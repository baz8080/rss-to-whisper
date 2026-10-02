package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription

/** Moves words and cues whisper timed before their speech to where VAD hears it start. No decode. */
internal object Retiming {
    data class Retimed(
        val transcription: WhisperTranscription,
        val words: Int,
        val cues: Int,
        val seconds: Double,
    )

    fun retime(
        base: WhisperTranscription,
        speech: List<TimeWindow>,
        /** Cues to leave alone: whisper's loops and filler, which retiming would place on speech they never said. */
        skip: Set<Int> = emptySet(),
    ): Retimed {
        val starts = speech.map { it.start }
        val tokens = base.words.toMutableList()
        var movedWords = 0
        var movedCues = 0
        var seconds = 0.0

        fun spanAt(t: Double) = speech.getOrNull(starts.binarySearch(t).let { if (it >= 0) it else -it - 2 })?.takeIf { t < it.end }

        fun onsetAfter(t: Double) = starts.getOrNull(starts.binarySearch(t).let { if (it >= 0) it + 1 else -it - 1 })

        // Linear, so the tokens of a word or the words of a run keep their order and proportions.
        fun move(
            indices: List<Int>,
            from: Double,
            to: Double,
            newFrom: Double,
            newTo: Double,
        ) {
            val scale = if (to > from) (newTo - newFrom) / (to - from) else 0.0
            for (i in indices) {
                val t = tokens[i]
                tokens[i] = t.copy(start = newFrom + (t.start - from) * scale, end = newFrom + (t.end - from) * scale)
            }
        }

        val bySegment = tokens.indices.groupBy { tokens[it].segment }
        val opening = mutableMapOf<Int, Int>()
        val moved = mutableSetOf<Int>()
        for ((segment, indices) in bySegment.toSortedMap()) {
            if (segment in skip) continue
            val words = WindowRepair.spoken(indices.map { tokens[it] }).map { group -> group.map { indices[it] } }
            if (words.isEmpty()) continue
            opening[segment] = words.first().first()
            // A word held over silence and then said: it starts where its speech does.
            for (word in words) {
                val start = tokens[word.first()].start
                val end = tokens[word.last()].end
                if (end - start < MIN_HELD_SECONDS || spanAt(start) != null) continue
                val onset = onsetAfter(start) ?: continue
                if (onset < start + MIN_EARLY_SECONDS || onset >= end) continue
                val newStart = onset - LEAD_SECONDS
                move(word, start, end, newStart, end)
                moved += segment
                movedWords++
                seconds += newStart - start
            }
            // A cue opening over silence, or on the tail of speech begun before it: its opening words move to its speech.
            val first = tokens[words.first().first()]
            val heard = spanAt(first.start)
            val openSilent =
                heard == null ||
                    (heard.start < first.start - TAIL_SECONDS && words.size > 1 && heard.end <= tokens[words[1].first()].start)
            if (!openSilent) continue
            val onset = onsetAfter(heard?.end ?: first.start) ?: continue
            val last = tokens[words.last().last()].end
            if (onset - first.start < MIN_EARLY_SECONDS || onset >= last) continue
            val ahead = words.takeWhile { tokens[it.first()].start < onset - ON_SPEECH_SLACK_SECONDS }
            val rest = words.drop(ahead.size)
            val newStart = onset - LEAD_SECONDS
            val room = maxOf(rest.firstOrNull()?.let { tokens[it.first()].start } ?: last, newStart + ahead.size * MIN_WORD_SECONDS)
            // Words after them keep their times unless the moved ones now run into them; a cue that would overrun its end stays.
            val kept = indices.map { tokens[it] }
            move(ahead.flatten(), first.start, tokens[ahead.last().last()].end, newStart, room)
            var floor = room
            for (i in rest.flatten()) {
                val t = tokens[i]
                if (t.start >= floor) break
                tokens[i] = t.copy(start = floor, end = maxOf(floor, t.end + (floor - t.start)))
                floor = tokens[i].end
            }
            if (floor > maxOf(last, base.cues[segment].end)) {
                indices.forEachIndexed { n, i -> tokens[i] = kept[n] }
                continue
            }
            moved += segment
            movedCues++
            seconds += newStart - first.start
        }
        if (movedWords == 0 && movedCues == 0) return Retimed(base, 0, 0, 0.0)
        // Punctuation whisper writes before a cue's first word moves with it.
        for (n in moved) {
            val first = opening.getValue(n)
            for (i in bySegment.getValue(n).takeWhile { it != first }) {
                tokens[i] = tokens[i].copy(start = tokens[first].start, end = tokens[first].start)
            }
        }
        val cues =
            base.cues.mapIndexed { n, cue ->
                val first = opening[n]?.takeIf { n in moved } ?: return@mapIndexed cue
                Cue(tokens[first].start, maxOf(cue.end, bySegment.getValue(n).maxOf { tokens[it].end }), cue.text)
            }
        return Retimed(WhisperTranscription.of(cues, tokens, base.run), movedWords, movedCues, seconds)
    }

    /** VAD's onset lands a little into the first word. */
    private const val LEAD_SECONDS = 0.3
    private const val MIN_HELD_SECONDS = 2.0

    /** Under this VAD often hears a breath mid-turn after whisper's right start. */
    private const val MIN_EARLY_SECONDS = 2.0

    /** Speech that began this long before the cue is the cue before's, not this one's. */
    private const val TAIL_SECONDS = 0.3
    private const val ON_SPEECH_SLACK_SECONDS = 0.05
    private const val MIN_WORD_SECONDS = 0.15
}
