package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import kotlin.math.abs

/** An episode's defects (loops, copies, echoes and prompt leaks), and the chunks a decode cut on VAD's speech is made of. */
internal object WindowRepair {
    /** Cues a defect's window takes either side: the one against it is the cue most often damaged. */
    private const val MARGIN_CUES = 2

    data class Replacement(
        val cues: List<Cue>,
        val words: List<Word>,
    )

    /** A cue that is nothing but one of the prompt's sentences, held this long, is whisper voicing the prompt over non-speech. */
    private const val MIN_PROMPT_LEAK_SECONDS = 10.0

    fun defectCues(
        cues: List<Cue>,
        prompt: Prompt = Prompt.NONE,
        voices: Voices? = null,
    ): Set<Int> = defectCues(cues, defectsByKind(cues, prompt, voices))

    /** The cues [byKind] names, as [defectsByKind] found them in [cues]. */
    fun defectCues(
        cues: List<Cue>,
        byKind: Map<String, Set<Int>>,
    ): Set<Int> {
        val defects = byKind.values.flatten().toMutableSet()
        // A cue of many words in no time beside a defect is part of it.
        val crammed = cues.indices.filter { crammed(cues[it]) }.toSet()
        var grew = true
        while (grew) grew = defects.addAll(defects.flatMap { listOf(it - 1, it + 1) }.filter { it in crammed })
        return defects
    }

    /** Each kind of defect's cues. With [voices], a repeat traded between voices is not a loop or an echo. */
    fun defectsByKind(
        cues: List<Cue>,
        prompt: Prompt = Prompt.NONE,
        voices: Voices? = null,
    ): Map<String, Set<Int>> =
        mapOf(
            "loop" to loops(cues, voices),
            "stretch" to TranscriptQuality.stretchCopyCues(cues).toSet(),
            "echo" to echoes(cues, voices).toSet(),
            "copy" to longCopies(cues).toSet(),
            "leak" to leaks(cues, prompt),
            "stock" to stock(cues),
        )

    /** How many cues of each kind of defect. */
    fun defectKinds(
        cues: List<Cue>,
        prompt: Prompt = Prompt.NONE,
    ): Map<String, Int> = defectsByKind(cues, prompt).mapValues { it.value.size }

    /** Runs of identical cues, with the cue either side that holds the loop's first or last lap. */
    private fun loops(
        cues: List<Cue>,
        voices: Voices?,
    ): Set<Int> {
        val loops = mutableSetOf<Int>()
        var i = 0
        while (i < cues.size) {
            val key = cues[i].text.trim().lowercase()
            var j = i
            while (key.isNotEmpty() && j + 1 < cues.size && cues[j + 1].text.trim().lowercase() == key) j++
            if (j - i + 1 >= TranscriptQuality.MAX_REPEATED_CUE_RUN && voices?.traded(cues, (i..j).map { it..it }) != true) {
                loops.addAll(i..j)
                // The loop's first lap usually arrives inside the cue before it.
                if (i > 0 && cues[i - 1].text.lowercase().contains(key)) loops += i - 1
                if (j + 1 < cues.size && cues[j + 1].text.lowercase().contains(key)) loops += j + 1
            }
            i = j + 1
        }
        loops += cycles(cues, voices)
        loops += cues.indices.filter { loopsWithin(cues[it]) }
        return loops
    }

    /** A few cues repeated in turn: "We're going to talk about | the | whiskey | sour." eight times over. */
    private fun cycles(
        cues: List<Cue>,
        voices: Voices?,
    ): Set<Int> {
        val keys = cues.map { Prompt.wordsOf(it.text) }
        val cycles = mutableSetOf<Int>()
        for (period in 2..MAX_CYCLE_CUES) {
            var i = period
            while (i < cues.size) {
                var j = i
                while (j < cues.size && keys[j].isNotEmpty() && keys[j] == keys[j - period]) j++
                val lap = keys.subList(i - period, i)
                if (j - i >= (TranscriptQuality.MAX_REPEATED_CUE_RUN - 1) * period && lap.toSet().size == period &&
                    lap.sumOf { it.size } >= MIN_CYCLE_WORDS
                ) {
                    val laps = (i - period until j step period).filter { it + period <= j }.map { it until it + period }
                    if (voices?.traded(cues, laps) != true) cycles.addAll(i - period until j)
                    i = j
                } else {
                    i++
                }
            }
        }
        return cycles
    }

