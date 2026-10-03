package com.rsstowhisper.external

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The diarization step cannot run at all, and will fail every file until it is fixed. */
class SpeakerDiarizerFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One file could not be diarized: an mp3 ffmpeg cannot decode, or one that takes too long. */
class EpisodeNotDiarized(message: String, cause: Throwable? = null) : IOException(message, cause)

data class SpeakerTurn(
    val start: Double,
    val end: Double,
    val speaker: Int,
)

/**
 * Who speaks when, from sherpa-onnx's offline speaker diarization (pyannote segmentation, a speaker embedding,
 * fast clustering), run by the `diarize.py` bundled with the pipeline under [python]. Needs ffmpeg on PATH.
 */
open class SpeakerDiarizer(
    private val python: String,
    private val segmentation: String,
    private val embedding: String,
) {
    /** What the turns depend on besides the audio; turns stored under any other are stale. */
    open val models: Map<String, Any> get() =
        mapOf(
            "segmentation" to name(segmentation),
            "embedding" to name(embedding),
            "threshold" to THRESHOLD,
        )

    /** That Python has sherpa-onnx, ffmpeg is on PATH and the models load: whatever fails after this is the file's. */
    open fun check() {
        val (exit, _, why) = run(listOf("--check"), CHECK_MINUTES) ?: throw SpeakerDiarizerFailed("The diarize check did not finish")
        if (exit != 0) throw SpeakerDiarizerFailed("The diarize check failed: $why")
    }

    open fun turns(audioPath: Path): List<SpeakerTurn> {
        val (exit, out, why) =
            run(listOf(audioPath.toString()), TIMEOUT_MINUTES)
                ?: throw EpisodeNotDiarized("Diarization did not finish on $audioPath within $TIMEOUT_MINUTES minutes")
        if (exit != 0) throw EpisodeNotDiarized("Diarization exited $exit on $audioPath: $why")
        return try {
            parse(out)
        } catch (e: SpeakerDiarizerFailed) {
            throw EpisodeNotDiarized("${e.message} on $audioPath", e)
        }
    }

    /** Exit code, stdout and the last line of stderr; null if it ran out of time. The script is written afresh for each run. */
    private fun run(
        args: List<String>,
        minutes: Long,
    ): Triple<Int, String, String>? {
        val script = Files.createTempFile("diarize-", ".py")
        val out = Files.createTempFile("diarize-", ".json")
        val err = Files.createTempFile("diarize-", ".err")
        try {
            SpeakerDiarizer::class.java.getResourceAsStream("/diarize.py").use { input ->
                Files.write(script, requireNotNull(input) { "diarize.py is missing from the pipeline's resources" }.readBytes())
            }
            val command =
                listOf(python, script.toString(), "--segmentation", segmentation, "--embedding", embedding) +
                    listOf("--threshold", THRESHOLD.toString(), "--threads", THREADS.toString()) + args
            val process =
                try {
                    ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(Redirect.to(err.toFile())).start()
                } catch (e: IOException) {
                    throw SpeakerDiarizerFailed("Cannot run $python: ${e.message}", e)
                }
            if (!process.waitFor(minutes, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return null
            }
            return Triple(process.exitValue(), Files.readString(out), Files.readString(err).trim().lines().lastOrNull().orEmpty())
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(out)
            Files.deleteIfExists(err)
        }
    }

    companion object {
        /** sherpa-onnx's default cosine distance at which two voices are one speaker. */
        const val THRESHOLD = 0.5

        /** The Mac's CPU is shared with the whisper server. */
        private const val THREADS = 4
        private const val TIMEOUT_MINUTES = 60L
        private const val CHECK_MINUTES = 5L

        private val mapper = ObjectMapper()

        /** A model's file and the folder it sits in: the segmentation model is always `model.onnx`. */
        private fun name(path: String): String = Path.of(path).let { p -> listOfNotNull(p.parent?.fileName, p.fileName).joinToString("/") }

        fun parse(json: String): List<SpeakerTurn> {
            val list =
                try {
                    mapper.readValue(json, List::class.java)
                } catch (e: IOException) {
                    throw SpeakerDiarizerFailed("Diarization printed something other than a list of turns: ${e.message}", e)
                }
            return list.map { entry ->
                val turn = entry as? List<*>
                val start = (turn?.getOrNull(0) as? Number)?.toDouble()
                val end = (turn?.getOrNull(1) as? Number)?.toDouble()
                val speaker = (turn?.getOrNull(2) as? Number)?.toInt()
                if (turn?.size != 3 || start == null || end == null || speaker == null) {
                    throw SpeakerDiarizerFailed("Diarization printed a turn that is not [start, end, speaker]: $entry")
                }
                SpeakerTurn(start, end, speaker)
            }
        }
    }
}
