package com.rsstowhisper.pipeline

import com.rsstowhisper.external.SpeakerTurn
import com.rsstowhisper.external.TimeWindow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerTurnsTest {
    private val models = mapOf<String, Any>("segmentation" to "seg/model.onnx", "embedding" to "emb/a.onnx", "threshold" to 0.5)

    @Test
    fun `a voice that talks for most of a span is its voice`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 7.0, 0), SpeakerTurn(7.0, 20.0, 1)))

        assertEquals(0, speakers.voiceOf(TimeWindow(2.0, 9.0)))
        assertEquals(1, speakers.voiceOf(TimeWindow(5.0, 10.0)))
    }

    @Test
    fun `a voice that talks for exactly half of a span, beside quieter ones, is its voice`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 5.0, 0), SpeakerTurn(5.0, 8.0, 1), SpeakerTurn(8.0, 10.0, 2)))

        assertEquals(0, speakers.voiceOf(TimeWindow(0.0, 10.0)))
    }

    @Test
    fun `two voices that each hold half a span have no single voice`() {
        assertNull(SpeakerTurns(listOf(SpeakerTurn(0.0, 5.0, 0), SpeakerTurn(5.0, 10.0, 1))).voiceOf(TimeWindow(0.0, 10.0)))
    }

    @Test
    fun `a lap two overlapping voices both talk through is not traded`() {
        // Behind the Bastards 2019-03-13, 6:14: one voice holds both laps, another overlaps the second.
        val speakers = SpeakerTurns(listOf(SpeakerTurn(370.98, 379.97, 83), SpeakerTurn(376.66, 377.96, 82)))

        assertFalse(speakers.traded(listOf(TimeWindow(374.06, 376.74), TimeWindow(376.74, 377.80))))
    }

    @Test
    fun `a voice that talks either side of another still adds up`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 3.0, 0), SpeakerTurn(3.0, 4.0, 1), SpeakerTurn(4.0, 7.0, 0)))

        assertEquals(0, speakers.voiceOf(TimeWindow(0.0, 7.0)))
    }

    @Test
    fun `no voice holds a span it shares out under half each`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 1.0, 0), SpeakerTurn(1.0, 2.0, 1), SpeakerTurn(2.0, 3.0, 2)))

        assertNull(speakers.voiceOf(TimeWindow(0.0, 3.0)))
    }

    @Test
    fun `a span mostly outside any turn has no voice`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 2.0, 0)))

        assertNull(speakers.voiceOf(TimeWindow(1.0, 10.0)))
    }

    @Test
    fun `a span no turn overlaps has no voice, and nor does one against no turns`() {
        assertNull(SpeakerTurns(listOf(SpeakerTurn(0.0, 5.0, 0))).voiceOf(TimeWindow(5.0, 8.0)))
        assertNull(SpeakerTurns(emptyList()).voiceOf(TimeWindow(0.0, 3.0)))
    }

    private val alternating = SpeakerTurns((0 until 8).map { SpeakerTurn(it * 3.0, it * 3.0 + 3.0, it % 2) })

    private fun laps(vararg index: Int) = index.map { TimeWindow(it * 3.0, it * 3.0 + 3.0) }

    @Test
    fun `laps whose voice changes at every lap are traded`() {
        assertTrue(alternating.traded(laps(0, 1)))
        assertTrue(alternating.traded(laps(0, 1, 2, 3)))
    }

    @Test
    fun `laps in one voice twice running are not traded`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 6.0, 0), SpeakerTurn(6.0, 9.0, 1), SpeakerTurn(9.0, 12.0, 0)))

        assertFalse(speakers.traded(laps(0, 1, 2, 3)))
        assertFalse(alternating.traded(laps(0, 2)))
    }

    @Test
    fun `laps all in one voice are not traded`() {
        assertFalse(SpeakerTurns(listOf(SpeakerTurn(0.0, 30.0, 0))).traded(laps(0, 1, 2, 3)))
    }

    @Test
    fun `a lap with no voice means the laps are not traded`() {
        val speakers = SpeakerTurns(listOf(SpeakerTurn(0.0, 3.0, 0), SpeakerTurn(6.0, 9.0, 1)))

        assertFalse(speakers.traded(laps(0, 1, 2)))
        assertFalse(speakers.traded(laps(1, 2)))
    }

    @Test
    fun `a single lap is not traded`() {
        assertFalse(alternating.traded(laps(0)))
        assertFalse(alternating.traded(emptyList()))
    }

    @Test
    fun `turns written for an audio read back for that audio`(
        @TempDir dir: Path,
    ) {
        val turns = listOf(SpeakerTurn(0.0, 4.25, 0), SpeakerTurn(4.25, 9.5, 1), SpeakerTurn(9.5, 12.0, 0))

        SpeakerTurns.write(dir, "sha-a", 100L, models, turns)
        val read = SpeakerTurns.read(dir, "sha-a")

        assertNotNull(read)
        assertEquals(turns, read.turns)
        assertTrue(Files.exists(dir.resolve(SpeakerTurns.FILENAME)))
    }

    @Test
    fun `writing again replaces the turns and leaves no partial file behind`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 1.0, 0)))
        SpeakerTurns.write(dir, "sha-b", 100L, models, listOf(SpeakerTurn(0.0, 2.0, 1)))

        assertEquals(listOf(SpeakerTurn(0.0, 2.0, 1)), SpeakerTurns.read(dir, "sha-b")?.turns)
        assertEquals(listOf(SpeakerTurns.FILENAME), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `turns for another audio are not read`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 4.0, 0)))

        assertNull(SpeakerTurns.read(dir, "sha-b"))
        assertNull(SpeakerTurns.read(dir, null))
    }

    @Test
    fun `a transcript that recorded no audio takes turns for audio of its mp3's size`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 4.0, 0)))

        assertEquals(listOf(SpeakerTurn(0.0, 4.0, 0)), SpeakerTurns.read(dir, null, 100L)?.turns)
        assertNull(SpeakerTurns.read(dir, null, 101L))
    }

    @Test
    fun `turns are not current for audio of another size`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 4.0, 0)))

        assertFalse(SpeakerTurns.current(dir, "sha-a", 101L, models))
    }

    @Test
    fun `a turns file that will not parse, or holds a malformed turn, reads as none and is not current`(
        @TempDir dir: Path,
    ) {
        Files.writeString(dir.resolve(SpeakerTurns.FILENAME), "{\"audio_sha256\": \"sha-a\", \"tur")
        assertNull(SpeakerTurns.read(dir, "sha-a"))
        assertFalse(SpeakerTurns.current(dir, "sha-a", 100L, models))

        Files.writeString(dir.resolve(SpeakerTurns.FILENAME), """{"audio_sha256": "sha-a", "audio_bytes": 100, "turns": [[0.0, "x", 1]]}""")
        assertNull(SpeakerTurns.read(dir, "sha-a"))
        assertFalse(SpeakerTurns.current(dir, "sha-a", 100L, models))
    }

    @Test
    fun `an episode with no turns file reads as none`(
        @TempDir dir: Path,
    ) {
        assertNull(SpeakerTurns.read(dir, "sha-a"))
        assertFalse(SpeakerTurns.current(dir, "sha-a", 100L, models))
    }

    @Test
    fun `turns are current for the audio and models they were made with`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 4.0, 0)))

        assertTrue(SpeakerTurns.current(dir, "sha-a", 100L, models))
    }

    @Test
    fun `turns are not current for another audio`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 4.0, 0)))

        assertFalse(SpeakerTurns.current(dir, "sha-b", 100L, models))
    }

    @Test
    fun `turns are not current when a model or the threshold differs`(
        @TempDir dir: Path,
    ) {
        SpeakerTurns.write(dir, "sha-a", 100L, models, listOf(SpeakerTurn(0.0, 4.0, 0)))

        assertFalse(SpeakerTurns.current(dir, "sha-a", 100L, models + ("embedding" to "emb/b.onnx")))
        assertFalse(SpeakerTurns.current(dir, "sha-a", 100L, models + ("segmentation" to "other/model.onnx")))
        assertFalse(SpeakerTurns.current(dir, "sha-a", 100L, models + ("threshold" to 0.6)))
    }
}
