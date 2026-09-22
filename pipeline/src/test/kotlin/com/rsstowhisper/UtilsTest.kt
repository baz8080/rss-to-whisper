package com.rsstowhisper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UtilsTest {
    @ParameterizedTest
    @CsvSource(
        ",''",
        "'',''",
        "' ',''",
        "file\$name,file-name",
        "hello__world,hello-world",
        "trailing-,trailing",
        "unsafe@chars!,unsafe-chars",
    )
    fun `test escapeFilename`(
        input: String?,
        expected: String,
    ) {
        assertEquals(expected, escapeFilename(input))
    }

    @Test
    fun `escapeFilename gives one slug whatever the feed's Unicode form`() {
        val composed = "Bl\u00fair\u00edn\u00ed B\u00e9aloidis"
        val decomposed = "Blu\u0301iri\u0301ni\u0301 Be\u0301aloidis"
        val mixed = "Bl\u00fairi\u0301n\u00ed Be\u0301aloidis"
        for (title in listOf(composed, decomposed, mixed)) {
            assertEquals("Bluirini-Bealoidis", escapeFilename(title))
        }
    }

    @Test
    fun `escapeFilename leaves only ASCII`() {
        assertEquals("Closes-in-on-Kamo-oalewa", escapeFilename("Closes in on Kamo\u02bboalewa"))
        assertEquals("Stra-e", escapeFilename("Stra\u00dfe"))
    }

    @Test
    fun `an exact name wins over one that differs only by accents`(
        @TempDir parent: Path,
    ) {
        // "Bluirini Bealoidis" slugs alike and sorts first, so only the exact-name rule picks the other.
        Files.createDirectory(parent.resolve("Bluirini Bealoidis"))
        val exact = Files.createDirectory(parent.resolve("Bluirini-Bealoidis"))
        assertEquals(exact, createPath(parent, "Bl\u00fair\u00edn\u00ed B\u00e9aloidis"))
    }

    @Test
    fun `a name with no ASCII letters is refused, not resolved to the parent`(
        @TempDir parent: Path,
    ) {
        assertFailsWith<IllegalArgumentException> { resolvePath(parent, "\u65e5\u672c") }
    }

    @Test
    fun `an accented directory from before stripping is reused, not duplicated`(
        @TempDir parent: Path,
    ) {
        val old = Files.createDirectory(parent.resolve("Bl\u00fair\u00edn\u00ed-B\u00e9aloidis"))
        assertEquals(old, createPath(parent, "Blu\u0301iri\u0301ni\u0301 Be\u0301aloidis"))
        assertEquals(1, parent.toFile().listFiles()!!.size)
    }

    @ParameterizedTest
    @CsvSource(
        ",0",
        "' ',0",
        "'unexpected string',0",
        "50,50",
        "1:30,90",
        "01:0,60",
        "1:1:15,3675",
        "01:02:20,3740",
        "::,0",
        "1:1:1:1,219661",
    )
    fun `test timeToSeconds`(
        input: String?,
        expected: Int,
    ) {
        assertEquals(expected, timeToSeconds(input))
    }
}
