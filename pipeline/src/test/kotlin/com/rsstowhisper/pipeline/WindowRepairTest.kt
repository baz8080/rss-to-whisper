package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.SpeechDetector
import com.rsstowhisper.external.SpeechDetectorFailed
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `a run of identical cues is a defect, windowed with two good cues either side`() {
        val cues = looping()

        val defects = WindowRepair.defectCues(cues)

        assertEquals(setOf(2, 3, 4, 5), defects)
        assertEquals(listOf(0..7), WindowRepair.windows(cues, defects))
        assertEquals(TimeWindow(0.0, 24.0), WindowRepair.window(cues, 0..7))
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
        assertEquals(0.0, WindowRepair.gaps(base, 1..6, replacement).first)
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
        assertTrue(WindowRepair.gaps(base, 1..6, replacement).second!! > 0)
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
        assertEquals(listOf<TimeWindow?>(TimeWindow(0.0, 24.0)), txSvc.windows.toList())
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
        assertEquals("0", repairs[0].path("request").path("offset_t").asText())
    }

    @Test
    fun `a window no better than it was is tried both ways twice, then left alone`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val before = Files.readString(dir.resolve("transcript.json"))
        val stillLooping = whisperJson(*(0 until 5).map { Triple(3.0 + it * 3, 6.0 + it * 3, loopText.trim()) }.toTypedArray())
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(stillLooping))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(listOf(true, false, true, false), txSvc.conditioned)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    /** Measured: a prompted decode skipped two sentences and smeared its next four words across 10 s of speech. */
    @Test
    fun `of two clean attempts the one whose words sit on the speech is kept`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val smeared =
            serverJson(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " if Euclid goes far."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )
        val dense =
            serverJson(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 10.0, " The WISE data set was looked through to see what could be found."),
                Cue(10.0, 14.0, " I'm sure the Roman data set is going to be looked through."),
                Cue(14.0, 18.0, " I don't know if Euclid goes far enough into the infrared."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                vtts = listOf(smeared, dense),
                speechDetector = FakeSpeechDetector(listOf(TimeWindow(0.0, 27.0))),
            )

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(listOf(true, false), txSvc.conditioned)
        val vtt = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText()
        assertTrue("The WISE data set" in vtt)
        assertFalse("if Euclid goes far." in vtt)
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
        val prompt = WindowRepair.Prompt("Hello, and welcome back. Let's get started.")
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
        val prompt = WindowRepair.Prompt("Let's get started.")
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

    /** Barry's Physics Frontiers window: two sentences alternating, every copy clamped onto one timestamp. */
    @Test
    fun `an alternating loop stacked on one timestamp is a defect`() {
        val a = " So he wrote something like, we now live in a marvelous age where we discover fundamental law."
        val b = " to be done, or theory would be so complicated, would become so complicated."
        val cues =
            listOf(Cue(930.0, 942.0, " law, but there may be a time when this is no longer possible.")) +
                listOf(a, b, a, b, a).map { Cue(942.6, 942.6, it) } +
                listOf(Cue(943.9, 947.0, " no longer can do the calculations. And now 50 or more years after"))

        val defects = WindowRepair.defectCues(cues)

        assertEquals(setOf(1, 2, 3, 4, 5), defects)
    }

    @Test
    fun `ordinary speech is neither too fast nor an echo`() {
        val cues =
            listOf(
                Cue(0.0, 3.0, " So that is where the story begins, and it gets stranger."),
                Cue(3.0, 4.0, " Yeah."),
                Cue(4.0, 7.0, " We looked at the data again, carefully, and found it."),
            )

        assertEquals(emptySet(), WindowRepair.defectCues(cues))
    }

    /** Barry's Mindscape window: the anchor kept was the loop's first lap, so its hallucination survived the repair. */
    @Test
    fun `a cue holding a loop's sentence beside the loop is part of it`() {
        val lap = " So it's a system where it's often referred to as a neural network."
        val cues =
            listOf(
                Cue(432.94, 441.74, " in terms of a set of programmatic rules, but instead it's a system where it's often"),
                Cue(441.74, 442.10, " referred to as a neural network.$lap"),
            ) + (0 until 5).map { Cue(442.10, 442.10 + it * 6, lap) }

        assertEquals(setOf(1, 2, 3, 4, 5, 6), WindowRepair.defectCues(cues))
    }

    /** Barry's We Have Ways intro: the show's own Patreon read, written again over the music that follows it. */
    @Test
    fun `a long cue copying the read before it is a defect`() {
        val cues =
            listOf(
                Cue(
                    0.0,
                    13.03,
                    " Thank you for listening to We Have Ways of Making You Talk. Sign up to our Patreon to receive bonus content,",
                ),
                Cue(13.03, 19.62, " plus early access to all live show tickets. That's patreon.com slash wehaveways."),
                Cue(
                    30.0,
                    59.98,
                    " Thank you for listening to We Have Ways of Making You Talk. Sign up to our Patreon to receive bonus content,",
                ),
                Cue(91.0, 99.2, " On December 17th 1944, my dad, George Melan, and his twin Joseph were serving in the heavy"),
            )

        assertEquals(listOf(2), WindowRepair.longCopies(cues))
    }

    /** Barry's We Have Ways edges: the cues against the loop were its seed on one side and a garbled start on the other. */
    @Test
    fun `the cue against a defect is re-decoded and the one beyond it anchors`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Welcome to the show."),
                Cue(18.0, 21.0, " And then we found something odd."),
                Cue(21.0, 24.0, " Nobody expected that part at all."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 0..7, setOf(2, 3, 4, 5))

        assertEquals(1..6, replacement.range)
        assertEquals(" We looked at the data again, carefully.", replacement.cues.first().text)
        assertEquals(" And then we found something odd.", replacement.cues.last().text)
    }

    /** Why This Universe 77: the left anchor ended at 2408.28 s and the right began at 2408.24 s. */
    @Test
    fun `anchors that overlap in time do not throw`() {
        val cues =
            listOf(
                Cue(0.0, 3.04, " So that is where the story begins."),
                Cue(3.0, 3.0, " Welcome to the show, with your host."),
                Cue(3.0, 3.0, " Welcome to the show, with your host."),
                Cue(3.0, 3.0, " Welcome to the show, with your host."),
                Cue(3.0, 3.0, " Welcome to the show, with your host."),
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
            )
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(0.0, 3.1, " So that is where the story begins. New words."),
                Cue(3.1, 6.0, " We looked at the data again, carefully."),
            )

        WindowRepair.anchor(base, decoded, 0..5, setOf(1, 2, 3, 4))
    }

    /** The server's verbose_json for [cues], one word per token as [transcription] spreads them. */
    private fun serverJson(vararg cues: Cue): String {
        val words = transcription(cues.toList()).words
        val segments =
            cues.mapIndexed { i, cue ->
                mapOf(
                    "start" to cue.start,
                    "end" to cue.end,
                    "text" to cue.text,
                    "words" to
                        words.filter { it.segment == i }.map {
                            mapOf("word" to it.text, "start" to it.start, "end" to it.end, "probability" to 0.9)
                        },
                )
            }
        return mapper.writeValueAsString(mapOf("segments" to segments))
    }

    private val leakThenTitle =
        listOf(
            Cue(0.0, 30.0, " Let's get started."),
            Cue(30.0, 55.3, " Astronomy Cast, episode 626, the terrestrial planets."),
            Cue(55.8, 59.0, " Welcome to Astronomy Cast, our weekly journey."),
            Cue(59.2, 62.0, " I'm Fraser Cain, publisher of Universe Today."),
            Cue(62.7, 64.2, " With me, as always, is Dr. Pamela Gay."),
        )

    @Test
    fun `a prompt leak fitted onto the speech it covers is not a repair`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, leakThenTitle)
        val leakAgain =
            serverJson(Cue(0.0, 55.7, " Let's get started."), Cue(55.8, 59.0, " Welcome to Astronomy Cast, our weekly journey."))
        val title = serverJson(leakThenTitle[1], leakThenTitle[2])
        val vad = FakeSpeechDetector(listOf(TimeWindow(49.5, 55.5), TimeWindow(56.0, 70.0)))
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(leakAgain, title), speechDetector = vad)

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        val vtt = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText()
        assertEquals(listOf(true, false), txSvc.conditioned)
        assertTrue("the terrestrial planets" in vtt)
        assertFalse("Let's get started." in vtt)
    }

    @Test
    fun `a cue too short to hold half a second of speech is kept when it lies in speech`() {
        val replacement =
            WindowRepair.Replacement(
                2..5,
                listOf(Cue(6.0, 6.0, " AI might be"), Cue(6.0, 11.0, " the most important new technology.")),
                listOf(Word(" AI", 6.0, 6.0, 0.9, 0), Word(" the", 6.0, 7.0, 0.9, 1)),
            )

        val kept = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(3.0, 12.0)))

        assertEquals(listOf(" AI might be", " the most important new technology."), kept.cues.map { it.text })
        assertEquals(listOf(0, 1), kept.words.map { it.segment })
    }

    @Test
    fun `a filler in the decode's rendering of the anchor is walked past`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We um looked at the data again, closely."),
                Cue(6.0, 18.0, " Today we are talking about the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals("time", replacement.anchorLeft)
        assertEquals(listOf(" Today we are talking about the telescope."), replacement.cues.map { it.text })
    }

    @Test
    fun `a leading filler on an early clock does not cost the first new words`() {
        val base = transcription(looping())
        val early = { c: Cue -> Cue(c.start - 0.3, c.end - 0.3, c.text) }
        val decoded =
            decodedWindow(
                early(Cue(3.0, 6.0, " Um, we looked at the data again, closely.")),
                early(Cue(6.0, 18.0, " Today we are talking about the telescope.")),
                early(Cue(18.0, 21.0, " And then we found something odd.")),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" Today", replacement.words.first().text)
    }

    @Test
    fun `a right anchor rendered another way on an early clock is not kept as new words`() {
        val base = transcription(looping())
        val early = { c: Cue -> Cue(c.start - 1.5, c.end - 1.5, c.text) }
        val decoded =
            decodedWindow(
                early(Cue(3.0, 6.0, " We looked at the data again, carefully.")),
                early(Cue(6.0, 18.0, " Today we are talking about the telescope.")),
                early(Cue(18.0, 21.0, " Then we found something odd.")),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(listOf(" Today we are talking about the telescope."), replacement.cues.map { it.text })
    }

    @Test
    fun `words clamped onto an anchor join the cue beside them`() {
        val base = transcription(looping())
        val cues =
            listOf(Cue(3.0, 6.0, " Look at the date again carefully. AI might be"), Cue(6.0, 18.0, " the most important new technology."))
        val words =
            listOf(" Look", " at", " the", " date", " again", " carefully.").mapIndexed {
                    i,
                    t,
                ->
                Word(t, 3.0 + i * 0.3, 3.3 + i * 0.3, 0.9, 0)
            } +
                listOf(" AI", " might", " be").mapIndexed { i, t -> Word(t, 5.0 + i * 0.3, 5.3 + i * 0.3, 0.9, 0) } +
                listOf(" the", " most", " important", " new", " technology.").mapIndexed { i, t -> Word(t, 6.0 + i, 7.0 + i, 0.9, 1) }

        val replacement = WindowRepair.anchor(base, WhisperTranscription.of(cues, words), 1..6, setOf(2, 3, 4, 5))

        assertEquals(listOf(" AI might be the most important new technology."), replacement.cues.map { it.text })
        assertEquals(setOf(0), replacement.words.map { it.segment }.toSet())
    }

    @Test
    fun `without VAD a decode that skips the base's good speech is refused`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val before = Files.readString(dir.resolve("transcript.json"))
        val skipping =
            serverJson(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(6.0, 18.0, " Today we are talking about the telescope."),
                Cue(21.0, 24.0, " Nobody expected that part at all."),
            )
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(skipping))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(4, txSvc.calls.size)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    @Test
    fun `VAD output whose count disagrees with its segments is refused`() {
        assertFailsWith<SpeechDetectorFailed> { SpeechDetector.parse("") }
        assertFailsWith<SpeechDetectorFailed> {
            SpeechDetector.parse("Detected 2 speech segments:\nSpeech segment 0: start = 100.00, end = 200.00\n")
        }
    }

    @Test
    fun `a VAD that cannot run stops the batch before any target is decoded or marked`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, speechDetector = FakeSpeechDetector(emptyList(), fails = true))

        val ok = pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertFalse(ok)
        assertEquals(0, txSvc.calls.size)
        assertFalse(Files.exists(dir.resolve(PodcastPipeline.RETRANSCRIBE_ATTEMPTED_FILENAME)))
    }

    @Test
    fun `a VAD that hears nothing under a transcript's speech is not trusted`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val clean =
            serverJson(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Today we are talking about the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
                Cue(21.0, 24.0, " Nobody expected that part at all."),
            )
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(clean), speechDetector = FakeSpeechDetector(emptyList()))

        val log = logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true)) }

        assertTrue(log.any { "repairing it without VAD" in it })
        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        assertTrue("the telescope" in json.path("episode_transcript").asText())
        assertFalse(json.path("whisper_run").path("repairs")[0].path("speech_checked").asBoolean())
    }

    @Test
    fun `a pair whose decode has no word timings is refused before anything is read`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        Files.delete(dir.resolve(WhisperTranscription.WORDS_FILENAME))
        Files.writeString(
            dir.resolve("transcript.json"),
            mapper.writeValueAsString(
                mapOf("episode_transcript" to transcription(looping()).vtt, "whisper_run" to mapOf("run_id" to "r1", "words" to 0)),
            ),
        )
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        val log = logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true)) }

        assertEquals(0, txSvc.calls.size)
        assertTrue(log.any { "no word timings to splice into" in it })
    }

    @Test
    fun `words the decode timed past the right anchor's start are not kept before it`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 17.0, " Today we are talking about the telescope."),
                Cue(21.0, 21.0, " but how we know"),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(listOf(" Today we are talking about the telescope."), replacement.cues.map { it.text })
        assertTrue(replacement.cues.all { it.start >= 6.0 && it.end <= 18.0 })
    }

    @Test
    fun `a filler inside the right anchor's rendering is walked past`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Today we are talking about the telescope."),
                Cue(18.0, 21.0, " And, uh, then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(listOf(" Today we are talking about the telescope."), replacement.cues.map { it.text })
    }

    @Test
    fun `a new sentence starting inside the anchor is not taken for its missing last word`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 5.9, " We looked at the data again,"),
                Cue(5.9, 18.0, " The telescope is what we are talking about."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" The", replacement.words.first().text)
    }

    @Test
    fun `the closing word of an anchor cut mid-sentence is taken for the anchor's when it ends inside it`() {
        val cues = looping().toMutableList().also { it[1] = Cue(3.0, 6.0, " We looked at the data again, and") }
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, an"),
                Cue(6.0, 18.0, " today we are talking about the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" today", replacement.words.first().text)
    }

    @Test
    fun `a prompt sentence voiced without its punctuation, or only in part, is a leak`() {
        val prompt =
            WindowRepair.Prompt(
                "Hello, and welcome back to the show. Today we're going to talk about a few different things. Let's get started.",
            )
        val cues =
            listOf(
                Cue(0.0, 30.0, " Let's get started"),
                Cue(30.0, 60.0, " and we're going to talk about a few different things"),
                Cue(60.0, 75.0, " Astronomy Cast, episode 732."),
            )

        assertEquals(setOf(0, 1), WindowRepair.defectCues(cues, prompt))
    }

    @Test
    fun `a leak without its full stop fitted onto speech still counts after a repair`() {
        val base = transcription(leakThenTitle)
        val replacement =
            WindowRepair.Replacement(0..1, listOf(Cue(49.5, 54.96, " Let's get started")), listOf(Word(" Let's", 49.5, 51.0, 0.9, 0)))

        assertEquals(1, WindowRepair.defectsAfter(base, replacement, WindowRepair.Prompt("Let's get started."), setOf(0)))
    }

    @Test
    fun `an anchor the decode disputes is re-decoded with the window`(
        @TempDir tempDir: Path,
    ) {
        val cues =
            listOf(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 10.0, " This is one of those things where there's lots."),
                Cue(10.0, 12.0, " And then it happened."),
            ) + (0 until 4).map { Cue(12.0 + it * 3, 15.0 + it * 3, loopText) } +
                listOf(
                    Cue(24.0, 27.0, " Nobody expected that part at all."),
                    Cue(27.0, 30.0, " It changed everything for us."),
                    Cue(30.0, 33.0, " The end."),
                )
        val dir = episode(tempDir, cues)
        val rest =
            arrayOf(
                Cue(10.0, 12.0, " And then it happened."),
                Cue(12.0, 24.0, " Today we are talking about the telescope."),
                Cue(24.0, 27.0, " Nobody expected that part at all."),
                Cue(27.0, 30.0, " It changed everything for us."),
            )
        val disputing = serverJson(Cue(6.0, 10.0, " This is the data set we looked through."), *rest)
        val widened =
            serverJson(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 10.0, " The data set was looked through."),
                *rest,
            )
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = List(4) { disputing } + widened)

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        val vtt = json.path("episode_transcript").asText()
        assertEquals(5, txSvc.calls.size)
        assertTrue("The data set was looked through." in vtt)
        assertFalse("where there's lots" in vtt)
        assertEquals(1, json.path("whisper_run").path("repairs")[0].path("widened").asInt())
    }

    @Test
    fun `a crammed cue and a prompt sentence with a word of whisper's own beside a leak are part of it`() {
        val prompt = WindowRepair.Prompt("Hello, and welcome back to the show. Let's get started.")
        val cues =
            listOf(
                Cue(678.72, 682.86, " It has properties, and we've only just recently discovered that it's a thing."),
                Cue(682.96, 683.06, " And because of that, we're going to talk a little bit more about it."),
                Cue(683.06, 683.06, " So let's get started."),
                Cue(683.08, 713.06, " So let's get started."),
                Cue(743.04, 748.28, " So that space itself is an emergent phenomenon."),
            )

        assertEquals(setOf(1, 2, 3), WindowRepair.defectCues(cues, prompt))
    }

    @Test
    fun `a lone short sound before the speech is not where smeared words are moved to`() {
        val replacement =
            WindowRepair.Replacement(
                0..1,
                listOf(Cue(23.0, 39.35, " I got stung.")),
                listOf(Word(" I", 23.0, 24.0, 0.9, 0), Word(" got", 24.0, 25.0, 0.9, 0), Word(" stung.", 25.0, 26.0, 0.9, 0)),
            )

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(27.55, 28.38), TimeWindow(38.82, 43.81)))

        assertEquals(38.82, fitted.words.first().start, 0.01)
        assertEquals(38.82, fitted.cues.single().start, 0.01)
    }
}
