package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptQualityTest {
    /**
     * Words are drawn pseudo-randomly from a vocabulary rather than cycled
     * through a handful of fixed sentences: repeating even four sentences over
     * sixty cues is a repetition loop by the measure under test, so a simpler
     * fixture trips the very flag this case exists to not trip.
     */
    private fun healthy(cueCount: Int = 60): WhisperTranscription = transcription(healthyTexts(cueCount), secondsPerCue = 3.0)

    private fun healthyTexts(cueCount: Int): List<String> {
        val vocabulary =
            (
                "the story really begins and it gets stranger we looked at data again carefully found " +
                    "something odd i asked him about later he had no explanation that is part nobody " +
                    "expected which changed everything for us all of them afterwards because nothing " +
                    "else would explain what happened next"
            ).split(" ").distinct()
        var seed = 20240101L

        fun next(): String {
            seed = (seed * 6364136223846793005L + 1442695040888963407L) ushr 1
            return vocabulary[(seed % vocabulary.size).toInt()]
        }
        return (0 until cueCount).map {
            val words = (0 until 10).joinToString(" ") { next() }
            // One comma and one full stop per ten words is about the 0.15
            // punctuation-per-word the README measured on healthy episodes.
            " ${words.substringBeforeLast(' ')}, ${words.substringAfterLast(' ')}."
        }
    }

    private fun transcription(
        texts: List<String>,
        secondsPerCue: Double = 3.0,
        wordProbability: Double = 0.9,
    ): WhisperTranscription {
        val cues =
            texts.mapIndexed { index, text ->
                Cue(index * secondsPerCue, (index + 1) * secondsPerCue, text)
            }
        val words =
            cues.flatMapIndexed { index, cue ->
                cue.text.split(" ").filter { it.isNotBlank() }.map {
                    Word(it, cue.start, cue.end, wordProbability, index)
                }
            }
        return WhisperTranscription(
            vtt = WhisperTranscription.VTT_HEADER,
            words = words,
            lastCueEnd = cues.lastOrNull()?.end,
            cues = cues,
        )
    }

    @Test
    fun `a healthy transcript trips nothing`() {
        val report = TranscriptQuality.score(healthy())

        assertEquals(emptyList(), report.flags)
        assertFalse(report.isFlagged)
        assertTrue(
            report.punctuationPerWord > TranscriptQuality.MIN_PUNCTUATION_PER_WORD,
            "expected healthy punctuation, got ${report.punctuationPerWord}",
        )
    }

    @Test
    fun `an unpunctuated episode is flagged`() {
        val stripped = healthyTexts(60).map { it.filter { c -> c !in ".,!?;:" } }
        val report = TranscriptQuality.score(transcription(stripped))

        assertEquals(listOf(TranscriptQuality.FLAG_UNPUNCTUATED), report.flags)
        assertEquals(0.0, report.punctuationPerWord)
    }

    @Test
    fun `a repetition loop is flagged`() {
        // What greedy decoding locking onto a phrase looks like in the corpus.
        val looping = List(20) { " And that is the thing about it, really." }
        val report = TranscriptQuality.score(transcription(looping))

        assertTrue(TranscriptQuality.FLAG_REPETITION_LOOP in report.flags, "flags were ${report.flags}")
        assertEquals(20, report.longestRepeatedCueRun)
    }

    @Test
    fun `a repeated phrase scattered through the episode is flagged without a cue run`() {
        // Never two in a row, so only the 4-gram share can catch it.
        val alternating =
            (0 until 40).map {
                if (it % 2 == 0) " buy now while stocks last." else " Something else entirely happened here, honestly."
            }
        val report = TranscriptQuality.score(transcription(alternating))

        assertTrue(TranscriptQuality.FLAG_REPETITION_LOOP in report.flags, "flags were ${report.flags}")
        assertEquals(1, report.longestRepeatedCueRun)
        assertTrue(report.repeatedShare > TranscriptQuality.MAX_REPEATED_SHARE)
    }

    @Test
    fun `a shredded episode is flagged`() {
        // The measured failure was 0.74s per cue.
        val shredded = List(200) { " word" }
        val report = TranscriptQuality.score(transcription(shredded, secondsPerCue = 0.5))

        assertTrue(TranscriptQuality.FLAG_SHREDDED_CUES in report.flags, "flags were ${report.flags}")
        assertEquals(0.5, report.secondsPerCue)
    }

    /** Short episodes segment coarsely by nature; the rate says nothing about them. */
    @Test
    fun `too few cues to judge the segmentation does not flag it`() {
        val short = List(TranscriptQuality.MIN_CUES_FOR_CUE_RATE - 1) { " A short, punctuated line." }
        val report = TranscriptQuality.score(transcription(short, secondsPerCue = 0.2))

        assertFalse(TranscriptQuality.FLAG_SHREDDED_CUES in report.flags, "flags were ${report.flags}")
    }

    @Test
    fun `a low confidence decode is flagged`() {
        val report = TranscriptQuality.score(transcription(healthyTexts(60), wordProbability = 0.1))

        assertEquals(listOf(TranscriptQuality.FLAG_LOW_CONFIDENCE), report.flags)
        assertEquals(1.0, report.lowConfidenceShare)
    }

    /**
     * Episodes decoded before token_timestamps was turned on carry no words. An
     * absent signal must not read as a passing one, nor as a failing one.
     */
    @Test
    fun `a response with no word timestamps skips the confidence check`() {
        val withWords = healthy()
        val report = TranscriptQuality.score(withWords.copy(words = emptyList()))

        assertNull(report.meanWordProbability)
        assertNull(report.lowConfidenceShare)
        assertFalse(TranscriptQuality.FLAG_LOW_CONFIDENCE in report.flags)
    }

    @Test
    fun `an empty transcription is flagged rather than dividing by zero`() {
        val report = TranscriptQuality.score(WhisperTranscription(WhisperTranscription.VTT_HEADER, emptyList()))

        assertEquals(listOf(TranscriptQuality.FLAG_NO_SPEECH), report.flags)
        assertEquals(0, report.wordCount)
        assertEquals(0, report.cueCount)
    }

    /** Cues can be present and still carry nothing to measure. */
    @Test
    fun `cues with no words are flagged as no speech`() {
        val report = TranscriptQuality.score(transcription(List(60) { "   " }))

        assertTrue(TranscriptQuality.FLAG_NO_SPEECH in report.flags, "flags were ${report.flags}")
        assertEquals(0, report.wordCount)
    }

    /**
     * The reason no-speech is a flag at all: every other check needs words, so
     * an unflagged empty decode would win the retry comparison on flag count
     * and throw away a real transcript.
     */
    @Test
    fun `an empty decode never beats a real one`() {
        val empty = TranscriptQuality.score(WhisperTranscription(WhisperTranscription.VTT_HEADER, emptyList()))
        val looping = TranscriptQuality.score(transcription(List(20) { " And that is the thing about it, really." }))

        assertTrue(looping.isFlagged, "control: the looping decode should be flagged")
        assertFalse(empty.isBetterThan(looping))
        assertTrue(looping.isBetterThan(empty))
    }

    /** One flag is all an empty decode can trip, so on count it beats two. */
    @Test
    fun `an empty decode never beats a real one, however badly the real one scored`() {
        val empty = TranscriptQuality.score(WhisperTranscription(WhisperTranscription.VTT_HEADER, emptyList()))
        val awful = TranscriptQuality.score(transcription(List(20) { " and that is the thing about it really" }))

        assertEquals(
            listOf(TranscriptQuality.FLAG_UNPUNCTUATED, TranscriptQuality.FLAG_REPETITION_LOOP),
            awful.flags,
            "control: the bad decode should trip more flags than the empty one",
        )
        assertEquals(listOf(TranscriptQuality.FLAG_NO_SPEECH), empty.flags)
        assertFalse(empty.isBetterThan(awful))
        assertTrue(awful.isBetterThan(empty))
    }

    /**
     * A stored report is only as good as the version that wrote it, and this
     * one says it has no words while flagging nothing -- which on flag count
     * alone would keep a blank transcript over a real re-decode for good.
     */
    @Test
    fun `a stored report with no words loses even when it flagged nothing`() {
        val storedBlank =
            QualityReport.fromMap(
                mapOf("word_count" to 0, "flags" to emptyList<String>()),
            )!!
        val awful = TranscriptQuality.score(transcription(List(20) { " and that is the thing about it really" }))

        assertFalse(storedBlank.isBetterThan(awful))
        assertTrue(awful.isBetterThan(storedBlank))
    }

    @Test
    fun `fewer flags wins, and punctuation breaks the tie`() {
        val clean = TranscriptQuality.score(healthy())
        val unpunctuated =
            TranscriptQuality.score(transcription(healthyTexts(60).map { it.filter { c -> c !in ".,!?;:" } }))

        assertTrue(clean.isBetterThan(unpunctuated))
        assertFalse(unpunctuated.isBetterThan(clean))

        // Same flags on both -- neither is flagged -- so only the ratio decides.
        val lessPunctuated = TranscriptQuality.score(transcription(healthyTexts(60).map { it.replace(",", "") }))
        assertEquals(clean.flags.size, lessPunctuated.flags.size)
        assertTrue(clean.isBetterThan(lessPunctuated))
        assertFalse(lessPunctuated.isBetterThan(clean))
    }

    /** A re-transcription compares its new decode against the score read back off disk. */
    @Test
    fun `a report read back from its map compares the same way`() {
        val clean = TranscriptQuality.score(healthy())
        val looping = TranscriptQuality.score(transcription(List(20) { " And that is the thing about it, really." }))

        val restored = QualityReport.fromMap(clean.toMap())!!

        assertEquals(clean.toMap(), restored.toMap())
        assertTrue(restored.isBetterThan(looping))
        assertFalse(looping.isBetterThan(restored))
    }

    @Test
    fun `a transcript with no recorded score is no baseline at all`() {
        assertNull(QualityReport.fromMap(null))
        assertNull(QualityReport.fromMap(emptyMap<String, Any?>()))
    }

    @Test
    fun `the report serialises every measure under a snake_case key`() {
        val map = TranscriptQuality.score(healthy()).toMap()

        assertEquals(
            setOf(
                "punctuation_per_word",
                "seconds_per_cue",
                "repeated_share",
                "longest_repeated_cue_run",
                "mean_word_probability",
                "low_confidence_share",
                "word_count",
                "cue_count",
                "flags",
            ),
            map.keys,
        )
        assertEquals(emptyList<String>(), map["flags"])
    }

    /** A report whose decode carried no word probabilities, as a server ignoring token_timestamps gives. */
    private fun unmeasured(
        flags: List<String> = emptyList(),
        punctuation: Double = 0.15,
    ) = QualityReport(
        punctuationPerWord = punctuation,
        secondsPerCue = 3.0,
        repeatedShare = 0.0,
        longestRepeatedCueRun = 1,
        meanWordProbability = null,
        lowConfidenceShare = null,
        wordCount = 160,
        cueCount = 40,
        flags = flags,
    )

    private fun measured(
        flags: List<String> = emptyList(),
        punctuation: Double = 0.15,
    ) = unmeasured(flags, punctuation).copy(
        meanWordProbability = 0.7,
        // Consistent with the flags asked for: score() raises low-confidence
        // from this share, so a clean report cannot carry a failing one.
        lowConfidenceShare = if (TranscriptQuality.FLAG_LOW_CONFIDENCE in flags) 0.4 else 0.01,
    )

    /**
     * The defect: low-confidence cannot be raised without word probabilities, so
     * a decode scored without them wins on raw count against one that tripped
     * it -- and the episode never acquires its word timings.
     */
    @Test
    fun `a decode that gained word times is not beaten by low-confidence alone`() {
        val before = unmeasured()
        val after = measured(flags = listOf(TranscriptQuality.FLAG_LOW_CONFIDENCE))

        assertFalse(before.isBetterThan(after))
        assertTrue(after.isBetterThan(before))
    }

    /** Word times are worth having, not worth trading a better transcript for. */
    @Test
    fun `gaining word times does not excuse a flag both reports could raise`() {
        val before = unmeasured()
        val after = measured(flags = listOf(TranscriptQuality.FLAG_REPETITION_LOOP))

        assertTrue(before.isBetterThan(after))
        assertFalse(after.isBetterThan(before))
    }

    /** Punctuation still decides first: word times are worth having, not worth a worse transcript. */
    @Test
    fun `a better punctuated transcript beats one that merely gained word times`() {
        val before = unmeasured(punctuation = 0.18)
        val after = measured(punctuation = 0.04)

        assertTrue(before.isBetterThan(after))
        assertFalse(after.isBetterThan(before))
    }

    @Test
    fun `with the flags equal, the one with word times wins`() {
        val before = unmeasured()
        val after = measured()

        assertTrue(after.isBetterThan(before))
        assertFalse(before.isBetterThan(after))
    }

    /** Both measured, so low-confidence counts normally. */
    @Test
    fun `low-confidence still counts when both decodes could raise it`() {
        val clean = measured()
        val lowConfidence = measured(flags = listOf(TranscriptQuality.FLAG_LOW_CONFIDENCE))

        assertTrue(clean.isBetterThan(lowConfidence))
        assertFalse(lowConfidence.isBetterThan(clean))
    }
}
