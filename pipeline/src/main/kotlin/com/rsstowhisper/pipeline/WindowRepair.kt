package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word

/** Re-decoding only the stretches of an episode whose cues are loops or stretch-copies. */
internal object WindowRepair {
    /** Good cues kept either side of a defect, so the splice lands on cue boundaries whisper chose. */
    private const val MARGIN_CUES = 1

    /**
     * [range] is the base cues this replaces; [anchors] says how each edge was
     * found ("text", "time", or "none" at the episode's edge), and the gaps are
     * the seconds between an anchor and the first new word, where speech could
     * have been lost.
     */
    data class Replacement(
        val range: IntRange,
        val cues: List<Cue>,
        val words: List<Word>,
        val anchorLeft: String = "none",
        val anchorRight: String = "none",
        val gapLeft: Double? = null,
        val gapRight: Double? = null,
    )

    /** A cue that is nothing but one of the prompt's sentences, held this long, is whisper voicing the prompt over non-speech. */
    private const val MIN_PROMPT_LEAK_SECONDS = 10.0

    fun defectCues(
        cues: List<Cue>,
        promptSentences: Set<String> = emptySet(),
    ): Set<Int> {
        val defects = TranscriptQuality.stretchCopyCues(cues).toMutableSet()
        var i = 0
        while (i < cues.size) {
            val key = cues[i].text.trim().lowercase()
            var j = i
            while (key.isNotEmpty() && j + 1 < cues.size && cues[j + 1].text.trim().lowercase() == key) j++
            if (j - i + 1 >= TranscriptQuality.MAX_REPEATED_CUE_RUN) defects.addAll(i..j)
            i = j + 1
        }
        val prompt = cues.map { it.text.trim().lowercase() in promptSentences }
        val leaks = cues.indices.filter { prompt[it] && cues[it].end - cues[it].start >= MIN_PROMPT_LEAK_SECONDS }.toMutableSet()
        // A short copy beside a leak is the same leak, and must not be kept as an anchor.
        var grew = true
        while (grew) {
            grew = leaks.addAll(leaks.flatMap { listOf(it - 1, it + 1) }.filter { it in cues.indices && prompt[it] })
        }
        defects += leaks
        return defects
    }

