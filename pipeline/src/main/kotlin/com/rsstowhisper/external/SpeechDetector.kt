package com.rsstowhisper.external

import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
    open fun speech(audioPath: Path): List<TimeWindow> {
        val process =
            ProcessBuilder(binary, "-np", "-vm", model, "-f", audioPath.toString())
                .redirectError(Redirect.DISCARD)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException("$binary did not finish on $audioPath within $TIMEOUT_MINUTES minutes")
        }
        if (process.exitValue() != 0) throw IOException("$binary exited ${process.exitValue()} on $audioPath")
        return parse(output)
    }

    companion object {
        private const val TIMEOUT_MINUTES = 10L

        private val SEGMENT = Regex("""start = ([0-9.]+), end = ([0-9.]+)""")

        /** The tool prints centiseconds. */
        fun parse(output: String): List<TimeWindow> =
            SEGMENT.findAll(output).map { TimeWindow(it.groupValues[1].toDouble() / 100, it.groupValues[2].toDouble() / 100) }.toList()
    }
}