    /** One cue repeating a phrase faster than anyone speaks: "a paid-for woman. You're visited by a paid-for woman." in 0.9 s. */
    private fun loopsWithin(cue: Cue): Boolean {
        val words = Prompt.wordsOf(cue.text)
        if (words.size / maxOf(cue.end - cue.start, MIN_LOOP_SECONDS) < MIN_LOOPING_WORDS_PER_SECOND) return false
        for (phrase in MIN_LOOP_PHRASE_WORDS..MAX_LOOP_PHRASE_WORDS) {
            var run = 0
            for (i in phrase until words.size) {
                run = if (words[i] == words[i - phrase]) run + 1 else 0
                if (run + phrase >= phrase * MIN_LOOP_LAPS && !repeats(words.subList(i + 1 - phrase, i + 1))) return true
            }
        }
        return false
    }

    /** "ha ha ha ha" is a one-word laugh, not a four-word phrase. */
    private fun repeats(phrase: List<String>): Boolean =
        (1 until phrase.size).any { d -> (d until phrase.size).all { phrase[it] == phrase[it - d] } }

    private const val MAX_CYCLE_CUES = 4

    /** "Yeah. | No." four times is a conversation; a cycle this long is not. */
    private const val MIN_CYCLE_WORDS = 4

    /** Shorter phrases, or slower, are mostly real: "no, no, no", "back and forth, back and forth". */
    private const val MIN_LOOP_PHRASE_WORDS = 4
    private const val MAX_LOOP_PHRASE_WORDS = 12
    private const val MIN_LOOP_LAPS = 2.5
    private const val MIN_LOOPING_WORDS_PER_SECOND = 10.0
    private const val MIN_LOOP_SECONDS = 0.05

    private fun leaks(
        cues: List<Cue>,
        prompt: Prompt,
    ): Set<Int> {
        val voiced = cues.map { prompt.voices(it.text) }
        val leaks = cues.indices.filter { voiced[it] && cues[it].end - cues[it].start >= MIN_PROMPT_LEAK_SECONDS }.toMutableSet()
        // A short copy beside a leak is the same leak.
        var grew = true
        while (grew) {
            grew = leaks.addAll(leaks.flatMap { listOf(it - 1, it + 1) }.filter { it in cues.indices && voiced[it] })
        }
        return leaks
    }

    /** A subtitle credit or sign-off held as long as a leak: whisper's, over music or silence. */
    private fun stock(cues: List<Cue>): Set<Int> =
        cues.indices.filter { i -> cues[i].end - cues[i].start >= MIN_PROMPT_LEAK_SECONDS && STOCK.any { it.voices(cues[i].text) } }.toSet()

    /** More words than anyone says in the time: [MIN_CRAMMED_WORDS] or more at over [MAX_WORDS_PER_SECOND]. */
    private fun crammed(cue: Cue): Boolean {
        val words = Prompt.wordsOf(cue.text).size
        return words >= MIN_CRAMMED_WORDS && cue.end - cue.start < words / MAX_WORDS_PER_SECOND
    }

    private const val MAX_WORDS_PER_SECOND = 20.0

    /** Only ever judged beside a defect or at a window's edge: "but also how peer" in 0.14 s. */
    private const val MIN_CRAMMED_WORDS = 3

