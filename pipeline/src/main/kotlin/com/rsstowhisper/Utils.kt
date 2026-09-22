package com.rsstowhisper

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.math.pow

private val logger = LoggerFactory.getLogger("com.rsstowhisper.Utils")

private val CONSECUTIVE_DASHES = Regex("-{2,}")
private val COMBINING_MARKS = Regex("\\p{M}+")

/**
 * Accents are stripped rather than kept: a feed sends `ú` composed or as `u` + a combining
 * mark, and the mark is not a letter, so the same title used to slug two different ways.
 */
fun escapeFilename(filename: String?): String {
    if (filename.isNullOrEmpty()) return ""

    val escaped =
        Normalizer.normalize(filename, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(CONSECUTIVE_DASHES, "-")

    return if (escaped.endsWith("-")) escaped.dropLast(1) else escaped
}

fun timeToSeconds(timeStr: String?): Int {
    if (timeStr.isNullOrBlank()) return 0

    return try {
        val parts = timeStr.split(":")
        parts.reversed().mapIndexed { i, part ->
            val value = part.toDoubleOrNull() ?: 0.0
            value * 60.0.pow(i)
        }.sum().toInt()
    } catch (_: Exception) {
        logger.error("Couldn't parse $timeStr into seconds")
        0
    }
}

/** Where [createPath] would put it, without creating anything. */
fun resolvePath(
    parentPath: Path,
    directoryName: String,
): Path {
    val escaped = escapeFilename(directoryName)
    return findCaseInsensitive(parentPath, escaped) ?: parentPath.resolve(escaped)
}

fun createPath(
    parentPath: Path,
    directoryName: String,
): Path = resolvePath(parentPath, directoryName).also { Files.createDirectories(it) }

/**
 * `escapeFilename` preserves case, so re-capitalising a podcast's name in
 * `pods.yaml` used to produce a SECOND directory for the same show. It
 * happened: one feed shipped as both `HBR-Ideacast` (635 episodes) and
 * `HBR-IdeaCast` (34). Downstream, train/test splits group on the first path
 * component, so one show sat on both sides of the split boundary and leaked
 * its hosts, jingles and recurring sponsors across it.
 *
 * Reusing the existing directory rather than case-folding the stored name:
 * 17,750 directories exist, and renaming them is a migration in its own right.
 * The defect is that a second directory can appear, not that the first has
 * capitals. An accented directory from before accents were stripped matches the same way.
 */
private fun findCaseInsensitive(
    parentPath: Path,
    name: String,
): Path? {
    if (!Files.isDirectory(parentPath)) return null
    val match =
        parentPath.toFile()
            .listFiles()
            ?.firstOrNull { it.isDirectory && escapeFilename(it.name).equals(name, ignoreCase = true) }
            ?: return null
    if (match.name != name) {
        logger.info("Reusing existing directory ${match.name} for $name (differs only by case or accents)")
    }
    return match.toPath()
}
