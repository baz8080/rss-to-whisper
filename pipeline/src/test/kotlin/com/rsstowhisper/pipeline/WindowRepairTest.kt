package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.SpeechDetector
import com.rsstowhisper.external.SpeechDetectorFailed
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.Transcriber
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

    /** Measured: Life Scientific 2013-10-01, 28:49.7: "Thank you." held one window over a promo. */
    @Test
    fun `speech with no word near it is a gap, from the cue before to the cue after, filler included`() {
        val cues =
            listOf(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(5.0, 35.0, " Thank you."),
                Cue(35.0, 38.0, " And then we found something odd."),
            )

        assertEquals(
            listOf(0..2),
            WindowRepair.gapWindows(transcription(cues), listOf(TimeWindow(0.0, 3.0), TimeWindow(8.0, 33.0), TimeWindow(35.0, 38.0))),
        )
    }

    /** Measured: We Have Ways 2024-06-20, 10:57.3: two sentences crammed into no time where whisper skipped. */
    @Test
    fun `a crammed copy at a gap's edge goes with the gap`() {
        val cues =
            listOf(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(3.0, 3.0, " It's changing the agenda, it's changing the momentum, and you know it."),
                Cue(30.0, 33.0, " And then we found something odd."),
            )

        assertEquals(
            listOf(0..2),
            WindowRepair.gapWindows(transcription(cues), listOf(TimeWindow(0.0, 3.0), TimeWindow(4.0, 28.0), TimeWindow(30.0, 33.0))),
        )
    }

    @Test
    fun `a punctuated attempt nearly as full beats one in lower case without a mark`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, fillerOverPromo)
        val flat =
            serverJson(
                Cue(8.0, 20.0, " hey its nora jones and my podcast is back with more of my favorite musicians so check out"),
                Cue(20.0, 34.0, " the newest episode and come hang out with us in the studio and listen to the show today"),
            )
        val marked =
            serverJson(
                Cue(8.0, 20.0, " Hey, it's Nora Jones, and my podcast is back with more of my favorite musicians. So check out"),
                Cue(20.0, 34.0, " the newest episode, and come hang out with us in the studio and listen to the show today."),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                vtts = listOf(flat, marked),
                speechDetector = FakeSpeechDetector(promoSpeech),
            )

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairGaps = true))

        assertEquals(listOf(false, true), txSvc.conditioned)
        val vtt = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText()
        assertTrue("Hey, it's Nora Jones," in vtt)
    }

    @Test
    fun `punctuation does not buy a gap decode that leaves far more speech without words`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, fillerOverPromo)
        val half = serverJson(Cue(8.0, 18.0, " Hey, it's Nora Jones, and my podcast is back."))
        val full =
            serverJson(
                Cue(8.0, 20.0, " hey its nora jones and my podcast is back with more of my favorite musicians so check out"),
                Cue(20.0, 34.0, " the newest episode and come hang out with us in the studio and listen to the show today"),
            )
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                vtts = listOf(half, full),
                speechDetector = FakeSpeechDetector(promoSpeech),
            )

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairGaps = true))

        val vtt = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText()
        assertTrue("come hang out with us" in vtt)
    }

    /** Measured: Behind the Bastards 2022-05-31, 1:36–1:59: nothing written at all. */
    @Test
    fun `a gap with no cue over it lies between its two neighbours`() {
        val cues = listOf(Cue(0.0, 3.0, " So that is where the story begins."), Cue(30.0, 33.0, " And then we found something odd."))

        assertEquals(
            listOf(0..1),
            WindowRepair.gapWindows(transcription(cues), listOf(TimeWindow(0.0, 3.0), TimeWindow(6.0, 28.0), TimeWindow(30.0, 33.0))),
        )
    }

    @Test
    fun `a gap before the first cue has nothing to anchor to`() {
        val cues = listOf(Cue(20.0, 23.0, " So that is where the story begins."))

        assertEquals(emptyList(), WindowRepair.gapWindows(transcription(cues), listOf(TimeWindow(0.0, 15.0), TimeWindow(20.0, 23.0))))
    }

    @Test
    fun `speech is cut into chunks that start on it, fit one of whisper's windows, and meet without overlap`() {
        val chunks = WindowRepair.chunks(listOf(TimeWindow(8.0, 20.0), TimeWindow(21.0, 30.0), TimeWindow(31.0, 70.0)), 6.0, 80.0)

        assertEquals(
            listOf(TimeWindow(7.8, 30.2), TimeWindow(30.8, 50.5), TimeWindow(50.5, 70.2)),
            chunks.map {
                TimeWindow(Math.round(it.start * 10) / 10.0, Math.round(it.end * 10) / 10.0)
            },
        )
    }

    private val fillerOverPromo =
        listOf(
            Cue(0.0, 3.0, " So that is where the story begins."),
            Cue(3.0, 6.0, " We looked at the data again, carefully."),
            Cue(6.0, 36.0, " Thank you."),
            Cue(36.0, 39.0, " And then we found something odd."),
        )
    private val promoSpeech = listOf(TimeWindow(0.0, 6.0), TimeWindow(8.0, 34.0), TimeWindow(36.0, 39.0))

    @Test
    fun `a gap decoded from where its speech starts replaces the filler`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, fillerOverPromo)
        val promo =
            serverJson(
                Cue(8.0, 20.0, " Hey, it's Nora Jones, and my podcast is back with more of my favorite musicians."),
                Cue(20.0, 34.0, " So come hang out with us in the studio and listen to the show."),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(promo), speechDetector = FakeSpeechDetector(promoSpeech))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairGaps = true))

        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        val vtt = json.path("episode_transcript").asText()
        assertTrue("Nora Jones" in vtt)
        assertFalse("Thank you." in vtt)
        assertTrue("And then we found something odd." in vtt)
        assertEquals(listOf(false), txSvc.conditioned)
        assertEquals(
            TimeWindow(7.8, 34.2),
            txSvc.windows.single()?.let {
                TimeWindow(Math.round(it.start * 10) / 10.0, Math.round(it.end * 10) / 10.0)
            },
        )
        val gap = json.path("whisper_run").path("repairs")[0]
        assertEquals("gap", gap.path("kind").asText())
        assertTrue(gap.path("uncovered_after_s").asDouble() < gap.path("uncovered_before_s").asDouble())
    }

    /** Measured: Mindscape 2021-04-05, 2:59: the next line written early over the theme. */
    @Test
    fun `a gap decode that is the next line early is an echo`() {
        val cues =
            listOf(
                Cue(174.5, 179.06, " Our sociology, I will leave to you to decide whether that's any good. So let's go."),
                Cue(196.18, 198.18, " Zeynep Tufekci, welcome to the Mindscape Podcast."),
            )
        val early =
            WindowRepair.Replacement(
                1..0,
                listOf(Cue(179.47, 187.52, " Zeynep Tufekci, welcome to the Mindscape")),
                listOf(Word(" Zeynep", 179.47, 180.2, 0.9, 0), Word(" Mindscape", 185.5, 186.7, 0.9, 0)),
            )
        val said = early.copy(cues = listOf(Cue(179.47, 187.52, " Today we are talking about the sociology of technology with a guest.")))

        assertTrue(WindowRepair.echoesNeighbours(transcription(cues), early))
        assertFalse(WindowRepair.echoesNeighbours(transcription(cues), said))
    }

    @Test
    fun `a gap that comes back as filler again is left alone`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, fillerOverPromo)
        val before = Files.readString(dir.resolve("transcript.json"))
        val filler = serverJson(Cue(8.0, 34.0, " Thank you."))
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(filler), speechDetector = FakeSpeechDetector(promoSpeech))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairGaps = true))

        assertEquals(4, txSvc.calls.size)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    /** Measured: Universe Today 2014-01-20 Ep 42, 3:53. A 1.8 s sentence fits inside the lost-speech allowance. */
    private val sentenceThenStock =
        listOf(
            Cue(230.22, 233.34, " We may never discover the answer."),
            Cue(233.34, 236.1, " And that all just makes the mystery even more interesting."),
            Cue(236.1, 253.74, " Thanks for watching."),
        )
    private val sentenceSpeech = listOf(TimeWindow(230.2, 232.1), TimeWindow(233.3, 235.1), TimeWindow(240.5, 253.8))

    @Test
    fun `an attempt that leaves a heard sentence's time empty drops it`() {
        val outro =
            WindowRepair.Replacement(
                1..2,
                listOf(Cue(240.46, 243.09, " listening to the audio edition of Universe Today.")),
                listOf(Word(" listening", 240.46, 240.9, 0.9, 0), Word(" Universe", 242.0, 242.5, 0.9, 0)),
            )

        assertTrue(WindowRepair.dropsHeardCue(transcription(sentenceThenStock), outro, setOf(2), sentenceSpeech))
    }

    /** Measured: Universe Today 2014-01-20 Ep 38, 3:24.9: "for watching." over a nine-word sentence. */
    @Test
    fun `a word or two over a heard sentence's time does not stand in for it`() {
        val cues =
            listOf(
                Cue(201.84, 204.88, " the most promise."),
                Cue(204.88, 206.92, " And you can bet that scientists are continuing."),
                Cue(206.92, 236.86, " Thanks for watching."),
            )
        val outro =
            WindowRepair.Replacement(
                1..2,
                listOf(Cue(205.4, 213.62, " for watching."), Cue(213.67, 217.78, " Now make sure you click subscribe on our channel.")),
                listOf(Word(" for", 205.4, 206.0, 0.9, 0), Word(" watching", 206.0, 206.6, 0.9, 0), Word(" Now", 213.67, 214.0, 0.9, 1)),
            )
        val speech = listOf(TimeWindow(200.1, 203.2), TimeWindow(204.8, 205.6), TimeWindow(205.8, 208.3), TimeWindow(212.7, 217.1))

        assertTrue(WindowRepair.dropsHeardCue(transcription(cues), outro, setOf(2), speech))
    }

    @Test
    fun `a heard cue of two words is kept only if the attempt says both`() {
        val cues =
            listOf(
                Cue(10.0, 12.0, " We should stop here."),
                Cue(12.0, 13.4, " Absolutely not."),
                Cue(13.4, 30.0, " Thanks for watching."),
            )
        val speech = listOf(TimeWindow(10.0, 13.4))
        val dropped = WindowRepair.Replacement(1..2, listOf(Cue(20.0, 21.0, " Thanks.")), listOf(Word(" Thanks", 20.0, 20.5, 0.9, 0)))
        val kept =
            WindowRepair.Replacement(
                1..2,
                listOf(Cue(12.1, 13.3, " Absolutely not.")),
                listOf(Word(" Absolutely", 12.1, 12.8, 0.9, 0), Word(" not", 12.8, 13.2, 0.9, 0)),
            )

        assertTrue(WindowRepair.dropsHeardCue(transcription(cues), dropped, setOf(2), speech))
        assertFalse(WindowRepair.dropsHeardCue(transcription(cues), kept, setOf(2), speech))
    }

    @Test
    fun `one word whisper splits into pieces counts as one word over a dropped cue's time`() {
        val cues =
            listOf(
                Cue(10.0, 12.0, " We should stop here."),
                Cue(12.0, 14.0, " Listen on the app every single week."),
                Cue(14.0, 30.0, " Thanks for watching."),
            )
        val speech = listOf(TimeWindow(10.0, 14.0))
        val pieces =
            WindowRepair.Replacement(
                1..2,
                listOf(Cue(12.2, 13.6, " iHeartRadio")),
                listOf(
                    Word(" i", 12.2, 12.4, 0.9, 0),
                    Word("He", 12.4, 12.7, 0.9, 0),
                    Word("art", 12.7, 13.0, 0.9, 0),
                    Word("Radio", 13.0, 13.6, 0.9, 0),
                ),
            )

        assertTrue(WindowRepair.dropsHeardCue(transcription(cues), pieces, setOf(2), speech))
    }

    @Test
    fun `an attempt that keeps the sentence, or says something else over it, drops nothing`() {
        val base = transcription(sentenceThenStock)
        val kept =
            WindowRepair.Replacement(
                1..2,
                listOf(
                    Cue(233.34, 236.1, " And that all makes the mystery even more interesting."),
                    Cue(240.46, 243.09, " listening to the audio."),
                ),
                listOf(Word(" And", 233.4, 233.6, 0.9, 0), Word(" listening", 240.46, 240.9, 0.9, 1)),
            )
        val heardOtherwise =
            WindowRepair.Replacement(
                1..2,
                listOf(Cue(233.34, 236.1, " The data set was looked through again.")),
                (0 until 6).map { Word(" word", 233.4 + it * 0.3, 233.6 + it * 0.3, 0.9, 0) },
            )

        assertFalse(WindowRepair.dropsHeardCue(base, kept, setOf(2), sentenceSpeech))
        assertFalse(WindowRepair.dropsHeardCue(base, heardOtherwise, setOf(2), sentenceSpeech))
    }

    /** Measured: 99pi 2023-03-29 The Panopticon Effect, 36:51.5. */
    @Test
    fun `a stock sign-off held as long as a leak is a defect, and a spoken one is not`() {
        val cues =
            listOf(
                Cue(2181.54, 2186.66, " Every past episode of 99pi at 99pi.org."),
                Cue(2211.48, 2241.46, " See you next time."),
                Cue(2241.5, 2243.0, " See you next time."),
            )

        assertEquals(setOf(1), WindowRepair.defectCues(cues))
        assertEquals(1, WindowRepair.defectKinds(cues)["stock"])
    }

    /** Measured: Blindboy 2018-04-24 Marble Charles, 1:20:50. */
    @Test
    fun `the credit whisper also writes as Transcribed by ESO is stock`() {
        val cues = listOf(Cue(4806.9, 4808.4, " I want to see a crayfish."), Cue(4850.6, 4866.6, " Transcribed by ESO. Translated by"))

        assertEquals(setOf(1), WindowRepair.defectCues(cues))
    }

    @Test
    fun `a stock sign-off over silence is repaired away`(
        @TempDir tempDir: Path,
    ) {
        val cues =
            listOf(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 9.0, " And then we found something odd."),
                Cue(9.0, 39.0, " See you next time."),
            )
        val dir = episode(tempDir, cues)
        val again =
            serverJson(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 9.0, " And then we found something odd."),
                Cue(9.0, 39.0, " See you next time."),
            )
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                vtts = listOf(again),
                speechDetector = FakeSpeechDetector(listOf(TimeWindow(0.0, 9.0))),
            )

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        val vtt = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText()
        assertFalse("See you next time" in vtt)
        assertTrue("And then we found something odd." in vtt)
    }

    /** Measured: Spacetime 2025-10-29, 1:26.6. The anchor spells "Spacetime" in three tokens, the decode in two words. */
    @Test
    fun `an anchor word whisper split into tokens is matched whole, and not left half in the window`() {
        val cues =
            listOf(
                Cue(81.56, 86.0, " and another successful test flight."),
                Cue(86.0, 89.0, " Thank you."),
                Cue(89.0, 94.12, " Spacetime. Welcome to Spacetime with Stuart Gary."),
            )
        val anchorTokens =
            listOf(" Sp", "ac", "etime", ".", " Welcome", " to", " Space", "time", " with", " Stuart", " Gary", ".")
                .mapIndexed { i, t -> Word(t, 89.04 + i * 0.4, 89.44 + i * 0.4, 0.9, 2) }
        val base = WhisperTranscription.of(cues, transcription(cues).words.filter { it.segment < 2 } + anchorTokens)
        val decoded =
            decodedWindow(
                Cue(81.58, 86.0, " and another successful test flight."),
                Cue(86.62, 89.59, " All that and more coming up on Space Time."),
                Cue(90.5, 94.1, " Welcome to Space Time with Stuart Gary."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 0..2, setOf(1))

        assertEquals(" All that and more coming up on", replacement.words.joinToString("") { it.text })
    }

    /** Measured: Freakonomics 260, 18:25. Whisper skipped the anchor's words and smeared "Eric" from inside it across 12 s of silence. */
    @Test
    fun `a word opening inside an anchor it doesn't match but held well past it is kept`() {
        val cues =
            listOf(
                Cue(1105.24, 1107.94, " or whatever app you use to find your podcasts."),
                Cue(1108.5, 1138.48, " Thank you."),
            )
        val decoded =
            WhisperTranscription.of(
                listOf(Cue(1105.24, 1127.34, " Eric Posner, a law professor")),
                listOf(
                    Word(" Eric", 1105.38, 1110.61, 0.9, 0),
                    Word(" Pos", 1110.65, 1114.68, 0.9, 0),
                    Word("ner", 1114.68, 1118.71, 0.9, 0),
                    Word(",", 1118.71, 1121.41, 0.9, 0),
                    Word(" a", 1121.41, 1121.52, 0.9, 0),
                    Word(" law", 1121.52, 1121.82, 0.9, 0),
                    Word(" professor", 1121.82, 1122.71, 0.9, 0),
                ),
            )

        val replacement = WindowRepair.anchor(transcription(cues), decoded, 0..1, setOf(1))

        assertEquals(" Eric", replacement.words.first().text)
    }

    @Test
    fun `a word in pieces held past an anchor is kept whole, not from its second piece`() {
        val cues =
            listOf(
                Cue(1105.24, 1107.94, " or whatever app you use to find your podcasts."),
                Cue(1108.5, 1138.48, " Thank you."),
            )
        val decoded =
            WhisperTranscription.of(
                listOf(Cue(1105.24, 1127.34, " Eric Posner, a law professor")),
                listOf(
                    Word(" Er", 1105.38, 1106.0, 0.9, 0),
                    Word("ic", 1106.0, 1110.61, 0.9, 0),
                    Word(" Posner", 1110.65, 1118.71, 0.9, 0),
                    Word(",", 1118.71, 1121.41, 0.9, 0),
                    Word(" a", 1121.41, 1121.52, 0.9, 0),
                    Word(" law", 1121.52, 1121.82, 0.9, 0),
                    Word(" professor", 1121.82, 1122.71, 0.9, 0),
                ),
            )

        val replacement = WindowRepair.anchor(transcription(cues), decoded, 0..1, setOf(1))

        assertEquals(" Eric", replacement.words.take(2).joinToString("") { it.text })
    }

    /** Measured: Citation Needed 2019-07-17, 12:59.2. */
    @Test
    fun `a word held for seconds then carried on from keeps its end and a word's length`() {
        val replacement =
            WindowRepair.Replacement(
                0..2,
                listOf(
                    Cue(778.2, 779.18, " I hate it so much"),
                    Cue(779.18, 784.58, " However"),
                    Cue(784.58, 786.36, " College did teach"),
                ),
                listOf(
                    Word(" much", 778.86, 779.18, 0.9, 0),
                    Word(" However", 779.54, 784.58, 0.9, 1),
                    Word(" College", 784.94, 785.18, 0.9, 2),
                ),
            )

        val fitted = WindowRepair.fitStretched(replacement)

        assertEquals(784.08, fitted.words[1].start, 0.001)
        assertEquals(784.08, fitted.cues[1].start, 0.001)
        assertEquals(replacement.words[0], fitted.words[0])
    }

    @Test
    fun `a word held before a pause is left where whisper put it`() {
        val replacement =
            WindowRepair.Replacement(
                0..1,
                listOf(Cue(10.0, 14.0, " Well"), Cue(20.0, 22.0, " then we left.")),
                listOf(Word(" Well", 10.0, 14.0, 0.9, 0), Word(" then", 20.0, 20.4, 0.9, 1)),
            )

        assertEquals(replacement, WindowRepair.fitStretched(replacement))
    }

    /** Measured: Blindboy Soda Jerk, 2740–2752 s. */
    @Test
    fun `a few cues repeated in turn are a loop`() {
        val lap = listOf(" We're going to talk about", " the", " whiskey", " sour.")
        val cues =
            listOf(Cue(0.0, 3.0, " So that is where the story begins.")) +
                (0 until 16).map { Cue(3.0 + it * 0.2, 3.2 + it * 0.2, lap[it % 4]) } +
                listOf(Cue(6.5, 9.0, " And then we found something odd."))

        assertEquals((1..16).toSet(), WindowRepair.defectCues(cues))
    }

    @Test
    fun `two speakers trading short lines are not a loop`() {
        val cues =
            listOf(Cue(0.0, 3.0, " So that is where the story begins.")) +
                (0 until 8).map {
                    Cue(3.0 + it, 4.0 + it, if (it % 2 == 0) " Yeah." else " No.")
                }

        assertEquals(emptySet<Int>(), WindowRepair.defectCues(cues))
    }

    /** Measured: Planetary Radio 2019-10-11, 294.0 s. */
    @Test
    fun `a phrase repeated inside one cue faster than anyone speaks is a loop`() {
        val cues =
            listOf(
                Cue(290.1, 294.0, " staffer or a member of Congress, you're visited by a paid-for"),
                Cue(294.0, 294.9, " woman. You're visited by a paid-for woman. You're visited by a paid-for woman. You're visited by a"),
                Cue(294.9, 300.2, " lobbyist. I'm not a lobbyist."),
            )

        assertEquals(setOf(1), WindowRepair.defectCues(cues))
    }

    /** Measured: Citation Needed 2020-06-03, 266.2 s. */
    @Test
    fun `a fast laugh is not a looped phrase`() {
        val cues = listOf(Cue(266.2, 269.1, " Ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha ha"))

        assertEquals(emptySet<Int>(), WindowRepair.defectCues(cues))
    }

    @Test
    fun `a short prompt sentence that could be speech is scored, not refused`() {
        val base = transcription(looping())
        val replacement =
            WindowRepair.Replacement(
                2..5,
                listOf(Cue(6.0, 8.0, " Let's get started."), Cue(8.0, 18.0, " The WISE data set was looked through again.")),
                listOf(Word(" Let's", 6.0, 7.0, 0.9, 0), Word(" The", 8.0, 9.0, 0.9, 1)),
            )
        val prompt = WindowRepair.Prompt("Let's get started.")

        assertFalse(WindowRepair.voicesPrompt(base, replacement, prompt, setOf(2, 3, 4, 5)))
        assertEquals(1, WindowRepair.defectsAfter(base, replacement, prompt, setOf(2, 3, 4, 5)))
    }

    @Test
    fun `a phrase repeated at a speaking pace is speech`() {
        val cues = listOf(Cue(1214.7, 1221.0, " He got picked up, taken in a boat, back and forth, back and forth, back and forth, and"))

        assertEquals(emptySet<Int>(), WindowRepair.defectCues(cues))
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

    /** Measured: "…Drake's recordings never go out of print. Never?" was cut at the first "never". */
    @Test
    fun `a one-word anchor is found at its own time, not at the same word just before it`() {
        val cues =
            looping().take(6) +
                listOf(
                    Cue(18.0, 21.0, " And then we found something odd."),
                    Cue(21.0, 22.0, " Never?"),
                    Cue(22.0, 25.0, " It changed everything for us."),
                )
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.5, " Boyd sells his stake in Island Records."),
                Cue(18.5, 21.0, " His recordings never go out of print."),
                Cue(21.0, 22.0, " Never?"),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..7, setOf(2, 3, 4, 5))

        assertEquals("text" to "text", replacement.anchorLeft to replacement.anchorRight)
        assertEquals(" His recordings never go out of print.", replacement.cues.last().text)
    }

    /** Measured: the decode skipped "Did he..." and smeared "Nobody" 1.5 s back into it. */
    @Test
    fun `words smeared into an anchor the decode skipped are kept when they open the next cue`() {
        val cues =
            listOf(
                Cue(0.0, 3.0, " So that is where the story begins."),
                Cue(3.0, 6.0, " Did he..."),
                Cue(6.0, 9.0, " Nobody knows."),
            ) + (0 until 3).map { Cue(9.0 + it * 3, 12.0 + it * 3, loopText) } +
                listOf(Cue(18.0, 21.0, " And then we found something odd."), Cue(21.0, 24.0, " Nobody expected that part at all."))
        val base = transcription(cues)
        val decoded =
            WhisperTranscription.of(
                listOf(Cue(4.5, 9.0, " Nobody knows."), Cue(9.0, 18.0, " A detail."), Cue(18.0, 21.0, " And then we found something odd.")),
                listOf(
                    Word(" Nobody", 4.5, 6.2, 0.9, 0),
                    Word(" knows.", 6.2, 9.0, 0.9, 0),
                    Word(" A", 9.0, 13.0, 0.9, 1),
                    Word(" detail.", 13.0, 18.0, 0.9, 1),
                ) +
                    listOf(" And", " then", " we", " found", " something", " odd.").mapIndexed {
                            i,
                            t,
                        ->
                        Word(t, 18.0 + i * 0.5, 18.5 + i * 0.5, 0.9, 2)
                    },
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(3, 4, 5))

        assertEquals("time", replacement.anchorLeft)
        assertEquals(" Nobody knows.", replacement.cues.first().text)
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
    fun `list-defects keeps an episode it cannot read out of its list, and says so in its result`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        Files.writeString(dir.resolve("transcript.json"), "{ not json")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertFalse(pipeline.listDefects(out))
        assertEquals("", out.toString())
    }

    @Test
    fun `list-defects names each episode the repair would work on, with its kinds`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null)
        val out = StringBuilder()

        assertTrue(pipeline.listDefects(out))

        assertEquals("Show/${dir.fileName}\tdefects=4\twindows=1\tloop=4\tstretch=0\techo=0\tcopy=0\tleak=0\tstock=0\n", out.toString())
        assertEquals(0, txSvc.calls.size)
    }

    @Test
    fun `a window no better than it was is tried both ways plain, warmer and with a wider beam, then left alone`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val before = Files.readString(dir.resolve("transcript.json"))
        val stillLooping = whisperJson(*(0 until 5).map { Triple(3.0 + it * 3, 6.0 + it * 3, loopText.trim()) }.toTypedArray())
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(stillLooping))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(listOf(true, false, true, false, true, false), txSvc.conditioned)
        val (warmer, wider) = Transcriber.RETRY_WARMER to Transcriber.RETRY_WIDER_BEAM
        assertEquals(listOf(emptyMap(), emptyMap(), warmer, warmer, wider, wider), txSvc.retries)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    /** Measured: Rest is History 262 traded six loop cues for 27 s of the prompt. */
    @Test
    fun `a decode that voices the prompt is refused however few defects it has`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val before = Files.readString(dir.resolve("transcript.json"))
        val leaking =
            serverJson(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " we're going to talk about a few different things, and I think you'll enjoy it."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(leaking))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        assertEquals(6, txSvc.calls.size)
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

    /** Measured: a line invented over an instrumental intro, squeezed onto the song, crammed its first real line into 0.3 s. */
    @Test
    fun `words smeared over music ahead of words already on the speech are dropped, not crammed onto it`() {
        val invented = listOf(" Gather", " around", " now", " you're", " in", " for", " a", " story")
        val sung = listOf(" Old", " tales", " of", " lore", " all", " in", " search", " of", " a", " glory")
        val words =
            invented.mapIndexed { i, t -> Word(t, 0.1 + i * 1.3, 1.3 + i * 1.3, 0.9, 0) } +
                sung.mapIndexed { i, t -> Word(t, 12.0 + i * 0.3, 12.3 + i * 0.3, 0.9, 0) }
        val replacement =
            WindowRepair.Replacement(
                0..1,
                listOf(Cue(0.1, 16.2, (invented + sung).joinToString(""))),
                words.dropLast(1) + words.last().copy(end = 16.2),
            )

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(13.19, 13.76), TimeWindow(14.2, 15.0)))

        assertEquals(sung.joinToString(""), fitted.cues.single().text)
        assertEquals(13.19, fitted.cues.single().start, 0.01)
        assertTrue(fitted.words.zipWithNext().all { (a, b) -> b.start - a.start >= 0.05 })
    }

    @Test
    fun `a crammed cue a window decode brings is a defect`() {
        val base = transcription(looping())
        val cues =
            listOf(
                Cue(6.0, 17.9, " Today we are talking about the telescope."),
                Cue(17.9, 17.95, " what's going on in the universe, but also what's going on"),
            )
        val replacement = WindowRepair.Replacement(2..5, cues, transcription(cues).words)

        assertEquals(1, WindowRepair.defectsAfter(base, replacement))
    }

    /** Measured: "but also how peer", four words in 0.14 s at the decode's 30 s boundary. */
    @Test
    fun `a crammed cue of a few words a window decode brings is a defect`() {
        val base = transcription(looping())
        val cues = listOf(Cue(6.0, 17.86, " Today we are talking about the telescope."), Cue(17.86, 18.0, " but also how peer"))
        val replacement = WindowRepair.Replacement(2..5, cues, transcription(cues).words)

        assertEquals(1, WindowRepair.defectsAfter(base, replacement))
    }

    /** Measured: a decode skipped "Astronomycast" and wrote whisper's stock "I'll see you next time." where it is said. */
    @Test
    fun `a stock phrase a window decode brings, and the base did not say, is a defect`() {
        val base = transcription(looping())
        val cues = listOf(Cue(6.0, 7.0, " I'll see you next time."), Cue(7.0, 18.0, " Today we are talking about the telescope."))
        val replacement = WindowRepair.Replacement(2..5, cues, transcription(cues).words)

        assertEquals(1, WindowRepair.defectsAfter(base, replacement))
    }

    /** Measured: a prompted decode's "make maneuverability." at no length, where the unprompted one had it right. */
    @Test
    fun `two words at no length in a window decode are a defect`() {
        val base = transcription(looping())
        val cues = listOf(Cue(6.0, 18.0, " Today we are talking about the telescope."), Cue(18.0, 18.0, " make maneuverability."))
        val replacement = WindowRepair.Replacement(2..5, cues, transcription(cues).words)

        assertEquals(1, WindowRepair.defectsAfter(base, replacement))
    }

    /** Measured: every decode of a window with a leak and its own crammed line left one two-word scrap, and all were refused. */
    @Test
    fun `a window's own crammed cues count before, as a decode's count after`() {
        val cues =
            looping().take(7) +
                listOf(
                    Cue(21.0, 21.2, " not only what's going on in the universe, but also what's going on in the universe."),
                    Cue(21.2, 24.0, " Nobody expected that part at all."),
                )
        val base = transcription(cues)
        val defects = WindowRepair.defectCues(cues)

        assertEquals(defects.size + 1, WindowRepair.defectsBefore(base, 0..8, defects))
    }

    @Test
    fun `an anchor with no words, a music marker, falls back to time`() {
        val cues = looping().take(6) + listOf(Cue(18.0, 21.0, " ♪"), Cue(21.0, 24.0, " Nobody expected that part at all."))
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Today we are talking about the telescope."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals("time", replacement.anchorRight)
        assertEquals(listOf(" Today we are talking about the telescope."), replacement.cues.map { it.text })
    }

    @Test
    fun `a crammed anchor, which no decode replaces, does not count before`() {
        val cues = looping().take(7) + listOf(Cue(21.0, 21.0, " Right, right."), Cue(21.0, 24.0, " Nobody expected that part at all."))
        val base = transcription(cues)
        val defects = WindowRepair.defectCues(cues)

        assertEquals(defects.size, WindowRepair.defectsBefore(base, 0..7, defects))
    }

    @Test
    fun `an everyday word before the right anchor that it opens with is kept`() {
        val cues =
            looping().take(6) +
                listOf(
                    Cue(18.0, 21.0, " Stars form when clouds collapse."),
                    Cue(21.0, 24.0, " Nobody expected that part at all."),
                )
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Today we are looking at the star"),
                Cue(18.0, 21.0, " Stars form when clouds collapse."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" Today we are looking at the star", replacement.cues.last().text)
    }

    @Test
    fun `a short phrase the speaker repeats after the left anchor is kept`() {
        val cues =
            looping().take(1) + listOf(Cue(3.0, 6.0, " We looked at it again, you know.")) + looping().drop(2)
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at it again, you know."),
                Cue(6.0, 18.0, " You know, the thing is the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" You know, the thing is the telescope.", replacement.cues.first().text)
    }

    /** Measured: "It's the size of a squash court." spread 27.55–35.62 s across VAD's silence at 30.08–33.95 s; heard from 34 s. */
    @Test
    fun `words smeared across a silence inside a cue are moved onto the speech after it`() {
        val times = listOf(27.55, 28.69, 29.67, 30.98, 31.64, 31.97, 34.05)
        val tokens = listOf(" It's", " the", " size", " of", " a", " squash", " court.")
        val words = tokens.mapIndexed { i, t -> Word(t, times[i], if (i + 1 < times.size) times[i + 1] else 35.62, 0.9, 0) }
        val replacement = WindowRepair.Replacement(0..1, listOf(Cue(27.38, 35.62, tokens.joinToString(""))), words)

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(26.08, 30.08), TimeWindow(33.95, 51.1)))

        assertEquals(33.95, fitted.cues.single().start, 0.01)
        assertTrue(fitted.words.all { it.start >= 33.95 - 0.01 })
    }

    @Test
    fun `words at a speaking pace before a silence in the cue stay on their speech`() {
        val ahead = (0 until 8).map { Word(" word", 10.0 + it / 3.0, 10.3 + it / 3.0, 0.9, 0) }
        val words =
            ahead + listOf(Word(" and", 14.5, 15.0, 0.9, 0), Word(" um", 15.5, 16.0, 0.9, 0)) +
                (0 until 6).map { Word(" more", 17.0 + it * 0.5, 17.4 + it * 0.5, 0.9, 0) }
        val replacement = WindowRepair.Replacement(0..1, listOf(Cue(10.0, 20.0, words.joinToString("") { it.text })), words)

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(9.5, 14.0), TimeWindow(17.0, 21.0)))

        assertEquals(10.0, fitted.words.first().start, 0.01)
    }

    @Test
    fun `a long word before a short anchor word it resembles is kept`() {
        val cues = looping().take(6) + listOf(Cue(18.0, 21.0, " stared at the sky."), Cue(21.0, 24.0, " Nobody expected that part at all."))
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Then the whole thing started"),
                Cue(18.0, 21.0, " stared at the sky."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" Then the whole thing started", replacement.cues.last().text)
    }

    /** Measured: "Okay, so you're a baseball coach" smeared over the theme, then the sentence on the speech from 110.7 s. */
    @Test
    fun `words smeared ahead of the speech go into the room before the words already on it`() {
        val ahead =
            listOf(" Okay," to 92.7, " so" to 96.0, " you're" to 97.8, " a" to 100.9, " baseball" to 101.5, " coach" to 106.4, "," to 109.4)
        val on = listOf(" and", " you've", " just", " got", " a", " new", " player", " on", " your", " team")
        val words =
            ahead.mapIndexed { i, (t, s) -> Word(t, s, if (i + 1 < ahead.size) ahead[i + 1].second else 109.6, 0.9, 0) } +
                on.mapIndexed { i, t -> Word(t, 110.7 + i * 0.25, 110.95 + i * 0.25, 0.9, 0) }
        val replacement = WindowRepair.Replacement(0..1, listOf(Cue(92.38, 113.62, words.joinToString("") { it.text })), words)

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(108.7, 115.3)))

        assertEquals(words.joinToString("") { it.text }, fitted.cues.single().text)
        assertEquals(108.7, fitted.words.first().start, 0.01)
        assertEquals(110.7, fitted.words.first { it.text == " and" }.start, 0.01)
    }

    /** Measured: a stack of zero-length copies beside a window survived it, and the title it had decoded appeared twice. */
    @Test
    fun `a window grows over a stack of crammed cues at its edge, so none is its anchor`() {
        val cues =
            looping().take(8) +
                listOf(
                    Cue(24.0, 24.0, " And then we found something odd. Nobody expected that part."),
                    Cue(24.0, 27.0, " It changed everything for us."),
                    Cue(27.0, 30.0, " The end came soon after that."),
                )

        assertEquals(listOf(0..9), WindowRepair.windows(cues, WindowRepair.defectCues(cues)))
    }

    /** Measured: "…fully dexterous" ended one decode segment and "dexters come and rescue you" opened the next, the anchor. */
    @Test
    fun `a word the decode repeats across its segment break into the right anchor is not kept`() {
        val cues =
            looping().take(6) +
                listOf(
                    Cue(18.0, 21.0, " dexters come and rescue you."),
                    Cue(21.0, 24.0, " Nobody expected that part at all."),
                )
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 17.5, " Your crewmates are fully dexterous"),
                Cue(17.5, 21.0, " dexters come and rescue you."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" Your crewmates are fully", replacement.cues.last().text)
    }

    @Test
    fun `a word the decode repeats from the left anchor across its segment break is not kept`() {
        val base = transcription(looping())
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 18.0, " Carefully, today we talk about the telescope."),
                Cue(18.0, 21.0, " And then we found something odd."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" today we talk about the telescope.", replacement.cues.first().text)
    }

    /** Measured: "I'm Frisian." at a 30 s boundary, then "I'm Frisian Cain. I'm the publisher…", kept as the window's anchor. */
    @Test
    fun `a window grows over an edge cue the next one repeats, so it is not the anchor`() {
        val cues =
            looping().take(7) +
                listOf(
                    Cue(21.0, 21.6, " I'm Frisian."),
                    Cue(21.6, 26.0, " I'm Frisian Cain. I'm the publisher."),
                    Cue(26.0, 29.0, " It changed everything for us."),
                )

        assertEquals(listOf(0..8), WindowRepair.windows(cues, WindowRepair.defectCues(cues)))
    }

    @Test
    fun `words the decode repeats across its segment break into the right anchor are not kept`() {
        val cues =
            looping().take(6) +
                listOf(
                    Cue(18.0, 21.0, " I'm Frisian Cain, the publisher."),
                    Cue(21.0, 24.0, " Nobody expected that part at all."),
                )
        val base = transcription(cues)
        val decoded =
            decodedWindow(
                Cue(3.0, 6.0, " We looked at the data again, carefully."),
                Cue(6.0, 16.0, " Today we talk about the telescope."),
                Cue(16.0, 18.0, " I'm Frisian."),
                Cue(18.0, 21.0, " I'm Frisian Cain, the publisher."),
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(listOf(" Today we talk about the telescope."), replacement.cues.map { it.text })
    }

    /** Measured: the decode's "dexterous", three tokens, before an anchor the original rendered as "dexters come and rescue you." */
    @Test
    fun `a word split into tokens is still recognised as the right anchor's own`() {
        val cues =
            looping().take(6) +
                listOf(
                    Cue(18.0, 21.0, " dexters come and rescue you."),
                    Cue(21.0, 24.0, " Nobody expected that part at all."),
                )
        val base = transcription(cues)
        val left = transcription(listOf(Cue(3.0, 6.0, " We looked at the data again, carefully."))).words
        val tokens =
            listOf(" Your", " crewmates", " are", " fully").mapIndexed { i, t -> Word(t, 6.0 + i * 2.5, 8.0 + i * 2.5, 0.9, 1) } +
                listOf(" de", "xter", "ous").mapIndexed { i, t -> Word(t, 17.4 + i * 0.2, 17.6 + i * 0.2, 0.9, 1) } +
                listOf(" come", " and", " rescue", " you.").mapIndexed { i, t -> Word(t, 18.2 + i * 0.6, 18.8 + i * 0.6, 0.9, 1) }
        val decoded =
            WhisperTranscription.of(
                listOf(Cue(3.0, 6.0, " We looked at the data again, carefully."), Cue(6.0, 21.0, tokens.joinToString("") { it.text })),
                left + tokens,
            )

        val replacement = WindowRepair.anchor(base, decoded, 1..6, setOf(2, 3, 4, 5))

        assertEquals(" Your crewmates are fully", replacement.cues.last().text)
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

        assertEquals(6, txSvc.calls.size)
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
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = List(6) { disputing } + widened)

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), repairWindows = true))

        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        val vtt = json.path("episode_transcript").asText()
        assertEquals(7, txSvc.calls.size)
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