    /** What whisper writes over music and silence from its training subtitles, seen held 10 s or more in this corpus. */
    private val STOCK by lazy {
        listOf(
            "I'll see you next time.",
            "Thanks for watching.",
            "Thank you for watching.",
            "Transcription by CastingWords",
            "Transcription by ESO. Translation by",
            "Transcribed by ESO, translated by",
            "Transcript Emily Beynon",
            "BF-WATCH TV 2021",
        ).map { Prompt(it) }
    }

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
    internal fun echoes(
        cues: List<Cue>,
        voices: Voices? = null,
    ): Set<Int> {
        val keys =
            cues.map { cue ->
                cue.text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.split(WHITESPACE).filter { it.isNotEmpty() }
            }
        val echoes =
            cues.indices.filter { i ->
                keys[i].size >= MIN_WORDS_FOR_ECHO &&
                    (maxOf(0, i - ECHO_LOOKBACK_CUES) until i).any {
                        keys[it] == keys[i] && voices?.traded(cues, listOf(it..it, i..i)) != true
                    }
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

    /** An episode's speaker turns, with the decode's words to say how sure whisper was of each cue. */
    class Voices(
        private val turns: SpeakerTurns,
        words: List<Word>,
    ) {
        private val probabilities = words.filter { it.text.isNotBlank() }.groupBy({ it.segment }, { it.probability })

        /** A line traded between voices, every repeat of it one whisper was sure of: a copy pasted over other speech is not. */
        fun traded(
            cues: List<Cue>,
            laps: List<IntRange>,
        ): Boolean = laps.drop(1).all { sure(it) } && turns.traded(laps.map { window(cues, it) })

        private fun sure(lap: IntRange): Boolean {
            if (lap.any { it !in probabilities }) return false
            val said = lap.flatMap { probabilities.getValue(it) }
            return said.average() >= MIN_REPEAT_PROBABILITY
        }
    }

    /** Whisper's copy of a line over someone else's speech starts unsure of itself; a real repeat does not. */
    private const val MIN_REPEAT_PROBABILITY = 0.9

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

        // A stack of crammed cues at an edge is whisper's copy of the window's text, so the window takes it in.
        fun edge(at: Int) = at in cues.indices && crammed(cues[at])

        // So is a cue the next one opens with again, or the one before ends with: whisper repeating itself across a break.
        fun echoed(
            at: Int,
            by: Int,
        ): Boolean {
            val own = Prompt.wordsOf(cues[at].text)
            val theirs = Prompt.wordsOf(cues[by].text)
            return own.size >= 2 && theirs.size > own.size && (if (by > at) theirs.take(own.size) else theirs.takeLast(own.size)) == own
        }
        for (i in defects.sorted()) {
            var first = maxOf(0, i - MARGIN_CUES)
            var last = minOf(cues.size - 1, i + MARGIN_CUES)
            val floor = maxOf(0, first - MAX_EDGE_GROWTH)
            val ceiling = minOf(cues.size - 1, last + MAX_EDGE_GROWTH)
            while (first > floor && (edge(first) || edge(first - 1) || echoed(first, first - 1))) first--
            while (last < ceiling && (edge(last) || edge(last + 1) || echoed(last, last + 1))) last++
            val previous = ranges.lastOrNull()
            if (previous != null && first <= previous.last + 1) {
                ranges[ranges.size - 1] = previous.first..maxOf(previous.last, last)
            } else {
                ranges += first..last
            }
        }
        return ranges
    }

    /** How far a window grows over crammed or echoed cues at either edge. */
    private const val MAX_EDGE_GROWTH = 8

    fun window(
        cues: List<Cue>,
        range: IntRange,
    ): TimeWindow = TimeWindow(cues[range.first].start, cues[range.last].end)

    /** Speech under this, across a cue, and the cue is over non-speech. */
    private const val MIN_SPEECH_SECONDS = 0.5

    private val MUSIC = Regex("""^[\s♪♫¦]+$|^[\[(]\s*music\s*[\])]$""", RegexOption.IGNORE_CASE)

    private fun heard(
        start: Double,
        end: Double,
        speech: List<TimeWindow>,
    ): Double = speech.sumOf { maxOf(0.0, minOf(it.end, end) - maxOf(it.start, start)) }

    /** Closer than that: a decode that skips a sentence smears its next words across the gap, a word every second or two. */
    private const val CLOSE_COVER_SECONDS = 0.5

    fun lastSpokenEnd(decode: WhisperTranscription): Double? = decode.words.lastOrNull { normalise(it.text).isNotEmpty() }?.end

    /** Where a chunk's decode stopped with speech VAD still hears after it: whisper skipping inside a clip. Null if none. */
    fun unheardTail(
        decode: WhisperTranscription,
        speech: List<TimeWindow>,
        window: TimeWindow,
    ): TimeWindow? {
        val last = lastSpokenEnd(decode) ?: window.start
        val rest =
            speech.mapNotNull {
                TimeWindow(maxOf(it.start, last + CLOSE_COVER_SECONDS), minOf(it.end, window.end)).takeIf {
                        w ->
                    w.end > w.start
                }
            }
        if (rest.sumOf { it.end - it.start } < MIN_HEARD_SECONDS) return null
        val start = maxOf(last, rest.first().start - CHUNK_PAD_SECONDS)
        return TimeWindow(start, window.end).takeIf { start > window.start + CLOSE_COVER_SECONDS }
    }

    /** Less unheard speech than this after a chunk's last word is not worth a decode of its own. */
    private const val MIN_HEARD_SECONDS = 1.0

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
        // From its first word: a cue that begins where the one before ended can open on that one's speech.
        val heard = speech.filter { it.end > words.first().start && it.start < cue.end }
        // A short sound alone, a jingle's hit, says nothing about where the words are.
        val inside = heard.filterNot { isolatedBlip(it, heard) }.ifEmpty { heard }
        if (inside.isEmpty()) return cue to words
        overSilence(cue, words, inside)?.let { return it }
        val onset = maxOf(cue.start, inside.first().start)
        val offset = minOf(cue.end, inside.last().end)
        val first = words.first().start
        val last = words.last().end
        val early = onset - first > MAX_WORDS_OUTSIDE_SPEECH_SECONDS
        val late = last - offset > MAX_WORDS_OUTSIDE_SPEECH_SECONDS
        if (!early && !late || last <= first) return cue to words
        if (early && !late) aheadOfSpeech(cue, words, onset)?.let { return it }
        val to0 = if (early) onset else first
        // Words that all end before the speech starts have only the speech to go to.
        val to1 = if (late || last <= to0) offset else last
        if (to1 <= to0) return cue to words
        val map = { t: Double -> to0 + (t - first) * (to1 - to0) / (last - first) }
        val moved = words.map { it.copy(start = map(it.start), end = map(it.end)) }
        val fitted = Cue(if (early) onset else cue.start, if (late) offset else cue.end, cue.text) to moved
        if (!crams(moved)) return fitted
        // Squeezing them on would cram the words already there: what whisper smeared over the music either side goes.
        var from = if (early) words.indexOfFirst { it.end > onset - MAX_WORDS_OUTSIDE_SPEECH_SECONDS } else 0
        var to = if (late) words.indexOfLast { it.start < offset + MAX_WORDS_OUTSIDE_SPEECH_SECONDS } else words.lastIndex
        if (from < 0 || to < from) return fitted
        // Whole words only: a token without its leading space continues the word before it.
        while (from > 0 && !words[from].text.startsWith(" ")) from--
        while (to < words.lastIndex && !words[to + 1].text.startsWith(" ")) to++
        if (to - from + 1 == words.size) return fitted
        val kept = words.subList(from, to + 1)
        return fitToSpeech(Cue(kept.first().start, kept.last().end, kept.joinToString("") { it.text }), kept, speech)
    }

