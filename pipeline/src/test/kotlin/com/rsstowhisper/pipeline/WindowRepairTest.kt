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
import kotlin.test.assertNull
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

    /** Measured: Life Scientific 2013-10-01, 28:35: a clip decoded to 2.7 s of its 16.8, the promo after a pause skipped. */
    @Test
    fun `a chunk decode that stops with speech still heard after it is picked up from there`() {
        val decode = transcription(listOf(Cue(1714.6, 1717.3, " And that's where we'll leave it.")))
        val speech = listOf(TimeWindow(1711.6, 1717.7), TimeWindow(1719.2, 1731.4))

        assertEquals(TimeWindow(1719.0, 1731.6), WindowRepair.unheardTail(decode, speech, TimeWindow(1714.8, 1731.6)))
        assertNull(WindowRepair.unheardTail(decode, listOf(TimeWindow(1711.6, 1717.7)), TimeWindow(1714.8, 1731.6)))
    }

    @Test
    fun `a chunk decode with no words is not sent again from the same place`() {
        assertNull(WindowRepair.unheardTail(transcription(emptyList()), listOf(TimeWindow(10.0, 30.0)), TimeWindow(9.8, 30.2)))
    }

    /** Measured: Behind the Bastards 2021-04-08, 1:51.8: "'m Jake Brennan", its "I" timed before where the chunk was picked up. */
    @Test
    fun `a chunk picked up again keeps its words from where the first decode stopped`() {
        val chunks =
            listOf(
                WindowRepair.Chunk(TimeWindow(102.4, 108.98), cutInSpeech = true),
                WindowRepair.Chunk(TimeWindow(111.64, 126.92)),
            )
        val first = transcription(listOf(Cue(102.4, 108.98, " or wherever you get your podcasts.")))
        val second = transcription(listOf(Cue(111.5, 114.0, " I'm Jake Brennan, and on the Disgraceland podcast")))

        val text = WindowRepair.stitched(chunks, listOf(first, second)).cues.joinToString("") { it.text }

        assertTrue(" I'm Jake Brennan" in text)
    }

    /** Measured: Behind the Bastards 2019-09-24, 59:56: a clip ending 0.1 s into "as" wrote "at". */
    @Test
    fun `a gap's last clip hears past its end, and words are kept to within a word's timing of either edge`() {
        val chunks = WindowRepair.chunks(listOf(TimeWindow(3573.1, 3596.22), TimeWindow(3596.42, 3599.74)), 3573.12, 3596.52)
        val decode =
            transcription(
                listOf(
                    Cue(3572.95, 3573.1, " presidential"),
                    Cue(3591.3, 3596.0, " imagine you're in the biggest band"),
                    Cue(3596.9, 3597.3, " Dave"),
                ),
            )

        val text = WindowRepair.stitched(chunks, listOf(decode)).cues.joinToString("") { it.text }

        assertEquals(3573.12, chunks.first().window.start)
        assertTrue(chunks.last().window.end > 3596.9)
        assertEquals(" presidential imagine you're in the biggest band", text)
    }

    /** Measured: Astronomy Cast 704, 2:59.9: the later clip timed "And I'd love to" 1.1 s early; both clips heard it. */
    @Test
    fun `chunks whose clocks disagree across an overlap are cut on the words both heard in turn`() {
        val chunks =
            listOf(WindowRepair.Chunk(TimeWindow(152.67, 180.87), cutInSpeech = true), WindowRepair.Chunk(TimeWindow(178.87, 183.54)))

        fun decode(
            text: String,
            vararg starts: Double,
        ): WhisperTranscription {
            val tokens = text.split(" ").drop(1).map { " $it" }
            val words = tokens.mapIndexed { i, t -> Word(t, starts[i], starts.getOrElse(i + 1) { starts[i] + 0.5 }, 0.9, 0) }
            return WhisperTranscription.of(listOf(Cue(starts.first(), words.last().end, text)), words)
        }
        val first = decode(" Women in Podcasting, and I'd love to", 178.12, 178.58, 178.76, 179.86, 180.13, 180.4, 180.89)
        val second = decode(" And I'd love to take home two trophies.", 178.73, 179.14, 179.35, 179.82, 179.98, 180.55, 180.88, 181.24)

        val stitched = WindowRepair.stitched(chunks, listOf(first, second))

        assertEquals(" Women in Podcasting, And I'd love to take home two trophies.", stitched.cues.joinToString("") { it.text })
        assertEquals(stitched.words.map { it.start }.sorted(), stitched.words.map { it.start })
    }

    @Test
    fun `the same word begun either side of a seam within half a second is written once`() {
        val chunks = listOf(WindowRepair.Chunk(TimeWindow(0.0, 10.0), cutInSpeech = true), WindowRepair.Chunk(TimeWindow(9.6, 20.0)))
        val first = transcription(listOf(Cue(7.0, 9.9, " the price you thought skyrockets. With")))
        val second = transcription(listOf(Cue(9.7, 12.0, " With Mint Mobile you never worry")))

        val text = WindowRepair.stitched(chunks, listOf(first, second)).cues.joinToString("") { it.text }

        assertEquals(1, Regex("With").findAll(text).count())
    }

    @Test
    fun `speech is cut at a pause into chunks that fit one of whisper's windows, overlapping where it has none`() {
        val chunks = WindowRepair.chunks(listOf(TimeWindow(8.0, 20.0), TimeWindow(21.0, 30.0), TimeWindow(31.0, 70.0)), 6.0, 80.0)

        assertEquals(
            listOf(TimeWindow(7.8, 30.2) to false, TimeWindow(30.8, 59.0) to true, TimeWindow(57.0, 80.5) to false),
            chunks.map { TimeWindow(Math.round(it.window.start * 10) / 10.0, Math.round(it.window.end * 10) / 10.0) to it.cutInSpeech },
        )
    }

    @Test
    fun `a chunk ends at the widest pause that leaves it long enough, not the last one that fits`() {
        val speech =
            listOf(TimeWindow(0.0, 5.0), TimeWindow(5.3, 12.0), TimeWindow(14.0, 20.0), TimeWindow(20.2, 27.0), TimeWindow(27.5, 33.0))

        assertEquals(listOf(12.2, 33.5), WindowRepair.chunks(speech, 0.0, 33.0).map { Math.round(it.window.end * 10) / 10.0 })
    }

    /** Measured: Behind the Bastards 2023-05-11, 1:43:51: a 0.4 s chunk came back with 6 s of the next one's speech. */
    @Test
    fun `words a chunk's decode starts past its end are dropped, and a cue cut short keeps the rest`() {
        val decode =
            transcription(
                listOf(
                    Cue(10.0, 12.0, " or wherever you get podcasts."),
                    Cue(12.0, 16.0, " what's up fam i'm sports journalist"),
                ),
            )

        val kept = WindowRepair.within(decode, 10.0, 13.0)

        assertEquals(listOf(" or wherever you get podcasts.", " what's up"), kept.cues.map { it.text })
        assertEquals(setOf(0, 1), kept.words.map { it.segment }.toSet())
    }

    @Test
    fun `two chunks cut inside speech are stitched at a word both heard, which is written once`() {
        val chunks =
            listOf(
                WindowRepair.Chunk(TimeWindow(0.0, 28.0), cutInSpeech = true),
                WindowRepair.Chunk(TimeWindow(26.0, 40.0)),
            )
        val first = transcription(listOf(Cue(20.0, 28.0, " and so we went down to the harbour")))
        val second = transcription(listOf(Cue(26.0, 34.0, " to the harbour where the boats were kept")))

        val text = WindowRepair.stitched(chunks, listOf(first, second)).cues.joinToString("") { it.text }

        assertEquals(1, Regex("harbour").findAll(text).count())
        assertTrue(text.startsWith(" and so we went down"))
        assertTrue(text.endsWith("where the boats were kept"))
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

    /** Measured: 99% Invisible 2020-12-22, 39:17.9: "You can find us" timed over 6.5 s of silence, said from 2364.6. */
    @Test
    fun `a cue opening where the one before ended is fitted from its first word`() {
        val words =
            listOf(
                " You" to 2359.18,
                " can" to 2360.8,
                " find" to 2363.4,
                " us" to 2364.1,
                " and" to 2365.22,
                " join" to 2365.3,
                " discussions" to 2365.4,
            )
                .mapIndexed {
                        i,
                        (w, t),
                    ->
                    Word(w, t, if (i < 6) listOf(2360.8, 2363.4, 2364.1, 2365.22, 2365.3, 2365.4)[i] else 2365.77, 0.9, 0)
                }
        val replacement = WindowRepair.Replacement(listOf(Cue(2357.85, 2365.77, words.joinToString("") { it.text })), words)
        val speech = listOf(TimeWindow(2347.52, 2358.14), TimeWindow(2364.61, 2378.69))

        val fitted = WindowRepair.dropNonSpeech(replacement, speech)

        assertEquals(2364.61, fitted.cues.single().start, 0.01)
        assertTrue(fitted.words.all { it.start >= 2364.6 })
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
    fun `a phrase repeated at a speaking pace is speech`() {
        val cues = listOf(Cue(1214.7, 1221.0, " He got picked up, taken in a boat, back and forth, back and forth, back and forth, and"))

        assertEquals(emptySet<Int>(), WindowRepair.defectCues(cues))
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
    fun `a pair from two decodes is not retimed`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        Files.writeString(
            dir.resolve("transcript.json"),
            mapper.writeValueAsString(mapOf("episode_transcript" to transcription(looping().drop(1)).vtt)),
        )
        val before = Files.readString(dir.resolve("transcript.json"))
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, speechDetector = FakeSpeechDetector(listOf(TimeWindow(0.0, 27.0))))

        val log = logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), retime = true)) }

        assertTrue(log.any { "Cannot retime" in it })
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    @Test
    fun `cues over non-speech are dropped, and a run of music markers becomes one`() {
        val replacement =
            WindowRepair.Replacement(
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
                listOf(Cue(0.1, 16.2, (invented + sung).joinToString(""))),
                words.dropLast(1) + words.last().copy(end = 16.2),
            )

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(13.19, 13.76), TimeWindow(14.2, 15.0)))

        assertEquals(sung.joinToString(""), fitted.cues.single().text)
        assertEquals(13.19, fitted.cues.single().start, 0.01)
        assertTrue(fitted.words.zipWithNext().all { (a, b) -> b.start - a.start >= 0.05 })
    }

    /** Measured: "It's the size of a squash court." spread 27.55–35.62 s across VAD's silence at 30.08–33.95 s; heard from 34 s. */
    @Test
    fun `words smeared across a silence inside a cue are moved onto the speech after it`() {
        val times = listOf(27.55, 28.69, 29.67, 30.98, 31.64, 31.97, 34.05)
        val tokens = listOf(" It's", " the", " size", " of", " a", " squash", " court.")
        val words = tokens.mapIndexed { i, t -> Word(t, times[i], if (i + 1 < times.size) times[i + 1] else 35.62, 0.9, 0) }
        val replacement = WindowRepair.Replacement(listOf(Cue(27.38, 35.62, tokens.joinToString(""))), words)

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
        val replacement = WindowRepair.Replacement(listOf(Cue(10.0, 20.0, words.joinToString("") { it.text })), words)

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(9.5, 14.0), TimeWindow(17.0, 21.0)))

        assertEquals(10.0, fitted.words.first().start, 0.01)
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
        val replacement = WindowRepair.Replacement(listOf(Cue(92.38, 113.62, words.joinToString("") { it.text })), words)

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

    @Test
    fun `a cue too short to hold half a second of speech is kept when it lies in speech`() {
        val replacement =
            WindowRepair.Replacement(
                listOf(Cue(6.0, 6.0, " AI might be"), Cue(6.0, 11.0, " the most important new technology.")),
                listOf(Word(" AI", 6.0, 6.0, 0.9, 0), Word(" the", 6.0, 7.0, 0.9, 1)),
            )

        val kept = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(3.0, 12.0)))

        assertEquals(listOf(" AI might be", " the most important new technology."), kept.cues.map { it.text })
        assertEquals(listOf(0, 1), kept.words.map { it.segment })
    }

    @Test
    fun `VAD output whose count disagrees with its segments is refused`() {
        assertFailsWith<SpeechDetectorFailed> { SpeechDetector.parse("") }
        assertFailsWith<SpeechDetectorFailed> {
            SpeechDetector.parse("Detected 2 speech segments:\nSpeech segment 0: start = 100.00, end = 200.00\n")
        }
    }

    @Test
    fun `VAD is cached with its audio's size, read back instead of run, and heard again if the audio or cache changed`(
        @TempDir tempDir: Path,
    ) {
        val audio = tempDir.resolve("Show").resolve("2024-01-02-abcd1234-hello").resolve("audio.mp3")
        Files.createDirectories(audio.parent)
        Files.writeString(audio, "fake-mp3-bytes")
        val runs = tempDir.resolve("runs")
        val vad = tempDir.resolve("vad.sh")
        Files.writeString(
            vad,
            "#!/bin/sh\necho x >> $runs\necho 'Detected 2 speech segments.'\n" +
                "echo 'Speech segment 0: start = 50.00, end = 325.00'\necho 'Speech segment 1: start = 400.00, end = 900.00'\n",
        )
        vad.toFile().setExecutable(true)
        val cache = tempDir.resolve("cache")
        val cached = cache.resolve("Show__2024-01-02-abcd1234-hello.json")
        val detector = SpeechDetector(vad.toString(), "model.bin", cache)
        val heard = listOf(TimeWindow(0.5, 3.25), TimeWindow(4.0, 9.0))

        assertEquals(heard, detector.speech(audio))
        assertEquals(heard, detector.speech(audio))
        assertEquals(1, Files.readAllLines(runs).size)
        Files.writeString(cached, "{\"audio_bytes\": 14, \"spans\": [[0.5, 3.25], [4.0")
        assertEquals(heard, detector.speech(audio))
        Files.writeString(audio, "fake-mp3-bytes, longer now")
        assertEquals(heard, detector.speech(audio))
        Files.writeString(cached, "[[1.0, 2.0], [3.0]")
        assertEquals(heard, detector.speech(audio))
        assertEquals(4, Files.readAllLines(runs).size)
        // A bare list, as #104 wrote its cache, carries no size and is taken as it is.
        Files.writeString(cached, "[[1.0, 2.0]]")
        assertEquals(listOf(TimeWindow(1.0, 2.0)), detector.speech(audio))
        val long = (0 until 4000).joinToString(", ", "[", "]") { "[${it * 2.5}, ${it * 2.5 + 1.25}]" }
        assertEquals(4000, SpeechDetector.readCache(long, 0)?.size)
    }

    /** Measured: Citation Needed 2019-07-17, 12:59: " However" held 5 s before it is said. */
    @Test
    fun `retiming writes the pair with the words moved, whisper's filler left, and a record of them, without the whisper server`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episode(
                tempDir,
                listOf(
                    Cue(0.0, 3.0, " So that is where the story begins."),
                    Cue(3.0, 12.0, " However, college did teach."),
                    Cue(12.0, 40.0, " Thanks for watching."),
                ),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                speechDetector = FakeSpeechDetector(listOf(TimeWindow(0.0, 2.9), TimeWindow(5.1, 12.0), TimeWindow(35.0, 40.0))),
            )

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), retime = true))

        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        assertTrue("00:00:04.800 --> 00:00:12.000\n However, college did teach." in json.path("episode_transcript").asText())
        assertTrue("00:00:12.000 --> 00:00:40.000\n Thanks for watching." in json.path("episode_transcript").asText())
        assertEquals("retime", json.path("whisper_run").path("repairs")[0].path("kind").asText())
        assertEquals(0, txSvc.calls.size)
        assertEquals(0, txSvc.pings)
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    @Test
    fun `a VAD that cannot run stops the batch before any target is decoded or marked`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, speechDetector = FakeSpeechDetector(emptyList(), fails = true))

        val ok = pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), retime = true))

        assertFalse(ok)
        assertEquals(0, txSvc.calls.size)
        assertFalse(Files.exists(dir.resolve(PodcastPipeline.RETRANSCRIBE_ATTEMPTED_FILENAME)))
    }

    @Test
    fun `a VAD that hears nothing under a transcript's speech is not trusted`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, looping())
        val before = Files.readString(dir.resolve("transcript.json"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, speechDetector = FakeSpeechDetector(emptyList()))

        val log = logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), retime = true)) }

        assertTrue(log.any { "not retiming it" in it })
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
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

        val log = logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), retime = true)) }

        assertEquals(0, txSvc.calls.size)
        assertTrue(log.any { "no word timings to move" in it })
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
                listOf(Cue(23.0, 39.35, " I got stung.")),
                listOf(Word(" I", 23.0, 24.0, 0.9, 0), Word(" got", 24.0, 25.0, 0.9, 0), Word(" stung.", 25.0, 26.0, 0.9, 0)),
            )

        val fitted = WindowRepair.dropNonSpeech(replacement, listOf(TimeWindow(27.55, 28.38), TimeWindow(38.82, 43.81)))

        assertEquals(38.82, fitted.words.first().start, 0.01)
        assertEquals(38.82, fitted.cues.single().start, 0.01)
    }
}
