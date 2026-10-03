package com.rsstowhisper.external

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    /** A stand-in for Python: a shell script given diarize.py's arguments, answering --check and an audio file apart. */
    private fun stub(
        dir: Path,
        check: String = "exit 0",
        audio: String,
    ): String {
        val file = dir.resolve("python")
        Files.writeString(file, "#!/bin/sh\ncase \"$*\" in *--check*) $check ;; esac\n$audio\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        return file.toString()
    }

    private fun diarizer(
        python: String,
        timeoutSeconds: Long = 30,
    ) = SpeakerDiarizer(python, "seg/model.onnx", "emb/titanet.onnx", timeoutSeconds = timeoutSeconds)

    private val audio = Path.of("Show", "episode", "audio.mp3")

    @Test
    fun `a run that prints turns gives them, and a passing check passes`(
        @TempDir dir: Path,
    ) {
        val diarizer = diarizer(stub(dir, audio = "echo '[[0.0, 2.5, 1]]'"))

        diarizer.check()
        assertEquals(listOf(SpeakerTurn(0.0, 2.5, 1)), diarizer.turns(audio))
    }

    @Test
    fun `a file the tool fails on, prints junk for or runs out of time on is that episode's failure`(
        @TempDir dir: Path,
    ) {
        val failed =
            assertFailsWith<EpisodeNotDiarized> { diarizer(stub(dir, audio = "echo 'ffmpeg could not decode' >&2; exit 1")).turns(audio) }
        assertTrue("ffmpeg could not decode" in failed.message.orEmpty())
        assertFailsWith<EpisodeNotDiarized> { diarizer(stub(dir, audio = "echo 'Loading models'")).turns(audio) }
        assertFailsWith<EpisodeNotDiarized> { diarizer(stub(dir, audio = "sleep 30"), timeoutSeconds = 1).turns(audio) }
    }

    @Test
    fun `a Python that cannot start, or a check that fails, is the setup's failure`(
        @TempDir dir: Path,
    ) {
        assertFailsWith<SpeakerDiarizerFailed> { diarizer(dir.resolve("no-such-python").toString()).turns(audio) }
        assertFailsWith<SpeakerDiarizerFailed> { diarizer(dir.resolve("no-such-python").toString()).check() }
        val failed =
            assertFailsWith<SpeakerDiarizerFailed> {
                diarizer(stub(dir, check = "echo 'ffmpeg is not on PATH' >&2; exit 1", audio = "exit 0")).check()
            }
        assertTrue("ffmpeg is not on PATH" in failed.message.orEmpty())
    }
}