    /**
     * Words timed inside a silence in the cue were smeared ahead of the speech after it, and all go onto that speech
     * if it can hold them at a speaking pace ("It's the size of a squash court" spread across a 4 s gap).
     */
    private fun overSilence(
        cue: Cue,
        words: List<Word>,
        heard: List<TimeWindow>,
    ): Pair<Cue, List<Word>>? {
        val gap =
            heard.zipWithNext().map { (a, b) -> TimeWindow(a.end, b.start) }
                .filter { it.end - it.start >= MIN_SILENCE_SECONDS && it.start > cue.start && it.end < cue.end }
                .firstOrNull { g -> words.count { it.start > g.start && it.start < g.end && normalise(it.text).isNotEmpty() } >= 2 }
                ?: return null
        // Words before the gap at a speaking pace are on their own speech, not smeared: they stay.
        val ahead = words.count { it.start < gap.start && it.text.startsWith(" ") && normalise(it.text).isNotEmpty() }
        if (ahead > (gap.start - words.first().start) * MAX_SMEARED_WORDS_PER_SECOND) return null
        val to = minOf(cue.end, heard.last().end)
        val spoken = words.count { it.text.startsWith(" ") && normalise(it.text).isNotEmpty() }
        if ((to - gap.end) * MAX_SPEAKING_WORDS_PER_SECOND < spoken) return null
        val first = words.first().start
        val last = words.last().end
        if (last <= first) return null
        val map = { t: Double -> gap.end + (t - first) * (to - gap.end) / (last - first) }
        return Cue(gap.end, to, cue.text) to words.map { it.copy(start = map(it.start), end = map(it.end)) }
    }

