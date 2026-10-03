package com.rsstowhisper.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeakerDiarizerTest {
    @Test
    fun `parse reads each start, end and speaker`() {
        val turns = SpeakerDiarizer.parse("[[0.0, 4.5, 0], [4.5, 9, 1], [9.25, 12.0, 0]]")

        assertEquals(listOf(SpeakerTurn(0.0, 4.5, 0), SpeakerTurn(4.5, 9.0, 1), SpeakerTurn(9.25, 12.0, 0)), turns)
    }

    @Test
    fun `parse reads an episode with no speech as no turns`() {
        assertEquals(emptyList(), SpeakerDiarizer.parse("[]"))
    }

    @Test
    fun `parse refuses output that is not JSON`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("Traceback (most recent call last):") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("") }
    }

    @Test
    fun `parse refuses JSON that is not a list`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("""{"turns": []}""") }
    }

    @Test
    fun `parse refuses a turn that is not a list of three numbers`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("[[0.0, 4.5]]") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("[[0.0, 4.5, 0, 9]]") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("""[[0.0, "4.5", 0]]""") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("""[[0.0, 4.5, null]]""") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("[3]") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("""[{"start": 0.0, "end": 4.5, "speaker": 0}]""") }
    }

    @Test
    fun `one good turn does not excuse a bad one after it`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("[[0.0, 4.5, 0], [4.5, 9.0]]") }
    }

    @Test
    fun `models name each model by its folder and file, with the threshold`() {
        val diarizer = SpeakerDiarizer("python3", "/models/pyannote/model.onnx", "/models/embed/titanet.onnx")

        assertEquals(
            mapOf("segmentation" to "pyannote/model.onnx", "embedding" to "embed/titanet.onnx", "threshold" to SpeakerDiarizer.THRESHOLD),
            diarizer.models,
        )
    }
}
