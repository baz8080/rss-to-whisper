package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranscriptPairTest {
    private val mapper = ObjectMapper()
    private val podcast = PodcastConfig(name = "Show", url = "https://feed")

    private val twoCues =
        whisperJson(
            Triple(0.0, 3.0, "So that is where the story begins."),
            Triple(3.0, 6.0, "We looked at the data again."),
        )

    private fun episodeDir(
        dataDir: Path,
        vtt: String,
        words: List<Map<String, Any?>>?,
        extra: Map<String, Any?> = emptyMap(),
    ): Path {
        val dir = dataDir.resolve("Show").resolve("2024-01-02-abcd1234-hello")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("audio.mp3"), "fake-mp3-bytes")
        Files.writeString(dir.resolve("transcript.json"), mapper.writeValueAsString(mapOf("episode_transcript" to vtt) + extra))
        if (words != null) writeWords(dir, words)
        return dir
    }

    private fun writeWords(
        dir: Path,
        words: List<Map<String, Any?>>,
    ) {
        OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(dir.resolve("words.jsonl.gz")))).use { out ->
            words.forEach { out.write(mapper.writeValueAsString(it) + "\n") }
        }
    }

    private fun readWords(dir: Path): List<Map<*, *>> =
        GZIPInputStream(Files.newInputStream(dir.resolve("words.jsonl.gz"))).bufferedReader().readLines()
            .map { mapper.readValue(it, Map::class.java) }

    private val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:03.000\n One two three.\n\n00:00:03.000 --> 00:00:06.000\n Four five.\n\n"

    private fun word(
        text: String,
        seg: Int,
        run: String? = null,
        // Inside its own cue: the fixture VTT's cues are three seconds each.
        start: Double = seg * 3.0 + 0.5,
    ) = mapOf("w" to text, "s" to start, "e" to start + 0.1, "p" to 0.9, "seg" to seg) + (run?.let { mapOf("run" to it) } ?: emptyMap())

    @Test
    fun `words that rebuild their cues are one decode`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episodeDir(tempDir, vtt, listOf(word(" One", 0), word(" two", 0), word(" three.", 0), word(" Four", 1), word(" five.", 1)))

        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    /** whisper omits the times of a word now and then, and the pipeline drops it. */
    @Test
    fun `a cue missing a word is still its own decode`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, listOf(word(" One", 0), word(" three.", 0), word(" Four", 1), word(" five.", 1)))

        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    /** The same words cut into different cues: every seg points at the wrong line. */
    @Test
    fun `words segmented by another decode diverge`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episodeDir(tempDir, vtt, listOf(word(" One", 0), word(" two", 0), word(" three.", 1), word(" Four", 1), word(" five.", 2)))

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }

    @Test
    fun `a transcript with no sidecar and no run is unpaired, not diverged`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, words = null)

        assertEquals(TranscriptPair.Unpaired, TranscriptPair.check(dir))
    }

    @Test
    fun `a run that recorded words and has no sidecar diverges`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, words = null, extra = mapOf("whisper_run" to mapOf("run_id" to "a", "words" to 5)))

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }

    @Test
    fun `words stamped with another run diverge even when the text fits`(
        @TempDir tempDir: Path,
    ) {
        val words =
            listOf(word(" One", 0, "b"), word(" two", 0, "b"), word(" three.", 0, "b"), word(" Four", 1, "b"), word(" five.", 1, "b"))
        val dir = episodeDir(tempDir, vtt, words, extra = mapOf("whisper_run" to mapOf("run_id" to "a", "words" to 5)))

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }

    @Test
    fun `stamped words beside a transcript with no run diverge`(
        @TempDir tempDir: Path,
    ) {
        val words =
            listOf(word(" One", 0, "b"), word(" two", 0, "b"), word(" three.", 0, "b"), word(" Four", 1, "b"), word(" five.", 1, "b"))
        val dir = episodeDir(tempDir, vtt, words)

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }

    @Test
    fun `blank cues keep their ordinal`() {
        val texts = TranscriptPair.cueTexts("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n\n\n00:00:01.000 --> 00:00:02.000\n Hi.\n\n")

        assertEquals(listOf("", " Hi.\n"), texts)
    }

    @Test
    fun `a decode writes one run id into both files`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), makeFeed(makeEntry("My Episode")), vtt = twoCues)

        pipeline.run()

        val dir = Files.list(tempDir.resolve("Show")).use { it.toList() }.single()
        val run = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("whisper_run")
        val runId = run.path("run_id").asText()
        assertTrue(runId.isNotBlank())
        assertEquals(setOf(runId), readWords(dir).map { it["run"] }.toSet())
        assertEquals(2, run.path("words").asInt())
        assertEquals("verbose_json", run.path("request").path("response_format").asText())
        assertEquals(64, run.path("audio_sha256").asText().length)
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    /** Its stored score describes one half of a pair, and the other half is from another decode. */
    @Test
    fun `re-transcription replaces a diverged pair whatever the stored score says`(
        @TempDir tempDir: Path,
    ) {
        val clean = mapOf("flags" to emptyList<String>(), "punctuation_per_word" to 0.5, "word_count" to 160)
        val dir = episodeDir(tempDir, vtt, listOf(word(" Unrelated", 0), word(" words.", 5)), extra = mapOf("episode_quality" to clean))
        val looping =
            whisperJson(*(0 until 20).map { Triple(it * 3.0, it * 3.0 + 3.0, "And that is the thing about it, really.") }.toTypedArray())
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(looping))

        val ok = pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertTrue(ok)
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    @Test
    fun `a batch that leaves a diverged pair behind fails and names it`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, listOf(word(" Unrelated", 0), word(" words.", 5)))
        Files.delete(dir.resolve("audio.mp3"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(twoCues))

        var ok = true
        val errors = loggedAtError { ok = pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"))) }

        assertFalse(ok)
        assertTrue(errors.any { "Show/${dir.fileName}" in it && "different decodes" in it }, errors.toString())
    }

    @Test
    fun `verifyPairs lists every diverged episode and nothing else`(
        @TempDir tempDir: Path,
    ) {
        val bad = episodeDir(tempDir, vtt, listOf(word(" Unrelated", 0), word(" words.", 5)))
        val good = tempDir.resolve("Show").resolve("2024-01-03-12345678-fine")
        Files.createDirectories(good)
        Files.writeString(good.resolve("transcript.json"), mapper.writeValueAsString(mapOf("episode_transcript" to vtt)))
        writeWords(good, listOf(word(" One", 0), word(" Four", 1)))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        val out = StringBuilder()
        val ok = pipeline.verifyPairs(out)

        assertFalse(ok)
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(listOf("Show/${bad.fileName}"), lines.map { it.substringBefore('\t') })
    }

    @Test
    fun `a list takes paths and ids, and ignores comments and anything after a tab`() {
        val (paths, ids) =
            RetranscribeRequest.parseList(
                listOf("# from verify", "Show/2024-01-02-abcd1234-hello\tcue 3 does not contain its words", "", "  deadBEEF  "),
            )

        assertEquals(listOf("Show/2024-01-02-abcd1234-hello"), paths)
        assertEquals(listOf("deadBEEF"), ids)
    }

    /** What a server that applies VAD returns: cues in real time, words in VAD-compressed time. */
    @Test
    fun `words outside their cues' time diverge even when the text fits`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, listOf(word(" One", 0, start = 0.5), word(" Four", 1, start = 0.5)))

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }

    @Test
    fun `a decode whose words are off the cue clock is never written, and stops the batch`(
        @TempDir tempDir: Path,
    ) {
        val first = episodeDir(tempDir, vtt, listOf(word(" Unrelated", 0), word(" words.", 5)))
        val second = tempDir.resolve("Show").resolve("2024-01-03-12345678-next")
        Files.createDirectories(second)
        Files.writeString(second.resolve("audio.mp3"), "fake-mp3-bytes")
        Files.writeString(second.resolve("transcript.json"), mapper.writeValueAsString(mapOf("episode_transcript" to vtt)))
        val before = Files.readString(first.resolve("transcript.json"))
        val vadShifted =
            """{"task":"transcribe","segments":[""" +
                """{"start":8.0,"end":11.0,"text":" One two.","words":[{"word":" One","start":0.0,"end":0.4,"probability":0.9},""" +
                """{"word":" two.","start":0.4,"end":0.9,"probability":0.9}]},""" +
                """{"start":12.0,"end":15.0,"text":" Three.","words":[{"word":" Three.","start":3.0,"end":3.5,"probability":0.9}]}""" +
                "]}"
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(vadShifted))

        val ok = pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${first.fileName}", "Show/${second.fileName}")))

        assertFalse(ok)
        assertEquals(1, txSvc.calls.size)
        assertEquals(before, Files.readString(first.resolve("transcript.json")))
    }

    @Test
    fun `an arrow in a cue's text does not start a cue`(
        @TempDir tempDir: Path,
    ) {
        val arrow = "WEBVTT\n\n00:00:00.000 --> 00:00:03.000\n Then File --> Export.\n\n00:00:03.000 --> 00:00:06.000\n Four five.\n\n"
        val dir =
            episodeDir(
                tempDir,
                arrow,
                listOf(word(" Then", 0), word(" File", 0), word(" -->", 0), word(" Export.", 0), word(" Four", 1), word(" five.", 1)),
            )

        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    /** A server sending one word per token splits a character across two, and each half arrives as U+FFFD. */
    @Test
    fun `a character split across two words still rebuilds its cue`(
        @TempDir tempDir: Path,
    ) {
        val sign = "WEBVTT\n\n00:00:00.000 --> 00:00:03.000\n Transcript \u00a9 Emily.\n\n"
        val dir = episodeDir(tempDir, sign, listOf(word(" Transcript", 0), word(" \uFFFD", 0), word("\uFFFD", 0), word(" Emily.", 0)))

        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    @Test
    fun `a words file that cannot be read is unreadable, not diverged`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, null)
        Files.createDirectory(dir.resolve("words.jsonl.gz"))

        assertIs<TranscriptPair.Unreadable>(TranscriptPair.check(dir))
    }

    @Test
    fun `a corrupt words file is diverged`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, null)
        Files.writeString(dir.resolve("words.jsonl.gz"), "not gzip at all")

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }

    @Test
    fun `verify lists a pair it could not read and fails`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, null)
        Files.createDirectory(dir.resolve("words.jsonl.gz"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertFalse(pipeline.verifyPairs(out))
        assertTrue(out.toString().startsWith("Show/${dir.fileName}\tcould not be checked"))
    }

    @Test
    fun `verify of a data directory that is not there fails`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(tempDir.resolve("unmounted"), listOf(podcast), feed = null)

        assertFalse(pipeline.verifyPairs(StringBuilder()))
    }

    @Test
    fun `verify of a podcast directory it cannot list fails`(
        @TempDir tempDir: Path,
    ) {
        episodeDir(tempDir, vtt, listOf(word(" One", 0)))
        val show = tempDir.resolve("Show")
        Files.setPosixFilePermissions(show, emptySet())
        try {
            val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

            assertFalse(pipeline.verifyPairs(StringBuilder()))
        } finally {
            Files.setPosixFilePermissions(show, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `a transcript that is not UTF-8 is diverged, not unreadable`(
        @TempDir tempDir: Path,
    ) {
        val dir = episodeDir(tempDir, vtt, listOf(word(" One", 0)))
        Files.write(dir.resolve("transcript.json"), byteArrayOf(0x7b, 0x22, 0xe9.toByte(), 0x22, 0x7d))

        assertIs<TranscriptPair.Diverged>(TranscriptPair.check(dir))
    }
}
