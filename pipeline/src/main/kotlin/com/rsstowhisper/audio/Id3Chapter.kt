package com.rsstowhisper.audio

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** One CHAP frame: a labelled span of the audio file it was read from. */
data class Id3Chapter(
    val elementId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String?,
)

private const val HEADER_SIZE = 10

/** The ID3 spec's "value unused" sentinel, not a 49-day offset. */
private const val TIME_UNUSED = 0xFFFFFFFFL

private val logger = LoggerFactory.getLogger(Id3Chapter::class.java)

/** Seconds, not the tag's milliseconds: every other time in `transcript.json` is seconds. */
fun Id3Chapter.toSecondsMap(): Map<String, Any?> =
    mapOf(
        "start_s" to startMs / 1000.0,
        "end_s" to endMs / 1000.0,
        "title" to title,
    )

/** False for times no consumer can use, so publishing them cannot pass off a sentinel as a span. */
fun Id3Chapter.hasUsableTimes(): Boolean {
    val usable = startMs != TIME_UNUSED && endMs != TIME_UNUSED && endMs >= startMs
    if (!usable) logger.warn("Dropping chapter $elementId: unusable times ${startMs}ms-${endMs}ms")
    return usable
}

private class Frame(val id: String, val body: ByteArray)

private class Tag(val major: Int, val body: ByteArray)

/** A tag that is present and cannot be parsed, which is not the same as a file carrying none. */
private class Id3Unreadable(reason: String) : Exception(reason)

/**
 * Chapters in [path]'s ID3v2 tag; reads only the tag, never the audio. Empty for no tag or no
 * chapters, null for a file this could not read -- which anything publishing the result has to
 * tell apart from an episode that genuinely carries none.
 */
fun readId3ChaptersOrNull(path: Path): List<Id3Chapter>? =
    try {
        val tag = readTag(path)
        if (tag == null) {
            emptyList()
        } else {
            parseFrames(tag.body, tag.major) { it == "CHAP" }
                .mapNotNull { parseChap(it.body, tag.major) }
                .sortedBy { it.startMs }
        }
    } catch (e: Exception) {
        logger.warn("Could not read the ID3 tag of $path: ${e.message}")
        null
    }

/** Empty for an unreadable file too: one odd tag must not fail a whole corpus scan. */
fun readId3Chapters(path: Path): List<Id3Chapter> = readId3ChaptersOrNull(path).orEmpty()

private fun readTag(path: Path): Tag? {
    Files.newInputStream(path).use { input ->
        val header = ByteArray(HEADER_SIZE)
        if (input.readNBytes(header, 0, HEADER_SIZE) < HEADER_SIZE) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }

        val major = header[3].toInt() and 0xFF
        // CHAP was added in the v2.3 era; v2.2's 3-byte frame ids cannot carry it.
        if (major < 3) return null

        val flags = header[5].toInt() and 0xFF
        val declared = syncSafe(header, 6)
        if (declared <= 0) return null

        // A tag may declare 256MB. Allocating that on a corrupt header raises OutOfMemoryError,
        // which is an Error: not caught below, and fatal to the whole run.
        val size = minOf(declared.toLong(), Files.size(path) - HEADER_SIZE).toInt()
        if (size <= 0) return null

        var body = ByteArray(size)
        val read = input.readNBytes(body, 0, size)
        if (read < size) body = body.copyOf(read)

        if (flags and 0x80 != 0) body = deUnsynchronise(body)
        if (flags and 0x40 != 0) {
            body = skipExtendedHeader(body, major) ?: throw Id3Unreadable("its extended header hides where the frames start")
        }
        return Tag(major, body)
    }
}

/** 0xFF 0x00 was inserted so the tag could never look like an audio frame sync. */
private fun deUnsynchronise(body: ByteArray): ByteArray {
    val out = ByteArray(body.size)
    var w = 0
    var r = 0
    while (r < body.size) {
        out[w++] = body[r]
        if (body[r] == 0xFF.toByte() && r + 1 < body.size && body[r + 1] == 0x00.toByte()) r++
        r++
    }
    return out.copyOf(w)
}

/** Null whenever the frame area can't be located, rather than guessing an offset. */
private fun skipExtendedHeader(
    body: ByteArray,
    major: Int,
): ByteArray? {
    if (body.size < 4) return null
    // v2.4 counts the four length bytes in the length; v2.3 does not.
    val declared = if (major >= 4) syncSafe(body, 0) else plainInt(body, 0)
    if (declared < 0) return null
    val skip = if (major >= 4) declared else declared + 4
    return if (skip in 1..body.size) body.copyOfRange(skip, body.size) else null
}

