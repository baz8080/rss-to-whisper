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
    private fun printed(
        turns: String,
        voices: String = "{}",
    ) = """{"turns": $turns, "voices": $voices}"""

    @Test
    fun `parse reads each start, end and speaker, and each voice`() {
        val d =
            SpeakerDiarizer.parse(
                printed(
                    "[[0.0, 4.5, 0], [4.5, 9, 1], [9.25, 12.0, 0]]",
                    """{"0": {"seconds": 7.25, "print": [0.6, 0.8]}, "1": {"seconds": 4.5, "print": [1, 0]}}""",
                ),
            )

        assertEquals(listOf(SpeakerTurn(0.0, 4.5, 0), SpeakerTurn(4.5, 9.0, 1), SpeakerTurn(9.25, 12.0, 0)), d.turns)
        assertEquals(setOf(0, 1), d.voices.keys)
        assertEquals(7.25, d.voices.getValue(0).seconds)
        assertEquals(listOf(0.6f, 0.8f), d.voices.getValue(0).print.toList())
    }

    @Test
    fun `parse reads an episode with no speech as no turns and no voices`() {
        val d = SpeakerDiarizer.parse(printed("[]"))

        assertEquals(emptyList(), d.turns)
        assertEquals(emptyMap(), d.voices)
    }

    @Test
    fun `parse refuses a voice it cannot read`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[]", """{"x": {"seconds": 1, "print": [1]}}""")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[]", """{"0": {"seconds": 1, "print": []}}""")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[]", """{"0": {"print": [1]}}""")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[]", """{"0": {"seconds": 1, "print": ["a"]}}""")) }
    }

    @Test
    fun `parse refuses output that is not JSON`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("Traceback (most recent call last):") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("") }
    }

    @Test
    fun `parse refuses output without both turns and voices`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("[[0.0, 4.5, 0]]") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("""{"turns": []}""") }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse("""{"voices": {}}""") }
    }

    @Test
    fun `parse refuses a turn that is not a list of three numbers`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[[0.0, 4.5]]")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[[0.0, 4.5, 0, 9]]")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("""[[0.0, "4.5", 0]]""")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("""[[0.0, 4.5, null]]""")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[3]")) }
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("""[{"start": 0.0, "end": 4.5, "speaker": 0}]""")) }
    }

    @Test
    fun `one good turn does not excuse a bad one after it`() {
        assertFailsWith<SpeakerDiarizerFailed> { SpeakerDiarizer.parse(printed("[[0.0, 4.5, 0], [4.5, 9.0]]")) }
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
        threads: Int = 4,
    ) = SpeakerDiarizer(python, "seg/model.onnx", "emb/titanet.onnx", threads, timeoutSeconds = timeoutSeconds)

    @Test
    fun `the thread count reaches the script`(
        @TempDir dir: Path,
    ) {
        val stub = stub(dir, audio = "case \"$*\" in *\"--threads 2 \"*) echo '$empty' ;; *) exit 1 ;; esac")

        assertEquals(emptyList(), diarizer(stub, threads = 2).diarize(audio).turns)
        assertFailsWith<EpisodeNotDiarized> { diarizer(stub, threads = 4).diarize(audio) }
    }

    private val audio = Path.of("Show", "episode", "audio.mp3")

    private val empty = """{"turns": [], "voices": {}}"""

    @Test
    fun `a run that prints turns gives them, and a passing check passes`(
        @TempDir dir: Path,
    ) {
        val diarizer =
            diarizer(stub(dir, audio = "echo '{\"turns\": [[0.0, 2.5, 1]], \"voices\": {\"1\": {\"seconds\": 2.5, \"print\": [1]}}}'"))

        diarizer.check()
        val d = diarizer.diarize(audio)
        assertEquals(listOf(SpeakerTurn(0.0, 2.5, 1)), d.turns)
        assertEquals(setOf(1), d.voices.keys)
    }

    @Test
    fun `voices for stored turns hands the script those turns and diarizes nothing`(
        @TempDir dir: Path,
    ) {
        // Prints the turns file it was given back as the turns, so the test sees what reached it.
        val script =
            """
            for a; do [ "${'$'}prev" = --turns ] && f="${'$'}a"; prev="${'$'}a"; done
            [ -n "${'$'}f" ] || exit 1
            printf '{"turns": %s, "voices": {"3": {"seconds": 2.0, "print": [1]}}}' "${'$'}(cat "${'$'}f")"
            """.trimIndent()
        val stub = stub(dir, audio = script)

        val voices = diarizer(stub).voices(audio, listOf(SpeakerTurn(1.0, 3.0, 3)))

        assertEquals(setOf(3), voices.keys)
        assertEquals(2.0, voices.getValue(3).seconds)
    }

    @Test
    fun `a file the tool fails on, prints junk for or runs out of time on is that episode's failure`(
        @TempDir dir: Path,
    ) {
        val failed =
            assertFailsWith<EpisodeNotDiarized> { diarizer(stub(dir, audio = "echo 'ffmpeg could not decode' >&2; exit 1")).diarize(audio) }
        assertTrue("ffmpeg could not decode" in failed.message.orEmpty())
        assertFailsWith<EpisodeNotDiarized> { diarizer(stub(dir, audio = "echo 'Loading models'")).diarize(audio) }
        assertFailsWith<EpisodeNotDiarized> { diarizer(stub(dir, audio = "sleep 30"), timeoutSeconds = 1).diarize(audio) }
    }

    @Test
    fun `a Python that cannot start, or a check that fails, is the setup's failure`(
        @TempDir dir: Path,
    ) {
        assertFailsWith<SpeakerDiarizerFailed> { diarizer(dir.resolve("no-such-python").toString()).diarize(audio) }
        assertFailsWith<SpeakerDiarizerFailed> { diarizer(dir.resolve("no-such-python").toString()).check() }
        val failed =
            assertFailsWith<SpeakerDiarizerFailed> {
                diarizer(stub(dir, check = "echo 'ffmpeg is not on PATH' >&2; exit 1", audio = "exit 0")).check()
            }
        assertTrue("ffmpeg is not on PATH" in failed.message.orEmpty())
    }
}
