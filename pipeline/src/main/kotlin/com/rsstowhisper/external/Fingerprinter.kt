package com.rsstowhisper.external

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit

/** fpcalc could not be run or said something unreadable. */
class FingerprinterFailed(message: String, cause: Throwable? = null) : IOException(message, cause)

/** An episode's Chromaprint sub-fingerprints, one per [HOP] seconds from the start of the file. */
class Fingerprint(
    val values: IntArray,
)

/** Chromaprint's raw fingerprint of a whole file, from its `fpcalc` tool, which decodes the mp3 itself. */
open class Fingerprinter(
    private val binary: String,
    private val cache: Path? = null,
) {
    open fun fingerprint(audioPath: Path): Fingerprint {
        val cached = cache?.resolve("${audioPath.parent.parent.fileName}__${audioPath.parent.fileName}.fp")
        if (cached != null && Files.exists(cached)) readCache(Files.readAllBytes(cached), Files.size(audioPath))?.let { return it }
        val (fingerprint, whole) =
            try {
                compute(audioPath)
            } catch (e: FingerprinterFailed) {
                throw e
            } catch (e: IOException) {
                throw FingerprinterFailed("Cannot run $binary on $audioPath: ${e.message}", e)
            }
        if (cached != null && whole) {
            Files.createDirectories(cached.parent)
            val partial = Files.createTempFile(cached.parent, ".fp-", ".fp")
            Files.write(partial, cacheBytes(fingerprint, Files.size(audioPath)))
            Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return fingerprint
    }

    /** The fingerprint, and whether it is worth keeping: fpcalc read the whole file, or stopped only near its end. */
    private fun compute(audioPath: Path): Pair<Fingerprint, Boolean> {
        // To a file, not a pipe: reading a pipe to its end would wait out a hung process and never reach the timeout.
        val out = Files.createTempFile("fp-", ".txt")
        try {
            val process =
                try {
                    ProcessBuilder(binary, "-raw", "-length", "0", audioPath.toString())
                        .redirectOutput(out.toFile())
                        .redirectError(Redirect.DISCARD)
                        .start()
                } catch (e: IOException) {
                    throw FingerprinterFailed("Cannot run $binary: ${e.message}", e)
                }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw FingerprinterFailed("$binary did not finish on $audioPath within $TIMEOUT_MINUTES minutes")
            }
            // 3 is a read error part way, often junk after the last frame: what came before is fingerprinted and printed.
            if (process.exitValue() !in setOf(0, PARTIAL_READ)) {
                throw FingerprinterFailed(
                    "$binary exited ${process.exitValue()} on $audioPath",
                )
            }
            val fingerprint = parse(Files.readString(out))
            if (process.exitValue() == 0) return fingerprint to true
            // Junk after the last frame stops it within seconds of the end; earlier may be the volume, so try again next time.
            // Measured against the frames, not fpcalc's DURATION: that is a bit-rate estimate, far out for some VBR files.
            val read = fingerprint.values.size * HOP
            val length = synchronized(WHOLE_FILE_READS) { Mp3Frames.of(audioPath)?.duration }
            val nearEnd = length != null && read >= length - PARTIAL_SLACK_SECONDS
            if (!nearEnd) {
                val of = length?.let { "%.0f".format(Locale.ROOT, it) } ?: "an unknown length of"
                logger.warn("$binary read $audioPath only to %.0f of $of s; not cached".format(Locale.ROOT, read))
            }
            return fingerprint to nearEnd
        } finally {
            Files.deleteIfExists(out)
        }
    }

    companion object {
        /** Chromaprint's default algorithm: 11,025 Hz, 4,096-sample frames, a third of a frame apart. */
        const val HOP = 1365.0 / 11025

        private const val TIMEOUT_MINUTES = 10L
        private const val PARTIAL_READ = 3
        private const val PARTIAL_SLACK_SECONDS = 5.0
        private val logger = LoggerFactory.getLogger(Fingerprinter::class.java)

        /** One mp3 at a time held whole to time its frames: a few partial reads at once could each be a 200 MB episode. */
        private val WHOLE_FILE_READS = Any()
        private const val CACHE_MAGIC = 0x46503031 // "FP01"

        fun parse(output: String): Fingerprint {
            val lines = output.lineSequence().associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val printed = lines["FINGERPRINT"]?.trim().orEmpty()
            if (printed.isEmpty()) throw FingerprinterFailed("fpcalc printed no FINGERPRINT")
            val values =
                try {
                    // Unsigned 32-bit numbers: the bits are what matter, not the sign.
                    printed.split(',').map { it.trim().toLong().toInt() }.toIntArray()
                } catch (e: NumberFormatException) {
                    throw FingerprinterFailed("fpcalc printed a FINGERPRINT that is not a list of numbers", e)
                }
            return Fingerprint(values)
        }

        fun cacheBytes(
            fingerprint: Fingerprint,
            audioBytes: Long,
        ): ByteArray {
            val bytes = ByteArrayOutputStream(16 + 4 * fingerprint.values.size)
            DataOutputStream(bytes).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeLong(audioBytes)
                // Where fpcalc's DURATION was kept; unread, and kept so earlier cache files still read.
                out.writeInt(0)
                out.writeInt(fingerprint.values.size)
                fingerprint.values.forEach { out.writeInt(it) }
            }
            return bytes.toByteArray()
        }

        /** Null for a file cut short, or one written for audio of another size. */
        fun readCache(
            bytes: ByteArray,
            audioBytes: Long,
        ): Fingerprint? =
            try {
                DataInputStream(bytes.inputStream()).use { input ->
                    if (input.readInt() != CACHE_MAGIC || input.readLong() != audioBytes) return null
                    input.readInt()
                    val count = input.readInt()
                    if (count <= 0 || bytes.size != 20 + 4 * count) return null
                    Fingerprint(IntArray(count) { input.readInt() })
                }
            } catch (e: EOFException) {
                null
            }
    }
}
