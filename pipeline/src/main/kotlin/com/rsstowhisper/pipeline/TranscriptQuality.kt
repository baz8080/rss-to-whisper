package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.WhisperTranscription

/**
 * Scores a decode against the three failure modes the README documents, all of
 * which were previously only ever found by an external repair pass reading the
 * finished corpus.
 *
 * None of these are judgements about the audio. Each one is a property a
 * transcript of ordinary speech cannot have, so a transcript that has it was
 * produced by the decoder going wrong rather than by an unusual episode.
 *
 * Every threshold below is a starting point measured on the real corpus, not a
 * tuned constant; expect to move them after a run over the whole corpus.
 */
object TranscriptQuality {
    /**
     * Punctuation marks per word. Healthy episodes sit around 0.15 (the
     * README's paired beam-search trial measured a 0.1546 median before and
     * 0.1611 after). The failure mode is not a low value, it is near zero:
     * whisper drops into a mode where it emits no punctuation and no capitals
     * for a whole episode, and 654 episodes hit it.
     */
    const val MIN_PUNCTUATION_PER_WORD = 0.03

    /**
     * Seconds of speech per cue. A shredded episode measured 0.74 against 2.42
     * for the same audio decoded properly, so the gap is wide and 1.0 sits in
     * it.
     */
    const val MIN_SECONDS_PER_CUE = 1.0

    /**
     * Below this many cues, seconds-per-cue is noise: a two-cue episode says
     * nothing about how the decoder segmented.
     */
    const val MIN_CUES_FOR_CUE_RATE = 50

    /**
     * Share of all words covered by the single most frequent 4-gram. Greedy
     * decoding locks onto a phrase and emits it for minutes; measured across
     * the first eleven regenerated shows it hit 0.7%-5.0% of episodes per show.
     * Ordinary speech repeats a given 4-gram a handful of times in thousands of
     * words, which is far under this.
     */
    const val MAX_REPEATED_SHARE = 0.05

    /**
     * Consecutive cues with identical text. Three in a row is a chorus or a
     * chant; four is the decoder stuck.
     */
    const val MAX_REPEATED_CUE_RUN = 4

    /**
     * A stretch-copy: whisper spends a whole 30 s window on a few words it has
     * just transcribed, and the real speech in that window is lost. Signature
     * from #98: a cue of 20 s or more, under 0.8 words/s, with 80% of its words
     * already in the previous three cues.
     */
    const val MIN_STRETCH_SECONDS = 20.0
    const val MAX_STRETCH_WORDS_PER_SECOND = 0.8
    const val MIN_STRETCH_COPIED_SHARE = 0.8
    private const val STRETCH_LOOKBACK_CUES = 3

    const val LOW_CONFIDENCE_PROBABILITY = 0.3

    const val MAX_LOW_CONFIDENCE_SHARE = 0.2

    private const val NGRAM = 4

    private val PUNCTUATION = setOf('.', ',', '!', '?', ';', ':')

    const val FLAG_NO_SPEECH = "no-speech"
    const val FLAG_UNPUNCTUATED = "unpunctuated"
    const val FLAG_SHREDDED_CUES = "shredded-cues"
    const val FLAG_REPETITION_LOOP = "repetition-loop"
    const val FLAG_LOW_CONFIDENCE = "low-confidence"
    const val FLAG_STRETCH_COPY = "stretch-copy"

