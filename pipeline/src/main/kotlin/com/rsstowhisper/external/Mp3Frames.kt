package com.rsstowhisper.external

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** Frames of an mp3 cut from a longer one, and the time in the whole file of the first sample they decode to. */
class Mp3Clip(
    val bytes: ByteArray,
    val start: Double,
)

/**
 * Where each Layer III frame of an mp3 sits, timed as whisper.cpp's decoder (dr_mp3) times the whole file: from the frame
 * after a Xing/Info frame, less the LAME delay. A stretch sent alone is all whisper hears; with offset_t it hears 30 s.
 */
class Mp3Frames private constructor(
    private val path: Path,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val mainDataBegin: IntArray,
    private val mainDataBytes: IntArray,
    private val samplesPerFrame: Int,
    private val sampleRate: Int,
    private val delay: Int,
) {
    val frames: Int get() = offsets.size

    val duration: Double get() = time(offsets.size)

    /** Seconds into the whole file's decode at which frame [i]'s first sample plays. */
    fun time(i: Int): Double = (i.toLong() * samplesPerFrame - delay).toDouble() / sampleRate

    /** The frames covering [window], and enough before it to fill the bit reservoir its first frame draws on. */
    fun clip(window: TimeWindow): Mp3Clip? {
        if (offsets.isEmpty()) return null
        val first = frameAt(window.start)
        val last = minOf(frameAt(window.end) + 1, offsets.lastIndex)
        var from = first
        var decodes = firstDecodable(from)
        while (decodes > first && from > 0) {
            from = maxOf(0, from - RESERVOIR_FRAMES)
            decodes = firstDecodable(from)
        }
        val size = (offsets[last] + lengths[last] - offsets[from]).toInt()
        val bytes = ByteArray(size)
        RandomAccessFile(path.toFile(), "r").use {
            it.seek(offsets[from])
            it.readFully(bytes)
        }
        return Mp3Clip(bytes, time(decodes))
    }

    private fun frameAt(seconds: Double): Int = ((seconds * sampleRate + delay) / samplesPerFrame).toInt().coerceIn(0, offsets.lastIndex)

    /** dr_mp3 skips a frame whose main data begins in bytes it never saw, and keeps them for the next one. */
    private fun firstDecodable(from: Int): Int {
        var reservoir = 0
        for (i in from until offsets.size) {
            if (reservoir >= mainDataBegin[i]) return i
            reservoir = minOf(MAX_RESERVOIR_BYTES, reservoir + mainDataBytes[i])
        }
        return offsets.lastIndex
    }

    private class Header(
        val version: Int,
        val sampleRate: Int,
        val samples: Int,
        val length: Int,
        val sideInfoStart: Int,
        val sideInfoLength: Int,
    ) {
        val mpeg1: Boolean get() = version == MPEG1
    }

    companion object {
        private const val MPEG1 = 3
        private const val MAX_RESERVOIR_BYTES = 511
        private const val RESERVOIR_FRAMES = 8
        private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val RATES = intArrayOf(44100, 48000, 32000)

        /** Null for anything but a Layer III stream of one sample rate throughout: those go up whole, with offset_t. */
        fun of(path: Path): Mp3Frames? = runCatching { parse(path, Files.readAllBytes(path)) }.getOrNull()

        internal fun parse(
            path: Path,
            b: ByteArray,
        ): Mp3Frames? {
            val offsets = ArrayList<Long>()
            val lengths = ArrayList<Int>()
            val begins = ArrayList<Int>()
            val mains = ArrayList<Int>()
            var stream: Header? = null
            var delay = 0
            var p = id3End(b)
            while (p + 4 <= b.size) {
                val h = header(b, p)
                val next = h?.let { header(b, p + it.length) }
                val chained = h != null && (p + h.length == b.size || (next != null && next.sampleRate == h.sampleRate))
                if (h == null || !chained || (stream == null && next?.let { header(b, p + h.length + it.length) } == null)) {
                    p++
                    continue
                }
                if (stream == null) {
                    stream = h
                    val lame = infoFrameDelay(b, p, h)
                    if (lame != null) {
                        delay = lame
                        p += h.length
                        continue
                    }
                } else if (h.sampleRate != stream.sampleRate || h.version != stream.version) {
                    return null
                }
                val side = p + h.sideInfoStart
                offsets += p.toLong()
                lengths += h.length
                begins += if (h.mpeg1) ((u(b, side) shl 1) or (u(b, side + 1) shr 7)) else u(b, side)
                mains += h.length - h.sideInfoStart - h.sideInfoLength
                p += h.length
            }
            if (stream == null || offsets.isEmpty()) return null
            return Mp3Frames(
                path,
                offsets.toLongArray(),
                lengths.toIntArray(),
                begins.toIntArray(),
                mains.toIntArray(),
                stream.samples,
                stream.sampleRate,
                delay,
            )
        }

        private fun u(
            b: ByteArray,
            i: Int,
        ) = b[i].toInt() and 0xFF

        private fun id3End(b: ByteArray): Int {
            if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return 0
            val size = (u(b, 6) shl 21) or (u(b, 7) shl 14) or (u(b, 8) shl 7) or u(b, 9)
            val footer = if (u(b, 5) and 0x10 != 0) 10 else 0
            return 10 + size + footer
        }

        private fun header(
            b: ByteArray,
            p: Int,
        ): Header? {
            if (p < 0 || p + 4 > b.size) return null
            val b1 = u(b, p + 1)
            val b2 = u(b, p + 2)
            val b3 = u(b, p + 3)
            if (u(b, p) != 0xFF || b1 and 0xE0 != 0xE0) return null
            val version = (b1 shr 3) and 3
            val layer = (b1 shr 1) and 3
            val bitrateIndex = (b2 shr 4) and 0xF
            val rateIndex = (b2 shr 2) and 3
            if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
            val mpeg1 = version == MPEG1
            val bitrate = (if (mpeg1) BITRATES_V1 else BITRATES_V2)[bitrateIndex] * 1000
            val rate =
                RATES[rateIndex] / (
                    if (mpeg1) {
                        1
                    } else if (version == 2) {
                        2
                    } else {
                        4
                    }
                )
            val samples = if (mpeg1) 1152 else 576
            val length = samples / 8 * bitrate / rate + ((b2 shr 1) and 1)
            val mono = (b3 shr 6) and 3 == 3
            val sideInfo = if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
            val sideStart = 4 + if (b1 and 1 == 0) 2 else 0
            if (length < sideStart + sideInfo) return null
            return Header(version, rate, samples, length, sideStart, sideInfo)
        }

        /** The delay dr_mp3 trims when the first frame is a Xing/Info frame, which it also skips; null if it is not one. */
        private fun infoFrameDelay(
            b: ByteArray,
            p: Int,
            h: Header,
        ): Int? {
            val tag = p + h.sideInfoStart + h.sideInfoLength
            if (tag + 8 > p + h.length) return null
            val name = String(b, tag, 4, Charsets.ISO_8859_1)
            if (name != "Xing" && name != "Info") return null
            val flags = u(b, tag + 7)
            var q = tag + 8
            if (flags and 1 != 0) q += 4
            if (flags and 2 != 0) q += 4
            if (flags and 4 != 0) q += 100
            if (flags and 8 != 0) q += 4
            if (q >= p + h.length || u(b, q) == 0) return 0
            q += 21
            if (q - p + 14 >= h.length) return 0
            return ((u(b, q) shl 4) or (u(b, q + 1) shr 4)) + 529
        }
    }
}