    private const val MIN_SILENCE_SECONDS = 1.5
    private const val MAX_SPEAKING_WORDS_PER_SECOND = 6.0
    private const val MAX_SMEARED_WORDS_PER_SECOND = 1.5

    /** Slower than this, words "on" the speech are smeared too, and the whole cue is fitted. */
    private const val MAX_SECONDS_PER_WORD = 1.0

    /**
     * Only the words timed before the speech move, into the time before the first word already on it at a speaking pace,
     * if they fit at one too: squeezing the whole cue would cram the words whisper timed right.
     */
    private fun aheadOfSpeech(
        cue: Cue,
        words: List<Word>,
        onset: Double,
    ): Pair<Cue, List<Word>>? {
        val on = words.indexOfFirst { it.start >= onset && it.text.startsWith(" ") && normalise(it.text).isNotEmpty() }
        if (on <= 0) return null
        val ahead = words.subList(0, on)
        val onSpeech = words.subList(on, words.size).count { it.text.startsWith(" ") && normalise(it.text).isNotEmpty() }
        if (words.last().end - words[on].start > onSpeech * MAX_SECONDS_PER_WORD) return null
        val room = words[on].start - onset
        val spoken = ahead.count { it.text.startsWith(" ") && normalise(it.text).isNotEmpty() }
        if (room * MAX_SPEAKING_WORDS_PER_SECOND < spoken) return null
        val first = ahead.first().start
        val last = ahead.last().end
        if (last <= first) return null
        val map = { t: Double -> onset + (t - first) * room / (last - first) }
        val moved = ahead.map { it.copy(start = map(it.start), end = map(it.end)) } + words.subList(on, words.size)
        return Cue(onset, cue.end, cue.text) to moved
    }

