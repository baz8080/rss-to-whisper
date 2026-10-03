package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.SpeakerTurn
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowRepairSpeakersTest {
    /** Two good cues, "Silmarillion." four times over from 6 s, then a good cue. */
    private fun silmarillion(): List<Cue> =
        listOf(
            Cue(0.0, 3.0, " So that is where the story begins."),
            Cue(3.0, 6.0, " We looked at the data again, carefully."),
        ) + (0 until 4).map { Cue(6.0 + it * 3, 9.0 + it * 3, " Silmarillion.") } +
            listOf(Cue(18.0, 21.0, " And then we found something odd."))

    private fun turns(vararg speakers: Int) = SpeakerTurns(speakers.mapIndexed { i, s -> SpeakerTurn(6.0 + i * 3, 9.0 + i * 3, s) })

    @Test
    fun `four identical cues are a loop without speaker turns`() {
        assertEquals(setOf(2, 3, 4, 5), WindowRepair.defectCues(silmarillion()))
    }

    @Test
    fun `four identical cues traded between two voices are not a loop`() {
        val cues = silmarillion()

        assertEquals(emptySet(), WindowRepair.defectCues(cues, speakers = turns(0, 1, 0, 1)))
        assertEquals(0, WindowRepair.defectsByKind(cues, speakers = turns(0, 1, 0, 1)).getValue("loop").size)
    }

    @Test
    fun `four identical cues in one voice stay a loop`() {
        assertEquals(setOf(2, 3, 4, 5), WindowRepair.defectCues(silmarillion(), speakers = turns(0, 0, 0, 0)))
    }

    @Test
    fun `four identical cues where one voice repeats itself stay a loop`() {
        assertEquals(setOf(2, 3, 4, 5), WindowRepair.defectCues(silmarillion(), speakers = turns(0, 1, 1, 0)))
    }

    @Test
    fun `a loop whose laps straddle turn boundaries stays a loop`() {
        // Three voices a second each: no voice holds half of any three-second lap.
        val straddling = SpeakerTurns((0 until 12).map { SpeakerTurn(6.0 + it, 7.0 + it, it % 3) })

        assertEquals(setOf(2, 3, 4, 5), WindowRepair.defectCues(silmarillion(), speakers = straddling))
    }

    @Test
    fun `turns that cover none of the loop leave it a loop`() {
        val elsewhere = SpeakerTurns(listOf(SpeakerTurn(100.0, 110.0, 0), SpeakerTurn(110.0, 120.0, 1)))

        assertEquals(setOf(2, 3, 4, 5), WindowRepair.defectCues(silmarillion(), speakers = elsewhere))
    }

    private val sentence = " I really did not expect it to end that way."

    private fun echoing(): List<Cue> =
        listOf(
            Cue(0.0, 3.0, sentence),
            Cue(3.0, 6.0, " Well, nobody did."),
            Cue(6.0, 9.0, sentence),
            Cue(9.0, 12.0, " So here we are."),
        )

    @Test
    fun `a long sentence said again within a few cues is an echo without speaker turns`() {
        assertEquals(setOf(2), WindowRepair.echoes(echoing()))
    }

    @Test
    fun `a long sentence echoed by a second voice is not an echo`() {
        val traded = SpeakerTurns(listOf(SpeakerTurn(0.0, 3.0, 0), SpeakerTurn(6.0, 9.0, 1)))

        assertEquals(emptySet(), WindowRepair.echoes(echoing(), traded))
        assertEquals(emptySet(), WindowRepair.defectsByKind(echoing(), speakers = traded).getValue("echo"))
    }

    @Test
    fun `a long sentence repeated by the same voice is still an echo`() {
        val oneVoice = SpeakerTurns(listOf(SpeakerTurn(0.0, 12.0, 0)))

        assertEquals(setOf(2), WindowRepair.echoes(echoing(), oneVoice))
    }
}
