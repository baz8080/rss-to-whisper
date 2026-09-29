package com.rsstowhisper.external

import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The VAD tool could not be run or said something unreadable, and will for every file until it is fixed. */
class SpeechDetectorFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Where Silero VAD hears speech in a file, from whisper.cpp's
 * `whisper-vad-speech-segments`. Not through the server: with `vad` on, the
 * server applies `offset_t` to VAD-compressed time, so a window's spans come
 * back wrong, and a whole-file request also transcribes every span.
 */
open class SpeechDetector(
    private val binary: String,
    private val model: String,
) {
    open fun speech(audioPath: Path): List<TimeWindow> =
        try {
            detect(audioPath)
        } catch (e: SpeechDetectorFailed) {
            throw e
        } catch (e: IOException) {
            throw SpeechDetectorFailed("Cannot run $binary on $audioPath: ${e.message}", e)
        }

    private fun detect(audioPath: Path): List<TimeWindow> {
        // To a file, not a pipe: reading a pipe to its end would wait out a hung process and never reach the timeout.
        val out = Files.createTempFile("vad-", ".txt")
        try {
            val process =
                try {
                    ProcessBuilder(binary, "-np", "-vm", model, "-f", audioPath.toString())
                        .redirectOutput(out.toFile())
                        .redirectError(Redirect.DISCARD)
                        .start()
                } catch (e: IOException) {
                    throw SpeechDetectorFailed("Cannot run $binary: ${e.message}", e)
                }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw SpeechDetectorFailed("$binary did not finish on $audioPath within $TIMEOUT_MINUTES minutes")
            }
            if (process.exitValue() != 0) throw SpeechDetectorFailed("$binary exited ${process.exitValue()} on $audioPath")
            return parse(Files.readString(out))
        } finally {
            Files.deleteIfExists(out)
        }
    }

    companion object {
        private const val TIMEOUT_MINUTES = 10L

        private val DETECTED = Regex("""Detected (\d+) speech segments""")
        private val SEGMENT = Regex("""start = ([0-9.]+), end = ([0-9.]+)""")

        /**
         * The tool prints centiseconds. Its own count must match what was read: it exits 0 with
         * nothing printed on a bad argument, and nothing heard would otherwise mean silence throughout.
         */
        fun parse(output: String): List<TimeWindow> {
            val spans =
                SEGMENT.findAll(output).map { TimeWindow(it.groupValues[1].toDouble() / 100, it.groupValues[2].toDouble() / 100) }.toList()
            val detected = DETECTED.find(output)?.groupValues?.get(1)?.toInt()
            if (detected != spans.size) {
                throw SpeechDetectorFailed("The VAD tool reported ${detected ?: "no count of"} speech segments and printed ${spans.size}")
            }
            return spans
        }
    }
}
