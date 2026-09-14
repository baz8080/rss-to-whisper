package com.rsstowhisper.audio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds the tag bytes a tagger would write, so the parser is tested against real layout. */
private object Id3Builder {
    fun tag(
        major: Int,
        frames: List<ByteArray>,
    ): ByteArray {
        val body = frames.fold(ByteArrayOutputStream()) { acc, f -> acc.apply { write(f) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major)
        out.write(0)
        out.write(0)
        out.write(syncSafeBytes(body.size))
        out.write(body)
        return out.toByteArray()
    }

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
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(elementId.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(plainBytes(startMs))
        out.write(plainBytes(endMs))
        out.write(plainBytes(0))
        out.write(plainBytes(0))
        if (titleText != null) out.write(title(titleText, major, encoding))
        return frame("CHAP", out.toByteArray(), major)
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
    fun `report stamps spans as clock times`() {
        assertEquals("0:01:32", stamp(92_000))
        assertEquals("1:00:00", stamp(3_600_000))
    }
}
