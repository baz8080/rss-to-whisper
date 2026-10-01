package com.rsstowhisper.external

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp3FramesTest {
    private companion object {
        const val FRAME_LENGTH = 417
        const val SAMPLES = 1152
        const val RATE = 44100

        // Inside the main data, clear of the header, the side info and an Info tag.
        const val MARKER_AT = 40
        const val LAME_DELAY_PLUS = 529
        val HEADER_44K = intArrayOf(0xFF, 0xFB, 0x90, 0x64)
        val HEADER_48K = intArrayOf(0xFF, 0xFB, 0x94, 0x64)
        val HEADER_LAYER_II = intArrayOf(0xFF, 0xFD, 0x90, 0x64)
    }

    private fun frame(
        marker: Int,
        mainDataBegin: Int = 0,
        header: IntArray = HEADER_44K,
        length: Int = FRAME_LENGTH,
    ): ByteArray {
        val f = ByteArray(length)
        header.forEachIndexed { i, v -> f[i] = v.toByte() }
        f[4] = (mainDataBegin shr 1).toByte()
        f[5] = ((mainDataBegin and 1) shl 7).toByte()
        f[MARKER_AT] = marker.toByte()
        return f
    }

    private fun stream(
        count: Int,
        first: Int = 0,
        mainDataBegin: (Int) -> Int = { 0 },
    ): ByteArray = (first until first + count).flatMap { frame(it, mainDataBegin(it)).asList() }.toByteArray()

    private fun infoFrame(
        name: String = "Info",
        encoderDelay: Int? = null,
    ): ByteArray {
        val f = frame(0)
        val tag = 4 + 32
        name.forEachIndexed { i, c -> f[tag + i] = c.code.toByte() }
        f[tag + 7] = 0x03
        if (encoderDelay != null) {
            val lame = tag + 8 + 8
            "LAME3.99r".forEachIndexed { i, c -> f[lame + i] = c.code.toByte() }
            f[lame + 21] = (encoderDelay shr 4).toByte()
            f[lame + 22] = ((encoderDelay and 0xF) shl 4).toByte()
        }
        return f
    }

    private fun id3(body: ByteArray): ByteArray {
        val size = body.size
        val header =
            byteArrayOf(
                'I'.code.toByte(),
                'D'.code.toByte(),
                '3'.code.toByte(),
                3,
                0,
                0,
                ((size shr 21) and 0x7F).toByte(),
                ((size shr 14) and 0x7F).toByte(),
                ((size shr 7) and 0x7F).toByte(),
                (size and 0x7F).toByte(),
            )
        return header + body
    }

    private fun parse(
        tmp: Path,
        bytes: ByteArray,
    ): Mp3Frames? {
        val path = tmp.resolve("episode.mp3")
        Files.write(path, bytes)
        return Mp3Frames.parse(path, bytes)
    }

    private fun indexed(
        tmp: Path,
        bytes: ByteArray,
    ): Mp3Frames = assertNotNull(parse(tmp, bytes))

    private fun seconds(
        frame: Int,
        delay: Int = 0,
    ): Double = (frame.toLong() * SAMPLES - delay).toDouble() / RATE

    // A few ms into the frame, so float rounding cannot land the window on the frame before.
    private fun Mp3Frames.window(
        from: Int,
        to: Int,
    ) = TimeWindow(time(from) + 0.005, time(to) + 0.005)

    private fun Mp3Frames.firstFrameMarker(window: TimeWindow): Int = assertNotNull(clip(window)).bytes[MARKER_AT].toInt()

    @Test
    fun `frames counts every Layer III frame of the stream`(
        @TempDir tmp: Path,
    ) {
        assertEquals(6, indexed(tmp, stream(6)).frames)
    }

    @Test
    fun `frame i starts at i times 1152 over 44100 seconds`(
        @TempDir tmp: Path,
    ) {
        val mp3 = indexed(tmp, stream(6))
        for (i in 0 until mp3.frames) {
            assertEquals(i * 1152 / 44100.0, mp3.time(i), 1e-9, "frame $i")
        }
    }

    @Test
    fun `a leading ID3v2 tag is skipped by its syncsafe size`(
        @TempDir tmp: Path,
    ) {
        // Two frames' worth of tag, so a size read without syncsafe decoding lands mid-frame.
        val mp3 = indexed(tmp, id3(stream(2, first = 90)) + stream(4, first = 10))
        assertEquals(4, mp3.frames)
        assertEquals(10, mp3.firstFrameMarker(mp3.window(0, 0)))
        assertEquals(0.0, mp3.time(0))
    }

    @Test
    fun `a Xing or Info first frame is not counted and adds no delay without a LAME tag`(
        @TempDir tmp: Path,
    ) {
        for (name in listOf("Xing", "Info")) {
            val mp3 = indexed(tmp, infoFrame(name) + stream(4, first = 10))
            assertEquals(4, mp3.frames, name)
            assertEquals(10, mp3.firstFrameMarker(mp3.window(0, 0)), name)
            assertEquals(0.0, mp3.time(0), name)
        }
    }

    @Test
    fun `the LAME encoder delay plus 529 shifts every frame time earlier`(
        @TempDir tmp: Path,
    ) {
        val encoderDelay = 576
        val mp3 = indexed(tmp, infoFrame(encoderDelay = encoderDelay) + stream(4))
        val delay = encoderDelay + LAME_DELAY_PLUS
        assertEquals(4, mp3.frames)
        for (i in 0 until mp3.frames) {
            assertEquals(seconds(i, delay), mp3.time(i), 1e-9, "frame $i")
        }
        assertEquals(-delay / 44100.0, mp3.time(0), 1e-9)
    }

    @Test
    fun `garbage between two runs of frames is skipped and the frame before it is lost`(
        @TempDir tmp: Path,
    ) {
        val garbage = ByteArray(100) { 0x55 }
        val mp3 = indexed(tmp, stream(3) + garbage + stream(3, first = 10))
        assertEquals(5, mp3.frames)
        assertEquals(listOf(0, 1, 10, 11, 12), (0 until mp3.frames).map { mp3.firstFrameMarker(mp3.window(it, it)) })
    }

    @Test
    fun `a sample rate change mid-stream gives null`(
        @TempDir tmp: Path,
    ) {
        val at48k = (0 until 3).flatMap { frame(it, header = HEADER_48K, length = 384).asList() }.toByteArray()
        assertNull(parse(tmp, stream(3) + at48k))
    }

    @Test
    fun `a stream that is not Layer III gives null`(
        @TempDir tmp: Path,
    ) {
        val layerII = (0 until 5).flatMap { frame(it, header = HEADER_LAYER_II).asList() }.toByteArray()
        assertNull(parse(tmp, layerII))
    }

    @Test
    fun `clip returns the bytes from the first frame to the one after the window's last`(
        @TempDir tmp: Path,
    ) {
        val bytes = stream(20)
        val mp3 = indexed(tmp, bytes)
        val clip = assertNotNull(mp3.clip(mp3.window(5, 8)))
        assertContentEquals(bytes.copyOfRange(5 * FRAME_LENGTH, 10 * FRAME_LENGTH), clip.bytes)
        assertEquals(mp3.time(5), clip.start)
    }

    @Test
    fun `clip stops at the last frame when the window runs past the end`(
        @TempDir tmp: Path,
    ) {
        val bytes = stream(10)
        val mp3 = indexed(tmp, bytes)
        val clip = assertNotNull(mp3.clip(TimeWindow(mp3.time(8) + 0.005, mp3.time(9) + 100.0)))
        assertContentEquals(bytes.copyOfRange(8 * FRAME_LENGTH, 10 * FRAME_LENGTH), clip.bytes)
    }

    @Test
    fun `clip is cut at file offsets and timed less the delay after a tag and an Info frame`(
        @TempDir tmp: Path,
    ) {
        val prefix = id3(ByteArray(300)) + infoFrame(encoderDelay = 576)
        val bytes = prefix + stream(12, first = 20)
        val mp3 = indexed(tmp, bytes)
        val clip = assertNotNull(mp3.clip(mp3.window(3, 4)))
        val from = prefix.size + 3 * FRAME_LENGTH
        assertContentEquals(bytes.copyOfRange(from, from + 3 * FRAME_LENGTH), clip.bytes)
        assertEquals(seconds(3, 576 + LAME_DELAY_PLUS), clip.start, 1e-9)
    }

    @Test
    fun `clip starts before the window when a frame's main data begins before the clip`(
        @TempDir tmp: Path,
    ) {
        val mp3 = indexed(tmp, stream(30) { 100 })
        val window = mp3.window(20, 22)
        val clip = assertNotNull(mp3.clip(window))
        val from = clip.bytes[MARKER_AT].toInt()
        assertTrue(from < 20, "clip begins at frame $from")
        assertEquals(mp3.time(from + 1), clip.start)
        assertTrue(clip.start <= window.start)
    }

    @Test
    fun `clip steps back further when one frame of reservoir is not enough`(
        @TempDir tmp: Path,
    ) {
        val mp3 = indexed(tmp, stream(30) { 400 })
        val window = mp3.window(20, 22)
        val clip = assertNotNull(mp3.clip(window))
        val from = clip.bytes[MARKER_AT].toInt()
        assertTrue(from < 20, "clip begins at frame $from")
        assertEquals(mp3.time(from + 2), clip.start)
        assertTrue(clip.start <= window.start)
    }

    @Test
    fun `clip cannot step back past the first frame`(
        @TempDir tmp: Path,
    ) {
        val mp3 = indexed(tmp, stream(10) { 511 })
        val clip = assertNotNull(mp3.clip(mp3.window(1, 2)))
        assertEquals(0, clip.bytes[MARKER_AT].toInt())
        assertEquals(mp3.time(2), clip.start)
    }
}
