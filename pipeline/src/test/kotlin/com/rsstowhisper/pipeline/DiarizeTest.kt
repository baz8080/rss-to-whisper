package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.SpeakerTurn
import com.rsstowhisper.external.WhisperRun
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiarizeTest {
    private val mapper = ObjectMapper()
    private val podcast = PodcastConfig(name = "Show", url = "https://feed")
    private val recordedSha = "recorded-sha"
    private val turns = listOf(SpeakerTurn(0.0, 12.0, 0), SpeakerTurn(12.0, 24.0, 1))

    private fun silmarillion(): List<Cue> =
        listOf(
            Cue(0.0, 3.0, " So that is where the story begins."),
            Cue(3.0, 6.0, " We looked at the data again, carefully."),
        ) + (0 until 4).map { Cue(6.0 + it * 3, 9.0 + it * 3, " Silmarillion.") } +
            listOf(
                Cue(18.0, 21.0, " And then we found something odd."),
                Cue(21.0, 24.0, " Nobody expected that part at all."),
            )

    private val tradedTurns = (0 until 4).map { SpeakerTurn(6.0 + it * 3, 9.0 + it * 3, it % 2) }

    /** The audio's size and hash as the decode recorded them: [recordedSha] unless a test says otherwise. */
    private fun episode(
        dataDir: Path,
        cues: List<Cue> = silmarillion(),
        sha: String? = recordedSha,
        recordedBytes: Long = FAKE_MP3_BYTES.size.toLong(),
        withWords: Boolean = false,
        name: String = "2024-01-02-abcd1234-hello",
    ): Path {
        val dir = dataDir.resolve("Show").resolve(name)
        Files.createDirectories(dir)
        Files.write(dir.resolve("audio.mp3"), FAKE_MP3_BYTES)
        val words =
            cues.flatMapIndexed { index, cue ->
                val tokens = cue.text.trim().split(" ")
                val step = (cue.end - cue.start) / tokens.size
                tokens.mapIndexed { i, token -> Word(" $token", cue.start + i * step, cue.start + (i + 1) * step, 0.9, index) }
            }
        val base = WhisperTranscription.of(cues, words)
        if (withWords) base.writeWords(dir.resolve(WhisperTranscription.WORDS_FILENAME))
        val record =
            mapOf("episode_transcript" to base.vtt, "_id" to "abcd1234") +
                if (sha == null) {
                    emptyMap()
                } else {
                    mapOf(WhisperRun.FIELD to mapOf("audio_sha256" to sha, "audio_bytes" to recordedBytes))
                }
        Files.writeString(dir.resolve("transcript.json"), mapper.writeValueAsString(record))
        return dir
    }

    private fun diarize(dir: Path) = RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), diarize = true)

    @Test
    fun `diarizing writes the turns keyed on the audio the transcript recorded`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertEquals(1, diarizer.calls)
        assertEquals(turns, SpeakerTurns.read(dir, recordedSha)?.turns)
        assertTrue(SpeakerTurns.current(dir, recordedSha, FAKE_MP3_BYTES.size.toLong(), diarizer.models))
    }

    @Test
    fun `diarizing alone never contacts the whisper server`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val before = Files.readString(dir.resolve("transcript.json"))
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = FakeSpeakerDiarizer(turns))

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertEquals(0, txSvc.pings)
        assertEquals(0, txSvc.calls.size)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    @Test
    fun `an episode whose turns are current for its audio is skipped on a second run`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)

        pipeline.retranscribe(diarize(dir))
        val messages = logged { assertTrue(pipeline.retranscribe(diarize(dir))) }

        assertEquals(1, diarizer.calls)
        assertTrue(messages.any { "already has speaker turns" in it })
        assertTrue(messages.any { "Diarized 0 of 1 episodes" in it })
    }

    @Test
    fun `turns made by other models are made again`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (first, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = FakeSpeakerDiarizer(turns))
        val newer = FakeSpeakerDiarizer(turns + SpeakerTurn(24.0, 30.0, 2), modelLabel = "b")
        val (second, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = newer)

        first.retranscribe(diarize(dir))
        second.retranscribe(diarize(dir))

        assertEquals(1, newer.calls)
        assertEquals(3, SpeakerTurns.read(dir, recordedSha)?.turns?.size)
    }

    @Test
    fun `an audio whose size differs from the record is hashed rather than trusted`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, recordedBytes = 999)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = FakeSpeakerDiarizer(turns))
        val actual = WhisperRun.sha256(dir.resolve("audio.mp3"))

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertNull(SpeakerTurns.read(dir, recordedSha))
        assertEquals(turns, SpeakerTurns.read(dir, actual)?.turns)
    }

    @Test
    fun `an episode with no recorded audio hash is hashed`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, sha = null)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = FakeSpeakerDiarizer(turns))

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertEquals(turns, SpeakerTurns.read(dir, WhisperRun.sha256(dir.resolve("audio.mp3")))?.turns)
    }

    @Test
    fun `a diarizer that fails its check stops the run before any episode`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(fails = true)
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)

        val errors = loggedAtError { assertFalse(pipeline.retranscribe(diarize(dir))) }

        assertEquals(0, diarizer.calls)
        assertFalse(Files.exists(dir.resolve(SpeakerTurns.FILENAME)))
        assertEquals(0, txSvc.calls.size)
        assertTrue(errors.any { "Cannot continue" in it && "diarize" in it })
    }

    @Test
    fun `an episode that cannot be diarized is skipped and the batch goes on`(
        @TempDir tempDir: Path,
    ) {
        val bad = episode(tempDir, name = "2024-01-01-aaaa1111-bad")
        val good = episode(tempDir, name = "2024-01-02-bbbb2222-good")
        val diarizer = FakeSpeakerDiarizer(turns, badFiles = setOf(bad.fileName.toString()))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)
        val request = RetranscribeRequest(paths = listOf("Show/${bad.fileName}", "Show/${good.fileName}"), diarize = true)

        val errors = loggedAtError { assertTrue(pipeline.retranscribe(request)) }

        assertEquals(2, diarizer.calls)
        assertFalse(Files.exists(bad.resolve(SpeakerTurns.FILENAME)))
        assertEquals(turns, SpeakerTurns.read(good, recordedSha)?.turns)
        assertTrue(errors.any { "Could not diarize" in it && "ffmpeg could not decode" in it })
    }

    @Test
    fun `three episodes in a row that cannot be diarized stop the batch`(
        @TempDir tempDir: Path,
    ) {
        val names = (1..4).map { "2024-01-0$it-abcd000$it-ep" }
        names.forEach { episode(tempDir, name = it) }
        val diarizer = FakeSpeakerDiarizer(turns, badFiles = names.toSet())
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)

        val errors =
            loggedAtError { assertFalse(pipeline.retranscribe(RetranscribeRequest(paths = names.map { "Show/$it" }, diarize = true))) }

        assertEquals(3, diarizer.calls)
        assertTrue(errors.any { "Stopping" in it && "3 episodes in a row" in it })
    }

    @Test
    fun `an unreadable turns file is diarized again`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        Files.writeString(dir.resolve(SpeakerTurns.FILENAME), "{\"audio_sha256\": \"recorded-sha\", \"tur")
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertEquals(1, diarizer.calls)
        assertEquals(turns, SpeakerTurns.read(dir, recordedSha)?.turns)
    }

    @Test
    fun `diarize without a configured diarizer is refused before any episode is touched`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        assertFalse(pipeline.retranscribe(diarize(dir)))

        assertFalse(Files.exists(dir.resolve(SpeakerTurns.FILENAME)))
        assertEquals(0, txSvc.pings)
    }

    @Test
    fun `an episode with no audio is not diarized, and says so`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        Files.delete(dir.resolve("audio.mp3"))
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, diarizer = diarizer)

        val errors = loggedAtError { pipeline.retranscribe(diarize(dir)) }

        assertEquals(0, diarizer.calls)
        assertTrue(errors.any { "it has no audio" in it })
    }

    @Test
    fun `list-defects lists the episode whose loop has no turns`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, withWords = true)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertTrue(pipeline.listDefects(out))

        assertEquals("Show/${dir.fileName}\tdefects=4\twindows=1\tloop=4\tstretch=0\techo=0\tcopy=0\tleak=0\tstock=0\n", out.toString())
    }

    @Test
    fun `list-defects lists nothing for a loop traded between voices`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, withWords = true)
        SpeakerTurns.write(dir, recordedSha, FAKE_MP3_BYTES.size.toLong(), FakeSpeakerDiarizer().models, tradedTurns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        val messages = logged { assertTrue(pipeline.listDefects(out)) }

        assertEquals("", out.toString())
        assertTrue(messages.any { "1 episodes have speaker turns; 1 hold repeats traded between voices, which cleared 1" in it })
    }

    @Test
    fun `list-defects uses turns for a transcript that recorded no audio, by the mp3's size`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, sha = null, withWords = true)
        SpeakerTurns.write(dir, "hashed-audio", FAKE_MP3_BYTES.size.toLong(), FakeSpeakerDiarizer().models, tradedTurns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertTrue(pipeline.listDefects(out))

        assertEquals("", out.toString())
    }

    @Test
    fun `window repair leaves a loop traded between voices alone, and repairs it without the turns`(
        @TempDir tempDir: Path,
    ) {
        val traded = episode(tempDir, sha = null, withWords = true, name = "2024-01-01-aaaa1111-traded")
        SpeakerTurns.write(traded, "hashed-audio", FAKE_MP3_BYTES.size.toLong(), FakeSpeakerDiarizer().models, tradedTurns)
        val untraded = episode(tempDir, sha = null, withWords = true, name = "2024-01-02-bbbb2222-untraded")
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        val messages =
            logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${traded.fileName}"), repairWindows = true)) }

        assertEquals(0, txSvc.calls.size)
        assertTrue(messages.any { "has no loops" in it })

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${untraded.fileName}"), repairWindows = true))
        assertTrue(txSvc.calls.isNotEmpty())
    }

    @Test
    fun `list-defects ignores turns made for other audio`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, withWords = true)
        SpeakerTurns.write(dir, "some-other-audio", FAKE_MP3_BYTES.size.toLong(), FakeSpeakerDiarizer().models, tradedTurns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertTrue(pipeline.listDefects(out))

        assertNotEquals("", out.toString())
        assertFalse("traded=" in out.toString())
    }

    @Test
    fun `list-defects counts what turns cleared on a line that still has a defect`(
        @TempDir tempDir: Path,
    ) {
        val cues =
            silmarillion() +
                (0 until 4).map { Cue(24.0 + it * 3, 27.0 + it * 3, " Welcome to the show, with your host.") } +
                listOf(Cue(36.0, 39.0, " It changed everything for us."))
        val dir = episode(tempDir, cues, withWords = true)
        SpeakerTurns.write(
            dir,
            recordedSha,
            FAKE_MP3_BYTES.size.toLong(),
            FakeSpeakerDiarizer().models,
            tradedTurns + SpeakerTurn(24.0, 36.0, 0),
        )
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertTrue(pipeline.listDefects(out))

        val line = out.toString().trimEnd()
        assertEquals(1, line.lines().size)
        assertTrue("\tdefects=4\t" in line && "\tloop=4\t" in line, line)
        assertTrue(line.endsWith("\ttraded=4"), line)
    }
}
