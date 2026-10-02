package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetimingTest {
    private fun word(
        text: String,
        start: Double,
        end: Double,
        segment: Int,
    ) = Word(text, start, end, 0.9, segment)

    /** Measured: Citation Needed 2019-07-17, 12:59: " However" 779.81–784.76, said just before " College" at 785.11. */
    @Test
    fun `a word held over silence starts just before its speech, and its cue with it`() {
        val base =
            WhisperTranscription.of(
                listOf(Cue(776.0, 779.2, " I hate it so much."), Cue(779.81, 786.3, " However, college did teach.")),
                listOf(
                    word(" I", 776.0, 776.5, 0),
                    word(" hate", 776.5, 777.4, 0),
                    word(" it", 777.4, 778.3, 0),
                    word(" so", 778.3, 778.7, 0),
                    word(" much.", 778.7, 779.2, 0),
                    word(" However", 779.81, 784.76, 1),
                    word(",", 784.76, 784.8, 1),
                    word(" college", 785.11, 785.5, 1),
                    word(" did", 785.5, 785.8, 1),
                    word(" teach.", 785.8, 786.3, 1),
                ),
            )

        val retimed = Retiming.retime(base, listOf(TimeWindow(775.0, 779.3), TimeWindow(784.5, 790.0)))

        assertEquals(784.2, retimed.transcription.words[5].start, 1e-9)
        assertEquals(784.76, retimed.transcription.words[5].end, 1e-9)
        assertEquals(784.2, retimed.transcription.cues[1].start, 1e-9)
        assertEquals(1, retimed.words)
    }

    /** Measured: Spacetime 2020-11-20, 9:32: "Get" on the tail of "on Space Time", the next words over 13 s of silence. */
    @Test
    fun `a cue's opening words over silence move to its speech, and the words already on it stay`() {
        val base =
            WhisperTranscription.of(
                listOf(Cue(568.0, 572.31, " on Space Time."), Cue(572.31, 588.0, " Get closer to greatness at the show.")),
                listOf(
                    word(" on", 568.0, 569.0, 0),
                    word(" Space", 569.0, 570.5, 0),
                    word(" Time.", 570.5, 572.31, 0),
                    word(" Get", 572.31, 572.7, 1),
                    word(" closer", 577.33, 578.0, 1),
                    word(" to", 579.0, 579.3, 1),
                    word(" greatness", 586.2, 586.9, 1),
                    word(" at", 586.9, 587.1, 1),
                    word(" the", 587.1, 587.3, 1),
                    word(" show.", 587.3, 588.0, 1),
                ),
            )

        val retimed = Retiming.retime(base, listOf(TimeWindow(567.0, 572.7), TimeWindow(586.05, 595.0)))
        val words = retimed.transcription.words

        assertEquals(585.75, retimed.transcription.cues[1].start, 1e-9)
        assertEquals(585.75, words[3].start, 1e-9)
        assertEquals(listOf(586.2, 586.9), words.subList(6, 8).map { it.start })
        assertEquals(words.map { it.start }.sorted(), words.map { it.start })
        assertEquals(1, retimed.cues)
    }

    /** Measured: Spacetime 2024-02-28, 24:14: "Time" said, then held over the music; its start is right. */
    @Test
    fun `a word said and then held, and a cue with no speech under it, are left alone`() {
        val base =
            WhisperTranscription.of(
                listOf(Cue(1450.0, 1464.54, " still to come on Space Time."), Cue(1470.0, 1480.0, " Thank you.")),
                listOf(
                    word(" still", 1450.0, 1450.5, 0),
                    word(" to", 1450.5, 1451.0, 0),
                    word(" come", 1451.0, 1452.0, 0),
                    word(" on", 1452.0, 1452.6, 0),
                    word(" Space", 1452.6, 1453.98, 0),
                    word(" Time.", 1453.98, 1464.54, 0),
                    word(" Thank", 1470.0, 1475.0, 1),
                    word(" you.", 1475.0, 1480.0, 1),
                ),
            )

        val retimed = Retiming.retime(base, listOf(TimeWindow(1449.5, 1454.5), TimeWindow(1462.0, 1464.0), TimeWindow(1500.0, 1510.0)))

        assertEquals(base, retimed.transcription)
        assertEquals(0, retimed.words + retimed.cues)
    }
}
