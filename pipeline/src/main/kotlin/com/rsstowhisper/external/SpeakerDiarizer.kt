package com.rsstowhisper.external

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The diarization step could not be run or said something unreadable, and will for every file until it is fixed. */
class SpeakerDiarizerFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

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

    open fun turns(audioPath: Path): List<SpeakerTurn> {
        val out = Files.createTempFile("diarize-", ".json")
        val err = Files.createTempFile("diarize-", ".err")
        try {
            val process =
                try {
                    ProcessBuilder(
                        python, script.toString(), "--segmentation", segmentation, "--embedding", embedding,
                        "--threshold", THRESHOLD.toString(), "--threads", THREADS.toString(), audioPath.toString(),
                    ).redirectOutput(out.toFile()).redirectError(Redirect.to(err.toFile())).start()
                } catch (e: IOException) {
                    throw SpeakerDiarizerFailed("Cannot run $python: ${e.message}", e)
                }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw SpeakerDiarizerFailed("Diarization did not finish on $audioPath within $TIMEOUT_MINUTES minutes")
            }
            if (process.exitValue() != 0) {
                val why = Files.readString(err).trim().lines().lastOrNull().orEmpty()
                throw SpeakerDiarizerFailed("Diarization exited ${process.exitValue()} on $audioPath: $why")
            }
            return parse(Files.readString(out))
        } finally {
            Files.deleteIfExists(out)
            Files.deleteIfExists(err)
        }
    }

    companion object {
        /** sherpa-onnx's default cosine distance for one speaker, which Barry's GNN project found clean untuned. */
        const val THRESHOLD = 0.5

        /** The Mac's CPU is shared with the whisper server. */
        private const val THREADS = 4
        private const val TIMEOUT_MINUTES = 60L

        private val mapper = ObjectMapper()

        /** A model's file and the folder it sits in: the segmentation model is always `model.onnx`. */
        private fun name(path: String): String = Path.of(path).let { p -> listOfNotNull(p.parent?.fileName, p.fileName).joinToString("/") }

        private val script: Path by lazy {
            val file = Files.createTempFile("diarize-", ".py")
            file.toFile().deleteOnExit()
            SpeakerDiarizer::class.java.getResourceAsStream("/diarize.py").use { input ->
                Files.write(file, requireNotNull(input) { "diarize.py is missing from the pipeline's resources" }.readBytes())
            }
            file
        }

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