    fun score(transcription: WhisperTranscription): QualityReport {
        val cues = transcription.cues
        val text = cues.joinToString(" ") { it.text }
        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        val wordCount = words.size

        val punctuationPerWord =
            if (wordCount == 0) 0.0 else text.count { it in PUNCTUATION }.toDouble() / wordCount

        // The span the cues cover, not the sum of their lengths: the gaps
        // between cues are part of what the decoder chose to segment.
        val span =
            if (cues.isEmpty()) 0.0 else (cues.last().end - cues.first().start).coerceAtLeast(0.0)
        val secondsPerCue = if (cues.isEmpty()) 0.0 else span / cues.size

        val repeatedShare = repeatedShare(words)
        val longestRepeatedCueRun = longestRepeatedCueRun(cues.map { it.text })
        val stretchCopies = stretchCopies(cues)

        // Empty for anything decoded before token_timestamps was turned on, and
        // an absent signal must not read as a passing one.
        val probabilities = transcription.words.map { it.probability }
        val meanWordProbability = probabilities.average().takeIf { probabilities.isNotEmpty() }
        val lowConfidenceShare =
            if (probabilities.isEmpty()) {
                null
            } else {
                probabilities.count { it < LOW_CONFIDENCE_PROBABILITY }.toDouble() / probabilities.size
            }

        val flags = mutableListOf<String>()
        // Every check below needs words to measure, so a decode with none trips
        // nothing and would otherwise score as clean -- which made an empty
        // retry beat a real transcript on flag count alone.
        if (wordCount == 0) flags += FLAG_NO_SPEECH
        if (wordCount > 0 && punctuationPerWord < MIN_PUNCTUATION_PER_WORD) flags += FLAG_UNPUNCTUATED
        if (cues.size >= MIN_CUES_FOR_CUE_RATE && secondsPerCue < MIN_SECONDS_PER_CUE) flags += FLAG_SHREDDED_CUES
        if (repeatedShare > MAX_REPEATED_SHARE || longestRepeatedCueRun >= MAX_REPEATED_CUE_RUN) {
            flags += FLAG_REPETITION_LOOP
        }
        if (stretchCopies > 0) flags += FLAG_STRETCH_COPY
        if (lowConfidenceShare != null && lowConfidenceShare > MAX_LOW_CONFIDENCE_SHARE) flags += FLAG_LOW_CONFIDENCE

        return QualityReport(
            punctuationPerWord = punctuationPerWord,
            secondsPerCue = secondsPerCue,
            repeatedShare = repeatedShare,
            longestRepeatedCueRun = longestRepeatedCueRun,
            meanWordProbability = meanWordProbability,
            lowConfidenceShare = lowConfidenceShare,
            wordCount = wordCount,
            cueCount = cues.size,
            flags = flags,
            stretchCopies = stretchCopies,
        )
    }

    private fun stretchCopies(cues: List<Cue>): Int {
        val tokens = cues.map { cue -> TOKEN.findAll(cue.text.lowercase()).map { it.value }.toList() }
        return cues.indices.count { i ->
            val cue = cues[i]
            val seconds = cue.end - cue.start
            val words = tokens[i]
            if (seconds < MIN_STRETCH_SECONDS || words.isEmpty() || words.size / seconds >= MAX_STRETCH_WORDS_PER_SECOND) {
                return@count false
            }
            val previous = tokens.subList(maxOf(0, i - STRETCH_LOOKBACK_CUES), i).flatten().toSet()
            previous.isNotEmpty() && words.count { it in previous }.toDouble() / words.size >= MIN_STRETCH_COPIED_SHARE
        }
    }

    /**
     * Only occurrences *after* the first count. Every transcript contains some
     * most-frequent 4-gram, so counting the first one would make the floor
     * `4 / wordCount` -- which on its own exceeds the threshold for anything
     * under 80 words, flagging short episodes that repeat nothing at all.
     *
     * Capped at 1.0 because overlapping matches double-count: a cue of one word
     * repeated twenty times contains seventeen copies of the same 4-gram, which
     * would otherwise read as 340% of the episode.
     */
    private fun repeatedShare(words: List<String>): Double {
        if (words.size < NGRAM) return 0.0
        val normalised = words.map { it.lowercase().trim { c -> c in PUNCTUATION } }
        val counts = HashMap<String, Int>()
        var most = 0
        for (i in 0..normalised.size - NGRAM) {
            val gram = normalised.subList(i, i + NGRAM).joinToString(" ")
            val next = (counts[gram] ?: 0) + 1
            counts[gram] = next
            if (next > most) most = next
        }
        return minOf(1.0, (most - 1).toDouble() * NGRAM / words.size)
    }

    private fun longestRepeatedCueRun(texts: List<String>): Int {
        var longest = 0
        var run = 0
        var previous: String? = null
        for (text in texts) {
            val key = text.trim().lowercase()
            // A run of blank cues is not the decoder looping on a phrase.
            if (key.isEmpty()) {
                run = 0
                previous = null
                continue
            }
            run = if (key == previous) run + 1 else 1
            previous = key
            if (run > longest) longest = run
        }
        return longest
    }

    private val WHITESPACE = Regex("\\s+")

    private val TOKEN = Regex("[a-z0-9']+")
}

/** Paired so nothing downstream can write a transcript without its score. */
data class ScoredTranscription(
    val transcription: WhisperTranscription,
    val quality: QualityReport,
)

/**
 * What [TranscriptQuality.score] measured, written into `transcript.json` as
 * `episode_quality` so a later pass can find the bad episodes without decoding
 * anything.
 */