/** [keep] is applied before the body is copied: cover art is megabytes, and never wanted here. */
private fun parseFrames(
    body: ByteArray,
    major: Int,
    keep: (String) -> Boolean,
): List<Frame> {
    val frames = mutableListOf<Frame>()
    var i = 0
    while (i + HEADER_SIZE <= body.size) {
        if (body[i] == 0.toByte()) break // padding

        val id = String(body, i, 4, Charsets.ISO_8859_1)
        if (!looksLikeFrameId(body, i)) break

        val size = frameSize(body, i + 4, major)
        val start = i + HEADER_SIZE
        // start <= body.size is guaranteed by the loop condition, so this cannot overflow
        // the way `start + size > body.size` would for a huge garbage size.
        if (size < 0 || size > body.size - start) break

        val format = frameFormat(body[i + 9].toInt() and 0xFF, major)
        if (keep(id) && format.readable && format.prefix <= size) {
            val content = body.copyOfRange(start + format.prefix, start + size)
            frames += Frame(id, if (format.unsynchronised) deUnsynchronise(content) else content)
        }
        i = start + size
    }
    return frames
}

/** What a frame's format flags put in front of its content, and whether the content is readable. */
private class FrameFormat(
    val prefix: Int,
    val readable: Boolean,
    val unsynchronised: Boolean,
)

/**
 * Each flag that carries extra data prefixes the frame content with it, so a flag read as content
 * shifts every byte after it. Compressed and encrypted frames are given up on rather than decoded.
 */
private fun frameFormat(
    flags: Int,
    major: Int,
): FrameFormat =
    if (major >= 4) {
        FrameFormat(
            prefix = (if (flags and 0x40 != 0) 1 else 0) + (if (flags and 0x04 != 0) 1 else 0) + (if (flags and 0x01 != 0) 4 else 0),
            readable = flags and 0x0C == 0,
            unsynchronised = flags and 0x02 != 0,
        )
    } else {
        FrameFormat(
            prefix = (if (flags and 0x80 != 0) 4 else 0) + (if (flags and 0x40 != 0) 1 else 0) + (if (flags and 0x20 != 0) 1 else 0),
            readable = flags and 0xC0 == 0,
            unsynchronised = false,
        )
    }

/**
 * v2.4 frame sizes are syncsafe, but some taggers write plain ones. An unreadable syncsafe
 * value falls back to plain; otherwise syncsafe wins unless only plain lands on the next frame.
 */
private fun frameSize(
    body: ByteArray,
    at: Int,
    major: Int,
): Int {
    val plain = plainInt(body, at)
    if (major < 4) return plain

    val safe = syncSafe(body, at)
    if (safe < 0) return plain
    return if (landsOnFrame(body, at + 6 + safe) || !landsOnFrame(body, at + 6 + plain)) safe else plain
}

private fun landsOnFrame(
    body: ByteArray,
    at: Int,
): Boolean {
    if (at == body.size) return true
    if (at < 0 || at + 4 > body.size) return false
    if (body[at] == 0.toByte()) return true
    return looksLikeFrameId(body, at)
}

/** A real frame id is always four ASCII A-Z/0-9 bytes -- unsigned, not Unicode-classified. */
private fun looksLikeFrameId(
    body: ByteArray,
    at: Int,
): Boolean =
    (0..3).all {
        val b = body[at + it].toInt() and 0xFF
        b in 'A'.code..'Z'.code || b in '0'.code..'9'.code
    }

private fun parseChap(
    body: ByteArray,
    major: Int,
): Id3Chapter? {
    val terminator = body.indexOfFirst { it == 0.toByte() }
    if (terminator < 0) return null

    val elementId = String(body, 0, terminator, Charsets.ISO_8859_1)
    val timesAt = terminator + 1
    if (timesAt + 16 > body.size) return null

    val startMs = plainInt(body, timesAt).toLong() and 0xFFFFFFFFL
    val endMs = plainInt(body, timesAt + 4).toLong() and 0xFFFFFFFFL

    val title =
        parseFrames(body.copyOfRange(timesAt + 16, body.size), major) { it == "TIT2" }
            .firstOrNull()
            ?.let { decodeText(it.body) }

    return Id3Chapter(elementId, startMs, endMs, title)
}

private fun decodeText(body: ByteArray): String? {
    if (body.isEmpty()) return null
    val text = body.copyOfRange(1, body.size)
    val decoded =
        when (body[0].toInt()) {
            0 -> String(text, Charsets.ISO_8859_1)
            1 -> String(text, Charsets.UTF_16)
            2 -> String(text, Charsets.UTF_16BE)
            3 -> String(text, Charsets.UTF_8)
            else -> return null
        }
    return decoded.trimEnd(Char(0)).trim().takeIf { it.isNotEmpty() }
}

private fun syncSafe(
    bytes: ByteArray,
    at: Int,
): Int {
    if (at + 4 > bytes.size) return -1
    var value = 0
    for (i in 0..3) {
        val b = bytes[at + i].toInt() and 0xFF
        if (b and 0x80 != 0) return -1
        value = (value shl 7) or b
    }
    return value
}

private fun plainInt(
    bytes: ByteArray,
    at: Int,
): Int {
    if (at + 4 > bytes.size) return -1
    var value = 0
    for (i in 0..3) {
        value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
    }
    return value
}