    /** Any [MIN_WORDS_FOR_RATE] spoken words in less time than anyone says them. */
    private fun crams(words: List<Word>): Boolean {
        val starts = words.filter { it.text.startsWith(" ") && normalise(it.text).isNotEmpty() }.map { it.start }
        return starts.windowed(MIN_WORDS_FOR_RATE).any { it.last() - it.first() < (MIN_WORDS_FOR_RATE - 1) / MAX_WORDS_PER_SECOND }
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

    /** Tokens grouped into the words they spell, punctuation left out: whisper splits "dexterous" as "de", "xter", "ous". */
    fun spoken(tokens: List<Word>): List<List<Int>> {
        val groups = mutableListOf<MutableList<Int>>()
        for ((i, token) in tokens.withIndex()) {
            if (normalise(token.text).isEmpty()) continue
            val opens = groups.isEmpty() || token.text.startsWith(" ") || normalise(tokens[i - 1].text).isEmpty()
            if (opens) groups += mutableListOf(i) else groups.last() += i
        }
        return groups
    }

    private fun normalise(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }

    /** A window long enough to judge, with next to no punctuation: what a decode without the prompt can produce. */
    fun unpunctuated(replacement: Replacement): Boolean {
        val text = replacement.cues.joinToString(" ") { it.text }
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val marks = text.count { it in ".,!?;:" }
        return words >= MIN_WORDS_TO_JUDGE_PUNCTUATION && marks.toDouble() / words < TranscriptQuality.MIN_PUNCTUATION_PER_WORD
    }

    private const val MIN_WORDS_TO_JUDGE_PUNCTUATION = 30

    /** One stretch sent to whisper on its own; [cutInSpeech] means it ends mid-speech, overlapping the next. */
    data class Chunk(
        val window: TimeWindow,
        val cutInSpeech: Boolean = false,
        /** Where the first or last chunk keeps words from or to: its clip runs on past the edge to hear that word whole. */
        val keep: TimeWindow? = null,
    )

    /**
     * The speech between [from] and [to] in pieces that fit one of whisper's windows, cut at the widest pause; with no
     * pause, cut inside the speech, the two sharing [CHUNK_OVERLAP_SECONDS] so the words there come whole from one.
     */
    fun chunks(
        speech: List<TimeWindow>,
        from: Double,
        to: Double,
    ): List<Chunk> {
        val spans = speech.mapNotNull { TimeWindow(maxOf(it.start, from), minOf(it.end, to)).takeIf { w -> w.end > w.start } }
        if (spans.isEmpty()) return emptyList()
        val raw = mutableListOf<Chunk>()
        var k = 0
        var start = spans[0].start
        while (true) {
            var j = k
            while (j + 1 < spans.size && spans[j + 1].end - start <= MAX_CHUNK_SECONDS) j++
            if (spans[j].end - start > MAX_CHUNK_SECONDS) {
                raw += Chunk(TimeWindow(start, start + MAX_CHUNK_SECONDS), cutInSpeech = true)
                start += MAX_CHUNK_SECONDS - CHUNK_OVERLAP_SECONDS
                continue
            }
            if (j == spans.lastIndex) {
                raw += Chunk(TimeWindow(start, spans[j].end))
                break
            }
            val cut =
                (j downTo k).filter { spans[it].end - start >= MIN_CHUNK_SECONDS }
                    .maxByOrNull { spans[it + 1].start - spans[it].end } ?: j
            raw += Chunk(TimeWindow(start, spans[cut].end))
            k = cut + 1
            start = spans[k].start
        }
        // Padded only away from a neighbour it touches, or the word at the seam is decoded twice.
        val padded =
            raw.mapIndexed { i, chunk ->
                val it = chunk.window
                val before = raw.getOrNull(i - 1)?.let { p -> p.cutInSpeech || it.start - p.window.end < 2 * CHUNK_PAD_SECONDS } ?: false
                val after = raw.getOrNull(i + 1)?.let { n -> chunk.cutInSpeech || n.window.start - it.end < 2 * CHUNK_PAD_SECONDS } ?: false
                chunk.copy(
                    window =
                        TimeWindow(
                            if (before) it.start else maxOf(from, it.start - CHUNK_PAD_SECONDS),
                            if (after) it.end else minOf(to, it.end + CHUNK_PAD_SECONDS),
                        ),
                )
            }.toMutableList()
        return heardPastEdges(padded, from, to)
    }

    /**
     * A clip cut on a word at the end writes a guess at it or nothing, so the last chunk hears past the end. Not the
     * first: whisper writes the word before it again there, timed a second late.
     */
    private fun heardPastEdges(
        chunks: MutableList<Chunk>,
        from: Double,
        to: Double,
    ): List<Chunk> {
        val last = chunks.last()
        chunks[chunks.lastIndex] = last.copy(window = TimeWindow(last.window.start, maxOf(last.window.end, to + EDGE_HEAR_SECONDS)))
        chunks[0] = chunks[0].copy(keep = TimeWindow(from - EDGE_SLACK_SECONDS, chunks[0].keep?.end ?: Double.MAX_VALUE))
        val keptFrom = chunks.last().keep?.start ?: -Double.MAX_VALUE
        chunks[chunks.lastIndex] = chunks.last().copy(keep = TimeWindow(keptFrom, to + EDGE_SLACK_SECONDS))
        return chunks
    }

    /**
     * The chunks' decodes as one, each keeping only the words that start inside its own window. Where two overlap it
     * cuts at a word both heard nearest the middle of the overlap, taking that word from the later chunk.
     */
    fun stitched(
        chunks: List<Chunk>,
        decodes: List<WhisperTranscription>,
    ): WhisperTranscription {
        val from = chunks.map { maxOf(it.window.start - EDGE_SLACK_SECONDS, it.keep?.start ?: -Double.MAX_VALUE) }.toMutableList()
        val until = chunks.map { minOf(it.window.end + EDGE_SLACK_SECONDS, it.keep?.end ?: Double.MAX_VALUE) }.toMutableList()
        val floor = MutableList<Double?>(chunks.size) { null }
        for (i in 0 until chunks.size - 1) {
            if (!chunks[i].cutInSpeech) continue
            val overlap = TimeWindow(chunks[i + 1].window.start, chunks[i].window.end)
            // A piece picked up after the one before stopped: nothing of the first is past its end, so the next keeps from there.
            if (overlap.end <= overlap.start) {
                until[i] = chunks[i].window.end
                from[i + 1] = chunks[i].window.end
                continue
            }
            val middle = (overlap.start + overlap.end) / 2
            val mine = decodes[i].words.filter { it.opensWord() && it.start >= overlap.start && it.start < overlap.end }
            val theirs = decodes[i + 1].words.filter { it.opensWord() && it.start >= overlap.start && it.start < overlap.end }
            val shared =
                mine.flatMap { a ->
                    theirs.filter { b -> normalise(a.text) == normalise(b.text) && abs(a.start - b.start) <= SAME_WORD_SECONDS }
                        .map { a to it }
                }.minByOrNull { abs(it.first.start - middle) }
            val span = overlap.end - overlap.start
            val early = decodes[i + 1].words.filter { it.opensWord() && it.start >= overlap.start - span && it.start < overlap.end }
            val inTurn = if (shared == null) sharedInTurn(mine, early, span).minByOrNull { abs(it.first.start - middle) } else null
            until[i] = (shared ?: inTurn)?.first?.start ?: middle
            from[i + 1] = (shared ?: inTurn)?.second?.start ?: middle
            if (inTurn != null) floor[i + 1] = until[i]
        }
        val parts = decodes.mapIndexed { i, it -> within(it, from[i], until[i]) }.toMutableList()
        for (i in 1 until parts.size) {
            val before = parts[i - 1].words.lastOrNull { it.opensWord() } ?: continue
            val after = parts[i].words.firstOrNull { it.opensWord() } ?: continue
            if (!sameWordAtSeam(normalise(after.text), after.start, before)) continue
            val next = parts[i].words.firstOrNull { it.opensWord() && it.start > after.start }?.start ?: until[i]
            parts[i] = within(decodes[i], next, until[i])
        }
        return joined(parts.mapIndexed { i, part -> floor[i]?.let { notBefore(part, it) } ?: part })
    }

    /**
     * Words both chunks heard in turn, two or more at one offset of up to [slack], each pair a place to cut: whisper
     * times a clip's first words up to a second early, past any one shared word's reach.
     */
    private fun sharedInTurn(
        mine: List<Word>,
        theirs: List<Word>,
        slack: Double,
    ): List<Pair<Word, Word>> {
        fun offset(
            a: Int,
            b: Int,
        ) = mine[a].start - theirs[b].start

        fun inTurn(
            a: Int,
            b: Int,
        ) = (0 until SHARED_RUN_WORDS).all { k ->
            a + k < mine.size && b + k < theirs.size && normalise(mine[a + k].text) == normalise(theirs[b + k].text) &&
                abs(offset(a + k, b + k)) <= slack && abs(offset(a + k, b + k) - offset(a, b)) <= SAME_WORD_SECONDS
        }
        return mine.indices.flatMap { a -> theirs.indices.filter { b -> inTurn(a, b) }.map { mine[a] to theirs[it] } }
    }

    private const val SHARED_RUN_WORDS = 2

    /** [part] with nothing before [floor]: a clip's first words, timed early, moved up to where the chunk before stopped. */
    private fun notBefore(
        part: WhisperTranscription,
        floor: Double,
    ): WhisperTranscription =
        WhisperTranscription.of(
            part.cues.map { it.copy(start = maxOf(it.start, floor), end = maxOf(it.end, floor)) },
            part.words.map { it.copy(start = maxOf(it.start, floor), end = maxOf(it.end, floor)) },
        )

    private fun Word.opensWord() = text.startsWith(" ") && normalise(text).isNotEmpty()

    private fun sameWordAtSeam(
        said: String,
        at: Double,
        edge: Word,
    ) = said == normalise(edge.text) && abs(at - edge.start) <= SAME_WORD_AT_SEAM_SECONDS

    /** The words of [decode] that start in [from, until), each cue kept whole, cut to its kept words, or dropped. */
    fun within(
        decode: WhisperTranscription,
        from: Double,
        until: Double,
    ): WhisperTranscription {
        val bySegment = decode.words.groupBy { it.segment }
        val cues = mutableListOf<Cue>()
        val words = mutableListOf<Word>()
        for ((i, cue) in decode.cues.withIndex()) {
            val all = bySegment[i].orEmpty()
            val kept = all.filter { it.start >= from && it.start < until }
            when {
                all.isEmpty() && cue.start >= from && cue.start < until -> cues += cue
                kept.isEmpty() -> continue
                kept.size == all.size -> cues += cue
                else -> cues += Cue(kept.first().start, kept.last().end, kept.joinToString("") { it.text })
            }
            words += kept.map { it.copy(segment = cues.lastIndex) }
        }
        return WhisperTranscription.of(cues, words)
    }

    /** Separate decodes read as one, each one's words still pointing at its own cues. */
    fun joined(parts: List<WhisperTranscription>): WhisperTranscription {
        val cues = mutableListOf<Cue>()
        val words = mutableListOf<Word>()
        for (part in parts) {
            words += part.words.map { it.copy(segment = it.segment + cues.size) }
            cues += part.cues
        }
        return WhisperTranscription.of(cues, words)
    }

    /** Seconds of [speech] between [from] and [to] with no spoken word starting within [cover]. */
    fun uncovered(
        words: List<Word>,
        speech: List<TimeWindow>,
        from: Double,
        to: Double,
        cover: Double = CLOSE_COVER_SECONDS,
    ): Double {
        val starts = words.filter { normalise(it.text).isNotEmpty() }.map { it.start }.sorted()
        return speech.mapNotNull { TimeWindow(maxOf(it.start, from), minOf(it.end, to)).takeIf { w -> w.end > w.start } }.sumOf { span ->
            var lost = 0.0
            var t = span.start
            while (t < span.end) {
                val step = minOf(SAMPLE_SECONDS, span.end - t)
                if (!near(starts, t + step / 2, cover)) lost += step
                t += step
            }
            lost
        }
    }

    private fun near(
        sorted: List<Double>,
        t: Double,
        within: Double,
    ): Boolean {
        val at = sorted.binarySearch(t).let { if (it < 0) -it - 1 else it }
        return listOfNotNull(sorted.getOrNull(at - 1), sorted.getOrNull(at)).any { abs(it - t) <= within }
    }

    private const val SAMPLE_SECONDS = 0.1

    /** Under whisper's 30 s window, with room for the pads either side. */
    private const val MAX_CHUNK_SECONDS = 28.0
    private const val CHUNK_PAD_SECONDS = 0.2
    private const val MIN_CHUNK_SECONDS = 10.0
    private const val CHUNK_OVERLAP_SECONDS = 2.0
    private const val SAME_WORD_SECONDS = 0.4
    private const val SAME_WORD_AT_SEAM_SECONDS = 0.5

    /** A clip starts on a frame boundary before its window, and whisper times a word by a fraction of a second. */
    private const val EDGE_SLACK_SECONDS = 0.25
    private const val EDGE_HEAR_SECONDS = 0.5

    /** whisper's own filler: a stock phrase or a sentence of the prompt, which a decode may drop at any edge. */
    fun filler(
        cue: Cue,
        prompt: Prompt,
    ): Boolean = STOCK.any { it.voices(cue.text) } || prompt.voices(cue.text)
}
