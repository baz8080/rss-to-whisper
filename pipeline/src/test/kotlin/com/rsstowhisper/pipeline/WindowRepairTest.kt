package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.SpeechDetector
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowRepairTest {
    private val mapper = ObjectMapper()
    private val podcast = PodcastConfig(name = "Show", url = "https://feed")

    /** One word per whitespace token, spread across its cue. */
    private fun transcription(cues: List<Cue>): WhisperTranscription {
        val words =
            cues.flatMapIndexed { index, cue ->
                val tokens = cue.text.trim().split(" ").filter { it.isNotEmpty() }
                val step = (cue.end - cue.start) / tokens.size.coerceAtLeast(1)
                tokens.mapIndexed { i, token -> Word(" $token", cue.start + i * step, cue.start + (i + 1) * step, 0.9, index) }
            }
        return WhisperTranscription.of(cues, words)
    }

    private val loopText = " Welcome to the show, with your host."

    /** Two good cues, a four-cue loop, and three good cues after it. */
    private fun looping(): List<Cue> =
        listOf(
            Cue(0.0, 3.0, " So that is where the story begins."),
            Cue(3.0, 6.0, " We looked at the data again, carefully."),
        ) + (0 until 4).map { Cue(6.0 + it * 3, 9.0 + it * 3, loopText) } +
            listOf(
                Cue(18.0, 21.0, " And then we found something odd."),
                Cue(21.0, 24.0, " Nobody expected that part at all."),
                Cue(24.0, 27.0, " It changed everything for us."),
            )

    @Test
    fun `a run of identical cues is a defect, windowed with a good cue either side`() {
        val cues = looping()

        val defects = WindowRepair.defectCues(cues)

        assertEquals(setOf(2, 3, 4, 5), defects)
        assertEquals(listOf(1..6), WindowRepair.windows(cues, defects))
        assertEquals(TimeWindow(3.0, 21.0), WindowRepair.window(cues, 1..6))
    }

    private fun decodedWindow(vararg cues: Cue): WhisperTranscription = transcription(cues.toList())

    @Test
    fun `the good cues either side are kept and only what lies between their words is spliced`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 12.0, " Welcome to the show, with your host."),
                Cue(12.0, 18.0, " Today we are talking about the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(2..5, replacement.range)
        assertEquals("text" to "text", replacement.anchorLeft to replacement.anchorRight)
        assertEquals(
            listOf(" Welcome to the show, with your host.", " Today we are talking about the telescope."),
            replacement.cues.map {
                it.text
            },
        )
    }

    /** A window starts cold, so its first words are the least trustworthy -- and the anchor cue already has them. */
    @Test
    fun `an anchor whose words the decode garbled falls back to time and says so`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " Look at the date again carefully."),
                Cue(6.0, 18.0, " Welcome to the show, today the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals("time" to "text", replacement.anchorLeft to replacement.anchorRight)
        assertEquals(listOf(" Welcome to the show, today the telescope."), replacement.cues.map { it.text })
        assertEquals(0.0, replacement.gapLeft)
    }

    @Test
    fun `a decode that runs on past the right anchor is cut before its words`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 13.0, " Welcome to the show."),
                Cue(17.4, 19.5, " And then we found"),
                Cue(19.5, 21.0, " something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals("text", replacement.anchorRight)
        assertEquals(listOf(" Welcome to the show."), replacement.cues.map { it.text })
        assertTrue(replacement.cues.single().start >= 6.0 && replacement.cues.single().end <= 18.0)
        assertTrue(replacement.gapRight!! > 0)
    }

    @Test
    fun `a splice renumbers the words after it`() {
        val base = transcription(looping())
        val replacement =
            WindowRepair.Replacement(
                1..6,
                listOf(Cue(3.0, 21.0, " One cue now.")),
                listOf(Word(" One", 3.0, 4.0, 0.9, 0), Word(" cue", 4.0, 5.0, 0.9, 0), Word(" now.", 5.0, 6.0, 0.9, 0)),
            )

        val spliced = WindowRepair.splice(base, listOf(replacement))

        assertEquals(4, spliced.cues.size)
        assertEquals(" Nobody expected that part at all.", spliced.cues[2].text)
        assertEquals(setOf(2), spliced.words.filter { it.text == " Nobody" }.map { it.segment }.toSet())
    }

    private fun episode(
        dataDir: Path,
        cues: List<Cue>,
    ): Path {
        val dir = dataDir.resolve("Show").resolve("2024-01-02-abcd1234-hello")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("audio.mp3"), "fake-mp3-bytes")
        val base = transcription(cues)
        Files.writeString(
            dir.resolve("transcript.json"),
            mapper.writeValueAsString(mapOf("episode_transcript" to base.vtt, "_id" to "abcd1234")),
        )
        base.writeWords(dir.resolve(WhisperTranscription.WORDS_FILENAME))
        return dir
    }

    @Test
    fun `repair splices a clean window decode into the pair and records it`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
        val window =
            whisperJson(
                Triple(3.0, 6.0, "We looked at the data again, carefully."),
                Triple(6.0, 12.0, "Welcome to the show, with your host."),
                Triple(12.0, 18.0, "Today we are talking about the telescope."),
                Triple(18.0, 21.0, "And then we found something odd."),
            )
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(window))

        val ok = pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertTrue(ok)
        assertEquals(listOf<TimeWindow?>(TimeWindow(3.0, 21.0)), txSvc.windows.toList())
        assertEquals(listOf(true), txSvc.conditioned)
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        val vtt = json.path("episode_transcript").asText()
        assertTrue("telescope" in vtt)
        assertEquals(1, Regex("Welcome to the show").findAll(vtt).count())
        assertTrue("It changed everything for us." in vtt)
        assertEquals("abcd1234", json.path("_id").asText())
        val repairs = json.path("whisper_run").path("repairs")
        assertEquals(1, repairs.size())
        assertEquals(4, repairs[0].path("defects_before").asInt())
        assertEquals(0, repairs[0].path("defects_after").asInt())
    }

    @Test
    fun `a window no better than it was is tried without history, then left alone`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val before = Files.readString(dir.resolve("transcript.json"))
        val stillLooping = whisperJson(*(0 until 5).map { Triple(3.0 + it * 3, 6.0 + it * 3, loopText.trim()) }.toTypedArray())
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(stillLooping))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(listOf(true, false), txSvc.conditioned)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    @Test
    fun `a pair from two decodes is not repaired`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        Files.writeString(
            dir.resolve("transcript.json"),
            mapper.writeValueAsString(mapOf("episode_transcript" to transcription(looping().drop(1)).vtt)),
        )
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(0, txSvc.calls.size)
    }

    /** The window decode's clock runs 1.5 s late; both anchors are found, so its words are mapped back between them. */
    @Test
    fun `a window decode on a different clock is mapped between its anchors`() {
        val base = transcription(looping())
        val late = { c: Cue -> Cue(c.start + 1.5, c.end + 1.5, c.text) }
        val decoded =
            decodedWindow(
                late(Cue(3.0, 6.0, " We looked at the data again, carefully.")),
                late(Cue(6.0, 18.0, " Welcome to the show, with your host.")),
                late(Cue(18.0, 21.0, " And then we found something odd.")),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals("text" to "text", replacement.anchorLeft to replacement.anchorRight)
        assertEquals(6.0, replacement.cues.single().start, 0.01)
        assertEquals(18.0, replacement.cues.single().end, 0.01)
    }

    @Test
    fun `the anchor cue's own full stop is not left behind as a cue`() {
        val base = transcription(looping())
        val cues = listOf(Cue(3.0, 6.0, " We looked at the data again, carefully."), Cue(6.0, 18.0, " Welcome to the show."))
        val words =
            transcription(cues).words.map { if (it.text == " carefully.") it.copy(text = " carefully") else it } +
                Word(".", 5.9, 6.0, 0.9, 0)
        val decoded = WhisperTranscription.of(cues, words.sortedWith(compareBy({ it.segment }, { it.start })))

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(listOf(" Welcome to the show."), replacement.cues.map { it.text })
    }

    @Test
    fun `cues over non-speech are dropped, and a run of music markers becomes one`() {
        val replacement =
            WindowRepair.Replacement(
                2..5,
                listOf(Cue(6.0, 9.0, " Thank you."), Cue(9.0, 12.0, " ♪"), Cue(12.0, 15.0, " ♪"), Cue(15.0, 18.0, " Real words here.")),
                listOf(Word(" Thank", 6.0, 7.0, 0.9, 0), Word(" you.", 7.0, 8.0, 0.9, 0), Word(" Real", 15.0, 16.0, 0.9, 3)),
            )

        val kept = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(15.2, 18.0)))

        assertEquals(listOf(" ♪", " Real words here."), kept.cues.map { it.text })
        assertEquals(Cue(9.0, 15.0, " ♪"), kept.cues.first())
        assertEquals(listOf(1), kept.words.map { it.segment })
    }

    @Test
    fun `a long cue that is only a sentence of the prompt is a defect`() {
        val prompt = WindowRepair.promptSentences("Hello, and welcome back. Let's get started.")
        // The second is someone actually saying it: short, and not beside a leak.
        val cues =
            listOf(Cue(0.0, 30.0, " Let's get started."), Cue(30.0, 33.0, " Welcome, everyone."), Cue(33.0, 35.0, " Let's get started."))

        assertEquals(setOf(0), WindowRepair.defectCues(cues, prompt))
    }

    @Test
    fun `the VAD tool's centiseconds are read as seconds`() {
        val output =
            "Detected 2 speech segments:\n" +
                "Speech segment 0: start = 58.00, end = 291.00\n" +
                "Speech segment 1: start = 531888.00, end = 532032.00\n"

        val spans = SpeechDetector.parse(output)

        assertEquals(listOf(TimeWindow(0.58, 2.91), TimeWindow(5318.88, 5320.32)), spans)
    }

    /** whisper can jump a whole window of real speech; the words either side still match, so only VAD shows it. */
    @Test
    fun `speech the repair skipped is counted as lost`() {
        val base = transcription(looping())
        val skipped =
            WindowRepair.Replacement(
                2..5,
                listOf(Cue(6.0, 18.0, " Welcome.")),
                listOf(Word(" Welcome.", 6.0, 6.5, 0.9, 0)),
            )

        val lost = WindowRepair.lostSpeech(base, skipped, listOf(TimeWindow(6.0, 18.0)))

        assertEquals(12.0 - 1.5, lost, 0.15)
        assertTrue(lost > WindowRepair.MAX_LOST_SPEECH_SECONDS)
    }

    @Test
    fun `a short copy of a prompt sentence beside a leak is part of it`() {
        val prompt = WindowRepair.promptSentences("Let's get started.")
        val cues =
            listOf(
                Cue(0.0, 30.0, " Let's get started."),
                Cue(30.0, 60.0, " Let's get started."),
                Cue(60.0, 60.0, " Let's get started."),
                Cue(60.0, 69.0, " I'm the publisher of Universe Today."),
            )

        assertEquals(setOf(0, 1, 2), WindowRepair.defectCues(cues, prompt))
    }

    /** Measured: a cue whose speech starts at 49.4 s had its first word at 29.8 s, smeared across an intro. */
    @Test
    fun `words smeared across music before the speech are moved onto it`() {
        val replacement =
            WindowRepair.Replacement(
                0..1,
                listOf(Cue(29.7, 55.4, " astronomy cast episode seven")),
                listOf(
                    Word(" astronomy", 29.8, 34.0, 0.9, 0),
                    Word(" cast", 34.1, 36.0, 0.9, 0),
                    Word(" episode", 36.1, 39.0, 0.9, 0),
                    Word(" seven", 54.0, 55.4, 0.9, 0),
                ),
            )

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(49.4, 55.4)))

        assertEquals(49.4, fitted.cues.single().start, 0.01)
        assertEquals(49.4, fitted.words.first().start, 0.01)
        assertTrue(fitted.words.zipWithNext().all { (a, b) -> a.start <= b.start })
        assertEquals(55.4, fitted.words.last().end, 0.01)
    }

    /** Barry heard "AI might be the" missing: whisper timed them inside the anchor cue, and a time cut dropped them. */
    @Test
    fun `words timed over an anchor that the anchor does not account for are kept`() {
        val base = transcription(looping())
        val cues =
            listOf(
                Cue(3.0, 6.0, " Look at the date again carefully. AI might be"),
                Cue(6.0, 18.0, " the most important new technology."),
            )
        val words =
            listOf(" Look", " at", " the", " date", " again", " carefully.").mapIndexed {
                    i,
                    t,
                ->
                Word(t, 3.0 + i * 0.3, 3.3 + i * 0.3, 0.9, 0)
            } +
                listOf(" AI", " might", " be").mapIndexed { i, t -> Word(t, 5.0 + i * 0.3, 5.3 + i * 0.3, 0.9, 0) } +
                listOf(" the", " most", " important", " new", " technology.").mapIndexed { i, t -> Word(t, 6.0 + i, 7.0 + i, 0.9, 1) }
        val decoded = WhisperTranscription.of(cues, words)

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals("time", replacement.anchorLeft)
        assertEquals(" AI", replacement.words.first().text)
    }
}
