package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import kotlin.math.abs

/** Re-decoding only the stretches of an episode whose cues are defects: loops, copies and prompt leaks. */
internal object WindowRepair {
    /**
     * Cues taken either side of a defect. The outer one anchors the splice and is
     * kept verbatim; the one against the defect is re-decoded with it, since it is
     * the cue most often damaged: the loop's seed at its end, or a garbled start.
     */
    private const val MARGIN_CUES = 2

    /** [range] is the base cues this replaces; the anchors say how each edge was found: "text", "time", or "none" at the episode's edge. */
    data class Replacement(
        val range: IntRange,
        val cues: List<Cue>,
        val words: List<Word>,
        val anchorLeft: String = "none",
        val anchorRight: String = "none",
        /** Seconds the new words reach into each anchor's own time, before they are clamped out of it. */
        val intoLeft: Double = 0.0,
        val intoRight: Double = 0.0,
    )

    /** More than this, and the decode heard something other than the anchor where the anchor is. */
    const val MAX_INTO_ANCHOR_SECONDS = 2.0

    /** Seconds between each anchor of [window] and the nearest new word, where speech could have been lost; null without that anchor. */
    fun gaps(
        base: WhisperTranscription,
        window: IntRange,
        replacement: Replacement,
    ): Pair<Double?, Double?> {
        val left = (replacement.range.first - 1).takeIf { it >= window.first }?.let { base.cues[it].end }
        val right = (replacement.range.last + 1).takeIf { it <= window.last }?.let { base.cues[it].start }
        val first = replacement.words.firstOrNull() ?: return null to null
        return left?.let { first.start - it } to right?.let { it - replacement.words.last().end }
    }

    /** A cue that is nothing but one of the prompt's sentences, held this long, is whisper voicing the prompt over non-speech. */
    private const val MIN_PROMPT_LEAK_SECONDS = 10.0

    fun defectCues(
        cues: List<Cue>,
        prompt: Prompt = Prompt.NONE,
    ): Set<Int> {
        val defects = TranscriptQuality.stretchCopyCues(cues).toMutableSet()
        var i = 0
        while (i < cues.size) {
            val key = cues[i].text.trim().lowercase()
            var j = i
            while (key.isNotEmpty() && j + 1 < cues.size && cues[j + 1].text.trim().lowercase() == key) j++
            if (j - i + 1 >= TranscriptQuality.MAX_REPEATED_CUE_RUN) {
                defects.addAll(i..j)
                // The loop's first lap usually arrives inside the cue before it, which
                // must not survive as the repair's anchor.
                if (i > 0 && cues[i - 1].text.lowercase().contains(key)) defects += i - 1
                if (j + 1 < cues.size && cues[j + 1].text.lowercase().contains(key)) defects += j + 1
            }
            i = j + 1
        }
        defects += echoes(cues) + longCopies(cues)
        val voiced = cues.map { prompt.voices(it.text) }
        val leaks = cues.indices.filter { voiced[it] && cues[it].end - cues[it].start >= MIN_PROMPT_LEAK_SECONDS }.toMutableSet()
        // A short copy beside a leak is the same leak, and must not be kept as an anchor.
        var grew = true
        while (grew) {
            grew = leaks.addAll(leaks.flatMap { listOf(it - 1, it + 1) }.filter { it in cues.indices && voiced[it] })
        }
        defects += leaks
        // A cue of many words in no time beside a defect is part of it, and must not be kept as an anchor.
        val crammed = cues.indices.filter { crammed(cues[it]) }.toSet()
        grew = true
        while (grew) grew = defects.addAll(defects.flatMap { listOf(it - 1, it + 1) }.filter { it in crammed })
        return defects
    }

    /** More words than anyone says in the time: [MIN_WORDS_FOR_RATE] or more at over [MAX_WORDS_PER_SECOND]. */
    private fun crammed(cue: Cue): Boolean {
        val words = Prompt.wordsOf(cue.text).size
        return words >= MIN_WORDS_FOR_RATE && cue.end - cue.start < words / MAX_WORDS_PER_SECOND
    }

