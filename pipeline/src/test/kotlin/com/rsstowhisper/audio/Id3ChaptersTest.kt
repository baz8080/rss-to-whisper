package com.rsstowhisper.audio

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds the tag bytes a tagger would write, so the parser is tested against real layout. */
internal object Id3Builder {
    fun tag(
        major: Int,
        frames: List<ByteArray>,
        flags: Int = 0,
    ): ByteArray = tagFromBody(major, flags, concat(frames))

    fun tagFromBody(
        major: Int,
        flags: Int,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0)
        out.write(flags)
        out.write(syncSafeBytes(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun concat(frames: List<ByteArray>): ByteArray = frames.fold(ByteArrayOutputStream()) { acc, f -> acc.apply { write(f) } }.toByteArray()

    fun frame(
        id: String,
        body: ByteArray,
        major: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major >= 4) syncSafeBytes(body.size) else plainBytes(body.size))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    /** A frame header whose size is plain even under v2.4, as some taggers write. */
    fun frameWithPlainSize(
        id: String,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(plainBytes(body.size))
        out.write(byteArrayOf(0, 0))
        out.write(body)
        return out.toByteArray()
    }

    /** A frame header whose declared size bears no relation to what (if anything) follows. */
    fun frameHeaderWithSize(
        id: ByteArray,
        sizeBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id)
        out.write(sizeBytes)
        out.write(byteArrayOf(0, 0))
        return out.toByteArray()
    }

    fun extendedHeaderV23(fillerSize: Int): ByteArray = plainBytes(fillerSize) + ByteArray(fillerSize)

    fun extendedHeaderV24(fillerSize: Int): ByteArray = syncSafeBytes(fillerSize + 4) + ByteArray(fillerSize)

    fun title(
        text: String,
        major: Int,
        encoding: Int = 3,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write(
            when (encoding) {
                0 -> text.toByteArray(Charsets.ISO_8859_1)
                1 -> text.toByteArray(Charsets.UTF_16)
                2 -> text.toByteArray(Charsets.UTF_16BE)
                else -> text.toByteArray(Charsets.UTF_8)
            },
        )
        return frame("TIT2", out.toByteArray(), major)
    }

    fun chap(
        elementId: String,
        startMs: Int,
        endMs: Int,
        titleText: String?,
        major: Int,
        encoding: Int = 3,
    ): ByteArray = frame("CHAP", chapContent(elementId, startMs, endMs, titleText, major, encoding), major)

    fun chapContent(
        elementId: String,
        startMs: Int,
        endMs: Int,
        titleText: String?,
        major: Int,
        encoding: Int = 3,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(elementId.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(plainBytes(startMs))
        out.write(plainBytes(endMs))
        out.write(plainBytes(0))
        out.write(plainBytes(0))
        if (titleText != null) out.write(title(titleText, major, encoding))
        return out.toByteArray()
    }

    private fun plainBytes(value: Int) =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun syncSafeBytes(value: Int) =
        byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
}

/** Inverse of [deUnsynchronise]: stuffs a 0x00 after every literal 0xFF byte. */
private fun unsynchronise(body: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    body.forEach { b ->
        out.write(b.toInt())
        if (b == 0xFF.toByte()) out.write(0)
    }
    return out.toByteArray()
}

class Id3ChaptersTest {
    private fun writeMp3(
        dir: Path,
        tag: ByteArray,
    ): Path {
        val path = dir.resolve("audio.mp3")
        // Trailing bytes stand in for the audio the parser must never read.
        Files.write(path, tag + ByteArray(2048) { 0x55 })
        return path
    }

    @Test
    fun `reads v2_3 chapters in start order with titles`(
        @TempDir dir: Path,
    ) {
        val tag =
            Id3Builder.tag(
                3,
                listOf(
                    Id3Builder.chap("ch2", 92_000, 1_842_000, "Part One", 3),
                    Id3Builder.chap("ch1", 0, 92_000, "Advertisement", 3),
                ),
            )

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(2, chapters.size)
        assertEquals(listOf("Advertisement", "Part One"), chapters.map { it.title })
        assertEquals(0, chapters[0].startMs)
        assertEquals(92_000, chapters[0].endMs)
        assertEquals("ch1", chapters[0].elementId)
    }

