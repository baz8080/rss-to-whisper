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
import kotlin.test.assertTrue

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

    private fun fakeFpcalc(
        dir: Path,
        exit: Int,
        duration: Int = 10,
    ): String {
        val script = dir.resolve("fpcalc-$exit-$duration")
        Files.writeString(script, "#!/bin/sh\necho DURATION=$duration\necho FINGERPRINT=${(1..16).joinToString(",")}\nexit $exit\n")
        script.toFile().setExecutable(true)
        return script.toString()
    }

    @Test
    fun `a read stopped short is used but not cached, and one read to near its end is cached`(
        @TempDir dir: Path,
    ) {
        val audio = Files.createDirectories(dir.resolve("show/episode")).resolve("audio.mp3")
        Files.write(audio, ByteArray(10))
        val cache = dir.resolve("cache")
        val fp = cache.resolve("show__episode.fp")
        assertEquals(16, Fingerprinter(fakeFpcalc(dir, 3, duration = 60), cache).fingerprint(audio).values.size)
        assertTrue(!Files.exists(fp))
        Fingerprinter(fakeFpcalc(dir, 3, duration = 2), cache).fingerprint(audio)
        assertTrue(Files.exists(fp))
        Files.delete(fp)
        Fingerprinter(fakeFpcalc(dir, 0, duration = 60), cache).fingerprint(audio)
        assertTrue(Files.exists(fp))
        assertThrows<FingerprinterFailed> { Fingerprinter(fakeFpcalc(dir, 1)).fingerprint(audio) }
    }
}