    private const val MAX_WORDS_PER_SECOND = 20.0

    /**
     * A cue of this many words in no time. Alone it is too common to call: over
     * 4,000 episodes have one, most reading as real lines whose timing collapsed.
     * Beside an echo it is part of the loop.
     */
    private const val MIN_WORDS_FOR_RATE = 5
    private const val ZERO_LENGTH_SECONDS = 0.1

    /** A sentence this long repeated verbatim within a few cues is the decoder looping, alternating or not. */
    private const val MIN_WORDS_FOR_ECHO = 8
    private const val ECHO_LOOKBACK_CUES = 3

    /** A sentence repeated within a few cues, and any stack of zero-length cues against it. */
    internal fun echoes(cues: List<Cue>): Set<Int> {
        val keys =
            cues.map { cue ->
                cue.text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.split(WHITESPACE).filter { it.isNotEmpty() }
            }
        val echoes =
            cues.indices.filter { i ->
                keys[i].size >= MIN_WORDS_FOR_ECHO && (maxOf(0, i - ECHO_LOOKBACK_CUES) until i).any { keys[it] == keys[i] }
            }.toMutableSet()
        val flat =
            cues.indices
                .filter { keys[it].size >= MIN_WORDS_FOR_RATE && cues[it].end - cues[it].start <= ZERO_LENGTH_SECONDS }
                .toSet()
        var grew = echoes.isNotEmpty()
        while (grew) grew = echoes.addAll(echoes.flatMap { listOf(it - 1, it + 1) }.filter { it in flat })
        return echoes
    }

    /**
     * A long cue that is mostly the text of the few before it: a stretch-copy that
     * kept a normal word rate. Measured on 16,465 pairs: at 15 s it adds 395 cues,
     * all reading as copies; without the length floor it adds 17,000, many real.
     */
    private const val MIN_LONG_COPY_SECONDS = 15.0
    private const val MIN_COPIED_SHARE = 0.8
    private const val COPY_LOOKBACK_CUES = 6
    private const val GRAM = 4