    @Test
    fun `reads v2_4 chapters whose frame sizes are syncsafe`(
        @TempDir dir: Path,
    ) {
        val tag = Id3Builder.tag(4, listOf(Id3Builder.chap("ch1", 1_000, 2_000, "Sponsor", 4)))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals("Sponsor", chapters[0].title)
    }

    @Test
    fun `a chapter with no title frame keeps its times`(
        @TempDir dir: Path,
    ) {
        val tag = Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 5_000, 6_000, null, 3)))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertNull(chapters[0].title)
        assertEquals(5_000, chapters[0].startMs)
    }

    @Test
    fun `decodes titles in every text encoding`(
        @TempDir dir: Path,
    ) {
        listOf(0, 1, 2, 3).forEach { encoding ->
            val sub = Files.createDirectories(dir.resolve("enc$encoding"))
            val tag = Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 0, 1, "Werbung", 3, encoding)))

            assertEquals("Werbung", readId3Chapters(writeMp3(sub, tag))[0].title, "encoding $encoding")
        }
    }

    @Test
    fun `a file with no ID3 tag yields nothing`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve("audio.mp3")
        Files.write(path, ByteArray(4096) { 0x55 })

        assertTrue(readId3Chapters(path).isEmpty())
    }

    @Test
    fun `a truncated tag yields nothing rather than throwing`(
        @TempDir dir: Path,
    ) {
        val tag = Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 0, 1_000, "Intro", 3)))
        val path = dir.resolve("audio.mp3")
        Files.write(path, tag.copyOf(14))

        assertTrue(readId3Chapters(path).isEmpty())
    }

    @Test
    fun `a frame with a garbage huge size does not lose chapters already found`(
        @TempDir dir: Path,
    ) {
        val goodChap = Id3Builder.chap("ch1", 0, 1_000, "Intro", 3)
        // 0x7FFFFFFF: start + size would overflow Int and wrap negative if the bounds
        // check added them instead of subtracting.
        val corruptHeader =
            Id3Builder.frameHeaderWithSize(
                "TXXX".toByteArray(Charsets.ISO_8859_1),
                byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            )
        val tag = Id3Builder.tagFromBody(3, flags = 0, body = goodChap + corruptHeader)

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals("Intro", chapters[0].title)
    }

    @Test
    fun `an extended header shorter than its own length field rejects the tag`(
        @TempDir dir: Path,
    ) {
        val tag = Id3Builder.tagFromBody(3, flags = 0x40, body = byteArrayOf(0, 0))

        assertTrue(readId3Chapters(writeMp3(dir, tag)).isEmpty())
    }

    @Test
    fun `an extended header whose declared length exceeds the body rejects the tag`(
        @TempDir dir: Path,
    ) {
        // declared = 1000, far past this 14-byte body.
        val body = byteArrayOf(0, 0, 3, 232.toByte()) + ByteArray(10)
        val tag = Id3Builder.tagFromBody(3, flags = 0x40, body = body)

        assertTrue(readId3Chapters(writeMp3(dir, tag)).isEmpty())
    }

    @Test
    fun `a frame id byte in the Latin-1 uppercase range is not mistaken for ASCII`(
        @TempDir dir: Path,
    ) {
        val goodChap = Id3Builder.chap("ch1", 0, 1_000, "Intro", 3)
        // 0xC1 is 'A-grave' in Latin-1 (and upper-case under Unicode), but no real ID3
        // frame id is non-ASCII.
        val invalidId = byteArrayOf(0xC1.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte())
        val tag = Id3Builder.tagFromBody(3, flags = 0, body = goodChap + invalidId + ByteArray(6))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
    }

    @Test
    fun `survey counts every episode and reports only the chaptered ones`(
        @TempDir dir: Path,
    ) {
        val withChapters = Files.createDirectories(dir.resolve("Show/2026-01-01-aaaa1111-one"))
        writeMp3(withChapters, Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 0, 1_000, "Advertisement", 3))))

        val plain = Files.createDirectories(dir.resolve("Show/2026-01-02-bbbb2222-two"))
        Files.write(plain.resolve("audio.mp3"), ByteArray(512) { 0x55 })

        val survey = surveyAudioChapters(dir)

        assertEquals(2, survey.scanned)
        assertEquals(1, survey.withChapters)
        assertTrue(survey.chaptered[0].relativePath.endsWith("one"))
    }

    @Test
    fun `a symlinked data directory is walked, not skipped`(
        @TempDir dir: Path,
    ) {
        val real = Files.createDirectories(dir.resolve("real/Show/ep"))
        writeMp3(real, Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 0, 1_000, "Ad", 3))))
        val link = Files.createSymbolicLink(dir.resolve("link"), dir.resolve("real"))

        val survey = surveyAudioChapters(link)

        assertEquals(1, survey.scanned)
        assertEquals(1, survey.withChapters)
    }

    @Test
    fun `an unreadable subdirectory is skipped, not losing the rest of the scan`(
        @TempDir dir: Path,
    ) {
        assumeTrue(System.getProperty("user.name") != "root", "chmod 000 does not block root")

        val readable = Files.createDirectories(dir.resolve("Show/ep"))
        writeMp3(readable, Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 0, 1_000, "Ad", 3))))
        val blocked = Files.createDirectories(dir.resolve("Blocked"))
        val permissions = Files.getPosixFilePermissions(blocked)
        Files.setPosixFilePermissions(blocked, emptySet())

        try {
            val survey = surveyAudioChapters(dir)

            assertEquals(1, survey.withChapters)
            assertEquals(1, survey.unreadable)
        } finally {
            Files.setPosixFilePermissions(blocked, permissions)
        }
    }

    @Test
    fun `tally ranks titles by how many episodes carry them`() {
        val episodes =
            listOf(
                ChapteredEpisode(
                    "a",
                    listOf(
                        Id3Chapter("1", 0, 1, "Advertisement"),
                        Id3Chapter("2", 1, 2, "Advertisement"),
                        Id3Chapter("3", 2, 3, "A one-off topic"),
                    ),
                ),
                ChapteredEpisode("b", listOf(Id3Chapter("1", 0, 1, "Advertisement"))),
            )

        val tally = titleTally(episodes)

        assertEquals("Advertisement", tally[0].first)
        assertEquals(2, tally[0].second, "episodes carrying it")
        assertEquals(3, tally[0].third, "total uses")
        assertEquals("A one-off topic", tally[1].first)
    }

    @Test
    fun `report says so when a corpus carries nothing`(
        @TempDir dir: Path,
    ) {
        val episode = Files.createDirectories(dir.resolve("Show/ep"))
        Files.write(episode.resolve("audio.mp3"), ByteArray(512) { 0x55 })

        val report = audioChapterReport(surveyAudioChapters(dir), limit = 10)

        assertTrue(report.contains("with ID3 chapters: 0 (0.0%)"))
        assertTrue(report.contains("No episode in this corpus carries embedded chapters."))
    }

    @Test
    fun `report says when some paths could not be read`(
        @TempDir dir: Path,
    ) {
        assumeTrue(System.getProperty("user.name") != "root", "chmod 000 does not block root")

        val blocked = Files.createDirectories(dir.resolve("Blocked"))
        val permissions = Files.getPosixFilePermissions(blocked)
        Files.setPosixFilePermissions(blocked, emptySet())

        try {
            val report = audioChapterReport(surveyAudioChapters(dir), limit = 10)

            assertTrue(report.contains("could not read 1 path -- counts below may be incomplete"))
        } finally {
            Files.setPosixFilePermissions(blocked, permissions)
        }
    }

    @Test
    fun `a limit of 0 shows every chaptered episode, matching the other limit flags in this CLI`(
        @TempDir dir: Path,
    ) {
        (1..3).forEach { n ->
            val episode = Files.createDirectories(dir.resolve("Show/ep$n"))
            writeMp3(episode, Id3Builder.tag(3, listOf(Id3Builder.chap("ch1", 0, 1_000, "Ad", 3))))
        }

        val report = audioChapterReport(surveyAudioChapters(dir), limit = 0)

        assertTrue(report.contains("showing 3 of 3"))
    }

    @Test
    fun `report stamps spans as clock times`() {
        assertEquals("0:01:32", stamp(92_000))
        assertEquals("1:00:00", stamp(3_600_000))
    }

    @Test
    fun `v2_2 tags are rejected before frame parsing, since v2_2 cannot carry CHAP`(
        @TempDir dir: Path,
    ) {
        val tag = Id3Builder.tag(2, listOf(Id3Builder.chap("ch1", 0, 1_000, "Ad", 3)))

        assertTrue(readId3Chapters(writeMp3(dir, tag)).isEmpty())
    }

    @Test
    fun `unsynchronised tag bytes are restored before frames are parsed`(
        @TempDir dir: Path,
    ) {
        val body = Id3Builder.concat(listOf(Id3Builder.chap("ch1", 0, 1_000, "AÿB", 3, encoding = 0)))
        val tag = Id3Builder.tagFromBody(3, flags = 0x80, body = unsynchronise(body))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals("AÿB", chapters[0].title)
    }

    @Test
    fun `a v2_3 extended header is skipped using its declared size plus its own length bytes`(
        @TempDir dir: Path,
    ) {
        val body = Id3Builder.extendedHeaderV23(fillerSize = 6) + Id3Builder.chap("ch1", 0, 1_000, "Ad", 3)
        val tag = Id3Builder.tagFromBody(3, flags = 0x40, body = body)

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals("Ad", chapters[0].title)
    }

    @Test
    fun `a v2_4 extended header is skipped using its declared size, which already counts its length bytes`(
        @TempDir dir: Path,
    ) {
        val body = Id3Builder.extendedHeaderV24(fillerSize = 6) + Id3Builder.chap("ch1", 0, 1_000, "Ad", 4)
        val tag = Id3Builder.tagFromBody(4, flags = 0x40, body = body)

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals("Ad", chapters[0].title)
    }

    @Test
    fun `a v2_4 frame size falls back to plain when the syncsafe reading misses the next frame`(
        @TempDir dir: Path,
    ) {
        // 300 bytes: a plain size whose top two bytes are still valid (small) syncsafe bytes,
        // so both readings look plausible and only the boundary check picks the right one.
        val title = "x".repeat(269)
        val content = Id3Builder.chapContent("ch1", 0, 1_000, title, 4)
        assertEquals(300, content.size)

        val tag = Id3Builder.tag(4, listOf(Id3Builder.frameWithPlainSize("CHAP", content)))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals(title, chapters[0].title)
    }

    @Test
    fun `a v2_4 frame size falls back to plain when the syncsafe reading is unrepresentable`(
        @TempDir dir: Path,
    ) {
        // 200 bytes: 0x000000C8 has its high bit set, so syncSafe rejects it outright (-1)
        // rather than returning a wrong-but-plausible number, the other half of the fallback.
        val title = "x".repeat(169)
        val content = Id3Builder.chapContent("ch1", 0, 1_000, title, 4)
        assertEquals(200, content.size)

        val tag = Id3Builder.tag(4, listOf(Id3Builder.frameWithPlainSize("CHAP", content)))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals(title, chapters[0].title)
    }

    @Test
    fun `a v2_4 extended header whose length is written plain rejects the tag rather than misparsing it`(
        @TempDir dir: Path,
    ) {
        // 0x00 0x00 0x00 0x80 (128, plain) has its high bit set, so syncSafe rejects it (-1);
        // the tag must be given up on rather than parsed from an unresolvable offset.
        val extendedHeader = byteArrayOf(0, 0, 0, 0x80.toByte()) + ByteArray(124)
        val body = extendedHeader + Id3Builder.chap("ch1", 0, 1_000, "Ad", 4)
        val tag = Id3Builder.tagFromBody(4, flags = 0x40, body = body)

        assertTrue(readId3Chapters(writeMp3(dir, tag)).isEmpty())
    }

    @Test
    fun `a zero-length frame is skipped rather than ending the scan`(
        @TempDir dir: Path,
    ) {
        val empty = Id3Builder.frame("TXXX", ByteArray(0), 3)
        val tag = Id3Builder.tag(3, listOf(empty, Id3Builder.chap("ch1", 0, 1_000, "Ad", 3)))

        val chapters = readId3Chapters(writeMp3(dir, tag))

        assertEquals(1, chapters.size)
        assertEquals("Ad", chapters[0].title)
    }
}
