package com.rsstowhisper.external

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FingerprinterTest {
    private fun output(values: List<Long>) =
        "DURATION=${(values.size * Fingerprinter.HOP).toInt()}\nFINGERPRINT=${values.joinToString(",")}\n"

    @Test
    fun `values above 2^31 keep their bits`() {
        val parsed = Fingerprinter.parse(output(List(80) { if (it == 0) 4293918828L else it.toLong() }))
        assertEquals(4293918828L.toInt(), parsed.values[0])
        assertEquals(80, parsed.values.size)
    }

    @Test
    fun `output without a fingerprint is refused`() {
        assertThrows<FingerprinterFailed> { Fingerprinter.parse("DURATION=10\n") }
        assertThrows<FingerprinterFailed> { Fingerprinter.parse("DURATION=10\nFINGERPRINT=\n") }
        assertThrows<FingerprinterFailed> { Fingerprinter.parse("") }
    }

    @Test
    fun `a binary that cannot run fails as a fingerprinting failure`(
        @TempDir dir: Path,
    ) {
        val audio = Files.createDirectories(dir.resolve("show/episode")).resolve("audio.mp3")
        Files.write(audio, ByteArray(10))
        assertThrows<FingerprinterFailed> { Fingerprinter(dir.resolve("no-such-fpcalc").toString()).fingerprint(audio) }
    }

    @Test
    fun `the cache reads back what it wrote, for audio of the same size only`() {
        val fingerprint = Fingerprint(intArrayOf(1, -2, Int.MAX_VALUE, Int.MIN_VALUE), 1)
        val bytes = Fingerprinter.cacheBytes(fingerprint, 1234)
        val read = assertNotNull(Fingerprinter.readCache(bytes, 1234))
        assertContentEquals(fingerprint.values, read.values)
        assertEquals(1, read.duration)
        assertNull(Fingerprinter.readCache(bytes, 1235))
        assertNull(Fingerprinter.readCache(bytes.copyOf(bytes.size - 2), 1234))
    }

    @Test
    fun `a cached fingerprint is used without running the binary`(
        @TempDir dir: Path,
    ) {
        val audio = Files.createDirectories(dir.resolve("show/episode")).resolve("audio.mp3")
        Files.write(audio, ByteArray(10))
        val cache = Files.createDirectories(dir.resolve("cache"))
        Files.write(cache.resolve("show__episode.fp"), Fingerprinter.cacheBytes(Fingerprint(intArrayOf(7, 8), 0), 10))
        assertContentEquals(intArrayOf(7, 8), Fingerprinter("no-such-fpcalc", cache).fingerprint(audio).values)
    }
}