    internal fun longCopies(cues: List<Cue>): List<Int> {
        val keys =
            cues.map {
                    cue ->
                cue.text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.split(WHITESPACE).filter { it.isNotEmpty() }
            }

        fun grams(words: List<String>): Set<List<String>> = (0..words.size - GRAM).map { words.subList(it, it + GRAM) }.toSet()
        return cues.indices.filter { i ->
            if (keys[i].size < MIN_WORDS_FOR_ECHO || cues[i].end - cues[i].start < MIN_LONG_COPY_SECONDS) return@filter false
            val own = grams(keys[i])
            val before = grams((maxOf(0, i - COPY_LOOKBACK_CUES) until i).flatMap { keys[it] })
            own.isNotEmpty() && own.count { it in before }.toDouble() / own.size >= MIN_COPIED_SHARE
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** The initial prompt, for telling a cue whisper voiced from it: its punctuation and case vary, and it may stop partway. */
    class Prompt(text: String?) {
        private val words = wordsOf(text.orEmpty())

        /** A run of the prompt's words, in order, that is all but one word or most of the cue: whisper adds its own. */
        fun voices(cue: String): Boolean {
            val said = wordsOf(cue)
            if (said.size < MIN_PROMPT_WORDS) return false
            var longest = 0
            var previous = IntArray(words.size + 1)
            for (i in said.indices) {
                val current = IntArray(words.size + 1)
                for (j in words.indices) if (said[i] == words[j]) current[j + 1] = previous[j] + 1
                longest = maxOf(longest, current.max())
                previous = current
            }
            return longest >= MIN_PROMPT_WORDS && (longest >= said.size * MIN_PROMPT_SHARE || said.size - longest <= 1)
        }

        companion object {
            val NONE = Prompt(null)
            private const val MIN_PROMPT_WORDS = 3
            private const val MIN_PROMPT_SHARE = 0.8

            internal fun wordsOf(text: String): List<String> =
                text.lowercase().replace('\u2019', '\'').filter { it.isLetterOrDigit() || it == '\'' || it.isWhitespace() }
                    .split(WHITESPACE).filter { it.isNotEmpty() }
        }
    }

    fun windows(
        cues: List<Cue>,
        defects: Set<Int>,
    ): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (i in defects.sorted()) {
            val first = maxOf(0, i - MARGIN_CUES)
            val last = minOf(cues.size - 1, i + MARGIN_CUES)
            val previous = ranges.lastOrNull()
            if (previous != null && first <= previous.last + 1) {
                ranges[ranges.size - 1] = previous.first..maxOf(previous.last, last)
            } else {
                ranges += first..last
            }
        }
        return ranges
    }

    fun window(
        cues: List<Cue>,
        range: IntRange,
    ): TimeWindow = TimeWindow(cues[range.first].start, cues[range.last].end)

    /** Words compared when finding an anchor: the last of the cue before the defects, the first of the one after. */
    private const val ANCHOR_WORDS = 3

    /** How far past an anchor cue a new word may be timed and still be one of the anchor's own. */
    private const val OVERLAP_SLACK_SECONDS = 0.5

    /** How far from its old time an anchor's words may be found in the new decode. */
    private const val ANCHOR_SLACK_SECONDS = 2.0

    /**
     * The new decode of [range], cut at its anchor cues, kept verbatim. Each edge is found by the anchor's own words,
     * and only by time without them: whisper starts a window cold, so its first words are the least trustworthy.
     */
    fun anchor(
        base: WhisperTranscription,
        decoded: WhisperTranscription,
        range: IntRange,
        defects: Set<Int>,
    ): Replacement {
        val baseWords = base.words.groupBy { it.segment }
        val left = range.first.takeIf { it !in defects }
        val right = range.last.takeIf { it !in defects && it != left }
        val inner = (left?.plus(1) ?: range.first)..(right?.minus(1) ?: range.last)

        val words = decoded.words
        val normalised = words.map { normalise(it.text) }
        // Punctuation arrives as words of its own and would break a match between two real ones.
        val content = words.indices.filter { normalised[it].isNotEmpty() }
        val contentText = content.map { normalised[it] }

        fun anchorWords(cue: Int) = baseWords[cue].orEmpty().filter { normalise(it.text).isNotEmpty() }

        // Nearest the anchor's own time: a short anchor ("Never?") can also be a word of the sentence before it.
        fun leftMatch(): Int? {
            val cue = base.cues[left ?: return null]
            val tail = anchorWords(left).takeLast(ANCHOR_WORDS).map { normalise(it.text) }
            val end = anchorWords(left).last().end
            return matches(contentText, tail).map { content[it + tail.size - 1] }
                .filter { words[it].start in (cue.start - ANCHOR_SLACK_SECONDS)..(cue.end + ANCHOR_SLACK_SECONDS) }
                .minByOrNull { abs(words[it].end - end) }
        }

        fun rightMatch(from: Int): Int? {
            val cue = base.cues[right ?: return null]
            val head = anchorWords(right).take(ANCHOR_WORDS).map { normalise(it.text) }
            val start = anchorWords(right).first().start
            return matches(contentText, head).map { content[it] }
                .filter { it >= from && words[it].start in (cue.start - ANCHOR_SLACK_SECONDS)..(cue.end + ANCHOR_SLACK_SECONDS) }
                .minByOrNull { abs(words[it].start - start) }
        }

        // An edge found by text says how far the decode's clock is off, and the other
        // edge is judged in that corrected time: a window can start a second or two out.
        val textLeft = leftMatch()
        val shift =
            textLeft?.let { anchorWords(left!!).last().end - words[it].end }
                ?: rightMatch(0)?.let { anchorWords(right!!).first().start - words[it].start }
                ?: 0.0

        var from = 0
        var anchorLeft = "none"
        var newLeft: Double? = null
        var oldLeft: Double? = null
        if (left != null) {
            if (textLeft != null) {
                from = textLeft + 1
                newLeft = words[textLeft].end
                oldLeft = anchorWords(left).last().end
                anchorLeft = "text"
            } else {
                from = walkLeft(words, normalised, content, base.cues[left], anchorWords(left).map { normalise(it.text) }, shift)
                anchorLeft = "time"
            }
            // What is left of the anchor cue's own punctuation belongs to it, not to the repair.
            while (from < words.size && normalised[from].isEmpty()) from++
        }

        var until = words.size
        var anchorRight = "none"
        var newRight: Double? = null
        var oldRight: Double? = null
        if (right != null) {
            val textRight = rightMatch(from)
            if (textRight != null) {
                until = textRight
                newRight = words[until].start
                oldRight = anchorWords(right).first().start
                anchorRight = "text"
            } else {
                until = walkRight(words, normalised, content, base.cues[right], anchorWords(right).map { normalise(it.text) }, shift, from)
                anchorRight = "time"
            }
        }

        val clock = clock(newLeft, oldLeft, newRight, oldRight)
        val floor = left?.let { base.cues[it].end }
        val ceiling = right?.let { base.cues[it].start }
        val clocked =
            (
                if (from < until) {
                    words.subList(
                        from,
                        until,
                    )
                } else {
                    emptyList()
                }
            ).map { it.copy(start = clock(it.start), end = clock(it.end)) }
        val intoLeft = floor?.let { f -> clocked.maxOfOrNull { f - it.start } }?.coerceAtLeast(0.0) ?: 0.0
        val intoRight = ceiling?.let { c -> clocked.maxOfOrNull { it.start - c } }?.coerceAtLeast(0.0) ?: 0.0
        // Words timed well into the right anchor are the decode running on past it: the anchor already holds that time.
        val kept = clocked.filter { ceiling == null || it.start < ceiling + OVERLAP_SLACK_SECONDS }
        val cues = mutableListOf<Cue>()
        val out = mutableListOf<Word>()
        for ((segment, segmentWords) in kept.groupBy { it.segment }.toSortedMap()) {
            val original = decoded.cues[segment]
            val whole = segmentWords.size == decoded.words.count { it.segment == segment }
            val text = if (whole) original.text else segmentWords.joinToString("") { it.text }
            var start = if (whole) clock(original.start) else segmentWords.first().start
            var end = if (whole) clock(original.end) else segmentWords.last().end
            if (floor != null) {
                start = maxOf(start, floor)
                end = maxOf(end, floor)
            }
            if (ceiling != null) {
                start = minOf(start, ceiling)
                end = minOf(end, ceiling)
            }
            // Words kept from over an anchor's span belong after it, not inside it.
            out +=
                segmentWords.map {
                    // Anchors can overlap by a few hundredths of a second; the floor wins.
                    val low = floor ?: it.start
                    val wordStart = it.start.coerceIn(low, maxOf(low, ceiling ?: it.start))
                    it.copy(start = wordStart, end = it.end.coerceIn(wordStart, maxOf(wordStart, ceiling ?: it.end)), segment = cues.size)
                }
            cues += Cue(start, maxOf(start, end), text)
        }
        // Words clamped onto an anchor's edge leave a cue of no length there; they belong with the cue beside them.
        if (cues.size > 1 && floor != null && cues.first().let { it.start == floor && it.end <= it.start }) merge(cues, out, 0)
        if (cues.size > 1 && ceiling != null && cues.last().let { it.start == ceiling && it.end <= it.start }) {
            merge(
                cues,
                out,
                cues.size - 2,
            )
        }
        return Replacement(
            range = inner,
            cues = cues,
            words = out,
            anchorLeft = anchorLeft,
            anchorRight = anchorRight,
            intoLeft = intoLeft,
            intoRight = intoRight,
        )
    }

    /**
     * Where new content starts when the anchor's closing words are not there as text: the decode's words
     * walked over the anchor's while they resemble them. Whatever follows is kept, even timed inside the anchor.
     */
    private fun walkLeft(
        words: List<Word>,
        normalised: List<String>,
        content: List<Int>,
        cue: Cue,
        anchor: List<String>,
        shift: Double,
    ): Int {
        fun hit(
            next: Int,
            at: Int,
        ) = (next until minOf(next + ANCHOR_LOOKAHEAD, anchor.size)).firstOrNull { similar(anchor[it], normalised[at]) }

        fun inAnchor(at: Int) = words[at].start + shift < cue.end + OVERLAP_SLACK_SECONDS
        var next = 0
        var last = -1
        var i = 0
        while (i < content.size && inAnchor(content[i])) {
            var at = content[i]
            var found = hit(next, at)
            // One word the anchor does not have, a filler most often, when the next one carries on with it.
            if (found == null && i + 1 < content.size && inAnchor(content[i + 1])) {
                hit(next, content[i + 1])?.let {
                    found = it
                    at = content[++i]
                }
            }
            if (found == null) {
                // The anchor's closing word, rendered another way: it ends a sentence if the anchor does, or ends inside it.
                val closes =
                    if (cue.text.trimEnd().lastOrNull() in SENTENCE_ENDS) {
                        endsSentence(words, at)
                    } else {
                        words[at].end + shift <= cue.end + OVERLAP_SLACK_SECONDS
                    }
                if (last >= 0 && next == anchor.size - 1 && words[at].start + shift < cue.end && closes) last = at
                break
            }
            next = found!! + 1
            last = at
            i++
        }
        if (last >= 0) return last + 1
        return words.indexOfFirst { it.start + shift >= cue.end - 0.25 }.let { if (it < 0) words.size else it }
    }

    /** [walkLeft] from the other end, past any words whisper ran on with beyond the window. */
    private fun walkRight(
        words: List<Word>,
        normalised: List<String>,
        content: List<Int>,
        cue: Cue,
        anchor: List<String>,
        shift: Double,
        from: Int,
    ): Int {
        fun hit(
            next: Int,
            at: Int,
        ) = (next downTo maxOf(0, next - ANCHOR_LOOKAHEAD + 1)).firstOrNull { it >= 0 && similar(anchor[it], normalised[at]) }

        val order = content.filter { it >= from }.reversed()

        fun inAnchor(at: Int) = words[at].end + shift > cue.start - OVERLAP_SLACK_SECONDS
        var next = anchor.size - 1
        var first: Int? = null
        var i = 0
        while (i < order.size && inAnchor(order[i])) {
            var at = order[i]
            var found = hit(next, at)
            if (found == null && first != null && i + 1 < order.size && inAnchor(order[i + 1])) {
                hit(next, order[i + 1])?.let {
                    found = it
                    at = order[++i]
                }
            }
            if (found == null) {
                if (first == null && words[at].start + shift >= cue.end - OVERLAP_SLACK_SECONDS) {
                    i++
                    continue
                }
                val opens =
                    if (cue.text.trimStart().firstOrNull()?.isUpperCase() == true) {
                        at == 0 || endsSentence(words, at - 1)
                    } else {
                        words[at].start + shift >= cue.start - OVERLAP_SLACK_SECONDS
                    }
                if (first != null && next == 0 && words[at].end + shift > cue.start && opens) first = at
                break
            }
            next = found!! - 1
            first = at
            if (next < 0) break
            i++
        }
        return first ?: words.indexOfFirst { it.start + shift >= cue.start - 0.1 }.let { if (it < 0) words.size else maxOf(it, from) }
    }

    /** Whether [at] closes a sentence, by its own punctuation or a mark whisper sent as a word of its own. */
    private fun endsSentence(
        words: List<Word>,
        at: Int,
    ): Boolean = words[at].text.trimEnd().lastOrNull() in SENTENCE_ENDS || words.getOrNull(at + 1)?.text?.trim() in SENTENCE_END_WORDS

    private val SENTENCE_ENDS = setOf('.', '!', '?')
    private val SENTENCE_END_WORDS = setOf(".", "!", "?")

    /** Joins cue [at] + 1 onto cue [at], renumbering the words after it. */
    private fun merge(
        cues: MutableList<Cue>,
        words: MutableList<Word>,
        at: Int,
    ) {
        val (a, b) = cues[at] to cues[at + 1]
        cues[at] = Cue(minOf(a.start, b.start), maxOf(a.end, b.end), a.text + b.text)
        cues.removeAt(at + 1)
        words.replaceAll { if (it.segment > at) it.copy(segment = it.segment - 1) else it }
    }

    /**
     * The window decode's clock mapped onto the base's, from where each found
     * its anchor words: the two decodes can disagree by seconds. Stretched between
     * two anchors, shifted by one, left alone with none.
     */
    private fun clock(
        newLeft: Double?,
        oldLeft: Double?,
        newRight: Double?,
        oldRight: Double?,
    ): (Double) -> Double {
        if (newLeft != null && oldLeft != null && newRight != null && oldRight != null && newRight > newLeft && oldRight > oldLeft) {
            val scale = (oldRight - oldLeft) / (newRight - newLeft)
            if (scale in MIN_CLOCK_SCALE..MAX_CLOCK_SCALE) return { t -> oldLeft + (t - newLeft) * scale }
        }
        if (newLeft != null && oldLeft != null) return { t -> t + (oldLeft - newLeft) }
        if (newRight != null && oldRight != null) return { t -> t + (oldRight - newRight) }
        return { t -> t }
    }

    private const val MIN_CLOCK_SCALE = 0.5
    private const val MAX_CLOCK_SCALE = 2.0

    /** Speech under this, across a cue, and the cue is over non-speech. */
    private const val MIN_SPEECH_SECONDS = 0.5

    private val MUSIC = Regex("""^[\s♪♫¦]+$|^[\[(]\s*music\s*[\])]$""", RegexOption.IGNORE_CASE)

    private fun heard(
        start: Double,
        end: Double,
        speech: List<TimeWindow>,
    ): Double = speech.sumOf { maxOf(0.0, minOf(it.end, end) - maxOf(it.start, start)) }

    /** Speech VAD heard that a repair may leave uncovered: more is speech the decode dropped. */
    const val MAX_LOST_SPEECH_SECONDS = 2.0

    /** Speech this close to where a word starts counts as covered by it. */
    private const val COVER_SLACK_SECONDS = 1.5

    /** Closer than that: a decode that skips a sentence smears its next words across the gap, a word every second or two. */
    const val CLOSE_COVER_SECONDS = 0.5

    /**
     * Seconds of speech between the anchors that no cue of [replacement] covers.
     * whisper can skip a whole 30 s window of real speech and carry on after it,
     * which anchoring cannot see: the words either side still match.
     */
    fun lostSpeech(
        base: WhisperTranscription,
        replacement: Replacement,
        speech: List<TimeWindow>,
        slack: Double = COVER_SLACK_SECONDS,
    ): Double {
        val from = replacement.range.first.let { if (it > 0) base.cues[it - 1].end else base.cues[it].start }
        val to = replacement.range.last.let { if (it + 1 < base.cues.size) base.cues[it + 1].start else base.cues[it].end }
        // By word, not by cue: a stretched cue spans the seconds it skipped.
        val covered = replacement.words.map { (it.start - slack)..(it.start + slack) }
        var lost = 0.0
        for (span in speech) {
            val start = maxOf(span.start, from)
            val end = minOf(span.end, to)
            if (end <= start) continue
            // Sampled at 0.1 s: spans are a few seconds long.
            var t = start
            while (t < end) {
                val step = minOf(0.1, end - t)
                if (covered.none { (t + step / 2) in it }) lost += step
                t += step
            }
        }
        return lost
    }

    /** How far a cue's words may run into non-speech at either end before they are moved onto the speech. */
    private const val MAX_WORDS_OUTSIDE_SPEECH_SECONDS = 1.0

    /**
     * After music, whisper starts a segment at its 30 s window's edge and smears
     * the words across the music: a cue whose speech begins at 49.4 s had its
     * first word at 29.8 s. Its words are mapped, in order, onto the stretch VAD
     * hears as speech, and the cue's edges moved with them.
     */
    private fun fitToSpeech(
        cue: Cue,
        words: List<Word>,
        speech: List<TimeWindow>,
    ): Pair<Cue, List<Word>> {
        if (words.isEmpty()) return cue to words
        val heard = speech.filter { it.end > cue.start && it.start < cue.end }
        // A short sound alone, a jingle's hit, says nothing about where the words are.
        val inside = heard.filterNot { isolatedBlip(it, heard) }.ifEmpty { heard }
        if (inside.isEmpty()) return cue to words
        val onset = maxOf(cue.start, inside.first().start)
        val offset = minOf(cue.end, inside.last().end)
        val first = words.first().start
        val last = words.last().end
        val early = onset - first > MAX_WORDS_OUTSIDE_SPEECH_SECONDS
        val late = last - offset > MAX_WORDS_OUTSIDE_SPEECH_SECONDS
        if (!early && !late || last <= first) return cue to words
        val to0 = if (early) onset else first
        // Words that all end before the speech starts have only the speech to go to.
        val to1 = if (late || last <= to0) offset else last
        if (to1 <= to0) return cue to words
        val map = { t: Double -> to0 + (t - first) * (to1 - to0) / (last - first) }
        val moved = words.map { it.copy(start = map(it.start), end = map(it.end)) }
        return Cue(if (early) onset else cue.start, if (late) offset else cue.end, cue.text) to moved
    }

    /** A cue shorter than [MIN_SPEECH_SECONDS] is judged over that much time around it: it cannot hold more speech than its length. */
    private fun overSpeech(
        cue: Cue,
        speech: List<TimeWindow>,
    ): Boolean {
        val pad = maxOf(0.0, MIN_SPEECH_SECONDS - (cue.end - cue.start)) / 2
        val span = cue.end - cue.start + 2 * pad
        return heard(cue.start - pad, cue.end + pad, speech) >= minOf(MIN_SPEECH_SECONDS, span / 2)
    }

    /** The base's own words in [range]'s good cues, standing in for VAD: speech a repair must not drop. */
    fun spokenIn(
        base: WhisperTranscription,
        range: IntRange,
        defects: Set<Int>,
    ): List<TimeWindow> = base.words.filter { it.segment in range && it.segment !in defects }.map { TimeWindow(it.start, it.end) }

    /**
     * Whether VAD heard speech under most of the base's good cues. It hears nothing
     * when it has failed quietly, and a transcript is mostly speech.
     */
    fun plausible(
        base: WhisperTranscription,
        defects: Set<Int>,
        speech: List<TimeWindow>,
    ): Boolean {
        val spoken = base.words.map { it.segment }.toSet()
        val cues = base.cues.indices.filter { it !in defects && it in spoken }.map { base.cues[it] }
        if (cues.size < MIN_CUES_TO_JUDGE_VAD) return true
        return cues.count { heard(it.start, it.end, speech) < MIN_SPEECH_SECONDS } * 2 <= cues.size
    }

    private const val MIN_CUES_TO_JUDGE_VAD = 5

    private fun isolatedBlip(
        span: TimeWindow,
        spans: List<TimeWindow>,
    ): Boolean =
        span.end - span.start < MAX_BLIP_SECONDS &&
            spans.none { it != span && it.end > span.start - BLIP_ISOLATION_SECONDS && it.start < span.end + BLIP_ISOLATION_SECONDS }

    private const val MAX_BLIP_SECONDS = 1.0
    private const val BLIP_ISOLATION_SECONDS = 3.0

    /** Whether the base cues in [range] lie over non-speech, which is what makes removing them a repair. */
    fun silent(
        base: WhisperTranscription,
        range: IntRange,
        speech: List<TimeWindow>,
    ): Boolean = heard(base.cues[range.first].start, base.cues[range.last].end, speech) < MIN_SPEECH_SECONDS

    /**
     * Drops the cues [speech] says fall over non-speech: whatever whisper wrote there
     * is invention. A run of music markers is kept as one, since music is what is there.
     */
    fun dropNonSpeech(
        replacement: Replacement,
        speech: List<TimeWindow>,
    ): Replacement {
        val bySegment = replacement.words.groupBy { it.segment }
        val cues = mutableListOf<Cue>()
        val words = mutableListOf<Word>()
        for ((index, cue) in replacement.cues.withIndex()) {
            if (overSpeech(cue, speech)) {
                val (fitted, fittedWords) = fitToSpeech(cue, bySegment[index].orEmpty(), speech)
                words += fittedWords.map { it.copy(segment = cues.size) }
                cues += fitted
            } else if (MUSIC.matches(cue.text.trim())) {
                val previous = cues.lastOrNull()
                if (previous != null && previous.text.trim() == "♪") {
                    cues[cues.size - 1] = previous.copy(end = cue.end)
                } else {
                    cues += Cue(cue.start, cue.end, " ♪")
                }
            }
        }
        return replacement.copy(cues = cues, words = words)
    }

    /** Anchor words a garbled rendering may skip past and still be counted as the anchor. */
    private const val ANCHOR_LOOKAHEAD = 3

    /** The same word as whisper might render it twice: equal, one a prefix of the other, or a letter or two off. */
    private fun similar(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a))) return true
        val allowed =
            if (maxOf(a.length, b.length) >= 7) {
                2
            } else if (maxOf(a.length, b.length) >= 4) {
                1
            } else {
                0
            }
        return allowed > 0 && editDistance(a, b) <= allowed
    }

    private fun editDistance(
        a: String,
        b: String,
    ): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun normalise(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }

    private fun matches(
        haystack: List<String>,
        needle: List<String>,
    ): List<Int> {
        if (needle.isEmpty()) return emptyList()
        return (0..haystack.size - needle.size).filter { at -> needle.indices.all { haystack[at + it] == needle[it] } }
    }

    /** A window long enough to judge, with next to no punctuation: what a decode without the prompt can produce. */
    fun unpunctuated(replacement: Replacement): Boolean {
        val text = replacement.cues.joinToString(" ") { it.text }
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val marks = text.count { it in ".,!?;:" }
        return words >= MIN_WORDS_TO_JUDGE_PUNCTUATION && marks.toDouble() / words < TranscriptQuality.MIN_PUNCTUATION_PER_WORD
    }

    private const val MIN_WORDS_TO_JUDGE_PUNCTUATION = 30

    /** Every replacement's range is in [base]'s cue numbering, and they must not overlap. */
    fun splice(
        base: WhisperTranscription,
        replacements: List<Replacement>,
    ): WhisperTranscription {
        val bySegment = base.words.groupBy { it.segment }
        val cues = mutableListOf<Cue>()
        val words = mutableListOf<Word>()
        var next = 0

        fun keep(until: Int) {
            for (i in next until until) {
                words += bySegment[i].orEmpty().map { it.copy(segment = cues.size) }
                cues += base.cues[i]
            }
        }
        for (replacement in replacements.sortedBy { it.range.first }) {
            keep(replacement.range.first)
            val offset = cues.size
            cues += replacement.cues
            words += replacement.words.map { it.copy(segment = it.segment + offset) }
            next = replacement.range.last + 1
        }
        keep(base.cues.size)
        return WhisperTranscription.of(cues, words)
    }

    /**
     * Defects left inside [replacement] once spliced, judged in context. A prompt sentence the base's good cues
     * there did not say counts at any length: fitted onto speech it is shorter than a leak, and still the prompt.
     */
    fun defectsAfter(
        base: WhisperTranscription,
        replacement: Replacement,
        prompt: Prompt = Prompt.NONE,
        defects: Set<Int> = emptySet(),
    ): Int {
        val spliced = splice(base, listOf(replacement))
        val first = replacement.range.first
        val inside = first until first + replacement.cues.size
        val said = replacement.range.filter { it !in defects }.map { Prompt.wordsOf(base.cues[it].text) }.toSet()
        val leaks = inside.filter { prompt.voices(spliced.cues[it].text) && Prompt.wordsOf(spliced.cues[it].text) !in said }
        return (defectCues(spliced.cues, prompt) + leaks).count { it in inside }
    }
}