data class QualityReport(
    val punctuationPerWord: Double,
    val secondsPerCue: Double,
    val repeatedShare: Double,
    val longestRepeatedCueRun: Int,
    /** Null when the response carried no word timestamps, which is not the same as zero. */
    val meanWordProbability: Double?,
    val lowConfidenceShare: Double?,
    val wordCount: Int,
    val cueCount: Int,
    val flags: List<String>,
    /** Null for a report stored before stretch-copies were measured, which is not the same as none. */
    val stretchCopies: Int? = null,
) {
    val isFlagged: Boolean get() = flags.isNotEmpty()

    /**
     * Punctuation breaks a tie because the cue boundaries are derived from it,
     * so it is the one measure with consequences beyond itself. Rounded to the
     * precision [toMap] stores, or a report read back off disk loses to one in
     * memory on digits it was never able to keep.
     */
    fun isBetterThan(other: QualityReport): Boolean {
        // Counting only what both reports could have raised. low-confidence
        // needs word probabilities, so a decode scored without them cannot trip
        // it and would win on raw count against one that did.
        val unmeasured =
            buildSet {
                if (!hasWordTimes || !other.hasWordTimes) add(TranscriptQuality.FLAG_LOW_CONFIDENCE)
                if (stretchCopies == null || other.stretchCopies == null) add(TranscriptQuality.FLAG_STRETCH_COPY)
            }
        val mine = flags.count { it !in unmeasured }
        val theirs = other.flags.count { it !in unmeasured }

        return when {
            // Ahead of the count because an empty decode trips exactly one
            // flag, so on count alone it beats any decode bad enough to trip
            // two. A poor transcript is worth more than none, and on the orphan
            // path none is permanent.
            hasSpeech != other.hasSpeech -> hasSpeech
            mine != theirs -> mine < theirs
            round(punctuationPerWord) != round(other.punctuationPerWord) ->
                round(punctuationPerWord) > round(other.punctuationPerWord)
            // Last, because gaining word times is worth having but not worth
            // trading a transcript that scored better for.
            else -> hasWordTimes && !other.hasWordTimes
        }
    }

    /**
     * Both, because the flag is only as good as whoever wrote the report:
     * [fromMap] reads `episode_quality` off disk, and a file from another
     * version need not have flagged what current code flags. `wordCount == 0`
     * is the condition that raises the flag, so for a report this code scored
     * the two always agree.
     */
    internal val hasSpeech: Boolean get() = wordCount > 0 && TranscriptQuality.FLAG_NO_SPEECH !in flags

    /** Whether the decode carried word probabilities, which is what low-confidence needs. */
    private val hasWordTimes: Boolean get() = meanWordProbability != null

    val summary: String
        get() = "${if (flags.isEmpty()) "no flags" else flags.joinToString(", ")}, punctuation ${round(punctuationPerWord)}"

    /** Snake_case to match every other key `index.py` may one day read. */
    fun toMap(): Map<String, Any?> =
        mapOf(
            "punctuation_per_word" to round(punctuationPerWord),
            "seconds_per_cue" to round(secondsPerCue),
            "repeated_share" to round(repeatedShare),
            "longest_repeated_cue_run" to longestRepeatedCueRun,
            "mean_word_probability" to meanWordProbability?.let { round(it) },
            "low_confidence_share" to lowConfidenceShare?.let { round(it) },
            "word_count" to wordCount,
            "cue_count" to cueCount,
            "stretch_copies" to stretchCopies,
            "flags" to flags,
        )

    private fun round(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0

    companion object {
        /**
         * Reads back what [toMap] wrote, so a decode can be measured against
         * the one already on disk. Null when there is no `flags` key at all,
         * which is what a transcript written before the quality gate looks
         * like -- unscored has to read as no baseline, not as a passing one.
         *
         * [flags], [punctuationPerWord] and the presence of [meanWordProbability]
         * decide [isBetterThan]; the rest are read best-effort so a partial map
         * still compares. A map missing `mean_word_probability` therefore reads
         * as a decode that had no word times, which is the safe way round: it
         * loses the tie-break rather than winning it.
         */
        fun fromMap(stored: Map<*, *>?): QualityReport? {
            val flags = (stored?.get("flags") as? List<*>)?.map { it.toString() } ?: return null
            return QualityReport(
                punctuationPerWord = number(stored["punctuation_per_word"]) ?: 0.0,
                secondsPerCue = number(stored["seconds_per_cue"]) ?: 0.0,
                repeatedShare = number(stored["repeated_share"]) ?: 0.0,
                longestRepeatedCueRun = number(stored["longest_repeated_cue_run"])?.toInt() ?: 0,
                meanWordProbability = number(stored["mean_word_probability"]),
                lowConfidenceShare = number(stored["low_confidence_share"]),
                wordCount = number(stored["word_count"])?.toInt() ?: 0,
                cueCount = number(stored["cue_count"])?.toInt() ?: 0,
                flags = flags,
                stretchCopies = number(stored["stretch_copies"])?.toInt(),
            )
        }

        private fun number(value: Any?): Double? = (value as? Number)?.toDouble()
    }
}