    fun promptSentences(prompt: String?): Set<String> =
        prompt.orEmpty().split(Regex("(?<=[.!?])\\s+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

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
     * The new decode of [range], cut at the good cues either side of the defects,
     * which are kept exactly as they were. Each edge is found by the anchor cue's
     * own words in the new decode near their old time, and only by time when the
     * words are not there: whisper starts a window cold, so its first words are
     * the least trustworthy, and the anchor cue already holds them.
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

        var from = 0
        var anchorLeft = "none"
        var newLeft: Double? = null
        var oldLeft: Double? = null
        if (left != null) {
            val cue = base.cues[left]
            val anchorWords = baseWords[left].orEmpty().filter { normalise(it.text).isNotEmpty() }.takeLast(ANCHOR_WORDS)
            val tail = anchorWords.map { normalise(it.text) }
            val match =
                matches(contentText, tail).lastOrNull { at ->
                    words[content[at + tail.size - 1]].start in (cue.start - ANCHOR_SLACK_SECONDS)..(cue.end + ANCHOR_SLACK_SECONDS)
                }
            if (match != null) {
                from = content[match + tail.size - 1] + 1
                newLeft = words[from - 1].end
                oldLeft = anchorWords.last().end
                anchorLeft = "text"
            } else {
                // The decode renders the anchor its own way, and can time the next
                // sentence's opening words inside it. Its words are walked over the
                // anchor's in order and dropped while they resemble them; the first
                // that resembles nothing is where the new content starts.
                val anchor = baseWords[left].orEmpty().map { normalise(it.text) }.filter { it.isNotEmpty() }
                var next = 0
                var last = -1
                for (at in content) {
                    if (words[at].start >= cue.end + OVERLAP_SLACK_SECONDS) break
                    val hit =
                        (next until minOf(next + ANCHOR_LOOKAHEAD, anchor.size))
                            .firstOrNull { similar(anchor[it], normalised[at]) } ?: break
                    next = hit + 1
                    last = at
                }
                from =
                    if (last >= 0) {
                        last + 1
                    } else {
                        words.indexOfFirst { it.start >= cue.end - 0.25 }.let { if (it < 0) words.size else it }
                    }
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
            val cue = base.cues[right]
            val anchorWords = baseWords[right].orEmpty().filter { normalise(it.text).isNotEmpty() }.take(ANCHOR_WORDS)
            val head = anchorWords.map { normalise(it.text) }
            val match =
                matches(contentText, head).firstOrNull { at ->
                    content[at] >= from && words[content[at]].start in (cue.start - ANCHOR_SLACK_SECONDS)..(cue.end + ANCHOR_SLACK_SECONDS)
                }
            if (match != null) {
                until = content[match]
                newRight = words[until].start
                oldRight = anchorWords.first().start
                anchorRight = "text"
            } else {
                // The same from the other end, past any words whisper ran on with beyond the window.
                val anchor = baseWords[right].orEmpty().map { normalise(it.text) }.filter { it.isNotEmpty() }
                var next = anchor.size - 1
                var first: Int? = null
                for (at in content.reversed()) {
                    if (at < from || words[at].end <= cue.start - OVERLAP_SLACK_SECONDS) break
                    val hit =
                        (next downTo maxOf(0, next - ANCHOR_LOOKAHEAD + 1))
                            .firstOrNull { it >= 0 && similar(anchor[it], normalised[at]) }
                    if (hit == null) {
                        if (first == null && words[at].start >= cue.end - OVERLAP_SLACK_SECONDS) continue
                        break
                    }
                    next = hit - 1
                    first = at
                    if (next < 0) break
                }
                until = first ?: words.indexOfFirst { it.start >= cue.start - 0.1 }.let { if (it < 0) words.size else maxOf(it, from) }
                anchorRight = "time"
            }
        }

        val clock = clock(newLeft, oldLeft, newRight, oldRight)
        val kept =
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
        val floor = left?.let { base.cues[it].end }
        val ceiling = right?.let { base.cues[it].start }
        val cues = mutableListOf<Cue>()
        val out = mutableListOf<Word>()
        for ((segment, segmentWords) in kept.groupBy { it.segment }.toSortedMap()) {
            val original = decoded.cues[segment]
            val whole = segmentWords.size == decoded.words.count { it.segment == segment }
            val text = if (whole) original.text else segmentWords.joinToString("") { it.text }
            var start = if (whole) clock(original.start) else segmentWords.first().start
            var end = if (whole) clock(original.end) else segmentWords.last().end
            if (floor != null) start = maxOf(start, floor)
            if (ceiling != null) end = minOf(end, ceiling)
            // Words kept from over an anchor's span belong after it, not inside it.
            out +=
                segmentWords.map {
                    val wordStart = it.start.coerceIn(floor ?: it.start, ceiling ?: it.start)
                    it.copy(start = wordStart, end = it.end.coerceIn(wordStart, maxOf(wordStart, ceiling ?: it.end)), segment = cues.size)
                }
            cues += Cue(start, maxOf(start, end), text)
        }
        return Replacement(
            range = inner,
            cues = cues,
            words = out,
            anchorLeft = anchorLeft,
            anchorRight = anchorRight,
            gapLeft = if (floor != null && kept.isNotEmpty()) kept.first().start - floor else null,
            gapRight = if (ceiling != null && kept.isNotEmpty()) ceiling - kept.last().end else null,
        )
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

    /**
     * Seconds of speech between the anchors that no cue of [replacement] covers.
     * whisper can skip a whole 30 s window of real speech and carry on after it,
     * which anchoring cannot see: the words either side still match.
     */
    fun lostSpeech(
        base: WhisperTranscription,
        replacement: Replacement,
        speech: List<TimeWindow>,
    ): Double {
        val from = replacement.range.first.let { if (it > 0) base.cues[it - 1].end else base.cues[it].start }
        val to = replacement.range.last.let { if (it + 1 < base.cues.size) base.cues[it + 1].start else base.cues[it].end }
        // By word, not by cue: a stretched cue spans the seconds it skipped.
        val covered = replacement.words.map { (it.start - COVER_SLACK_SECONDS)..(it.start + COVER_SLACK_SECONDS) }
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
        val inside = speech.filter { it.end > cue.start && it.start < cue.end }
        if (inside.isEmpty()) return cue to words
        val onset = maxOf(cue.start, inside.first().start)
        val offset = minOf(cue.end, inside.last().end)
        val first = words.first().start
        val last = words.last().end
        val early = onset - first > MAX_WORDS_OUTSIDE_SPEECH_SECONDS
        val late = last - offset > MAX_WORDS_OUTSIDE_SPEECH_SECONDS
        if (!early && !late || last <= first) return cue to words
        val to0 = if (early) onset else first
        val to1 = if (late) offset else last
        if (to1 <= to0) return cue to words
        val map = { t: Double -> to0 + (t - first) * (to1 - to0) / (last - first) }
        val moved = words.map { it.copy(start = map(it.start), end = map(it.end)) }
        return Cue(if (early) onset else cue.start, if (late) offset else cue.end, cue.text) to moved
    }

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
            if (heard(cue.start, cue.end, speech) >= MIN_SPEECH_SECONDS) {
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

    /** Defects left inside [replacement] once spliced, judged in context: a stretch-copy needs the cues before it. */
    fun defectsAfter(
        base: WhisperTranscription,
        replacement: Replacement,
        promptSentences: Set<String> = emptySet(),
    ): Int {
        val spliced = splice(base, listOf(replacement))
        val first = replacement.range.first
        val inside = first until first + replacement.cues.size
        return defectCues(spliced.cues, promptSentences).count { it in inside }
    }
}
