package com.rsstowhisper.audio

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val AUDIO_FILENAME = "audio.mp3"

data class ChapteredEpisode(
    val relativePath: String,
    val chapters: List<Id3Chapter>,
)

data class ChapterSurvey(
    val scanned: Int,
    val chaptered: List<ChapteredEpisode>,
) {
    val withChapters: Int get() = chaptered.size
}

/** Reads the ID3 tag of every `audio.mp3` under [dataDirectory]. Never decodes audio. */
fun surveyAudioChapters(dataDirectory: Path): ChapterSurvey {
    var scanned = 0
    val chaptered = mutableListOf<ChapteredEpisode>()

    Files.walk(dataDirectory).use { paths ->
        paths
            .filter { Files.isRegularFile(it) && it.name == AUDIO_FILENAME }
            .forEach { path ->
                scanned++
                val chapters = readId3Chapters(path)
                if (chapters.isNotEmpty()) {
                    val relative = dataDirectory.relativize(path.parent ?: path).toString()
                    chaptered += ChapteredEpisode(relative, chapters)
                }
            }
    }
    return ChapterSurvey(scanned, chaptered.sortedBy { it.relativePath })
}

/**
 * A title carried by many episodes is a structural segment -- an intro, a break, a sponsor
 * read. A title carried once is that episode's content. Sorting by episode count is what
 * separates them, so it is the ordering the tally uses.
 */
internal fun titleTally(chaptered: List<ChapteredEpisode>): List<Triple<String, Int, Int>> {
    val occurrences = mutableMapOf<String, Int>()
    val episodes = mutableMapOf<String, MutableSet<String>>()

    chaptered.forEach { episode ->
        episode.chapters.forEach { chapter ->
            val title = chapter.title ?: return@forEach
            occurrences.merge(title, 1, Int::plus)
            episodes.getOrPut(title) { mutableSetOf() } += episode.relativePath
        }
    }

    return occurrences
        .map { (title, count) -> Triple(title, episodes[title]?.size ?: 0, count) }
        .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.first })
}

fun audioChapterReport(
    survey: ChapterSurvey,
    limit: Int,
): String {
    val out = StringBuilder()
    out.appendLine("scanned $AUDIO_FILENAME in ${survey.scanned} episodes")

    if (survey.scanned == 0) {
        out.appendLine("nothing found -- is this the data directory?")
        return out.toString()
    }

    val percent = survey.withChapters * 100.0 / survey.scanned
    out.appendLine("with ID3 chapters: ${survey.withChapters} (${"%.1f".format(percent)}%)")

    if (survey.withChapters == 0) {
        out.appendLine()
        out.appendLine("No episode in this corpus carries embedded chapters.")
        return out.toString()
    }

    val shown = survey.chaptered.take(limit)
    out.appendLine()
    out.appendLine("showing ${shown.size} of ${survey.withChapters}")

    shown.forEach { episode ->
        out.appendLine()
        out.appendLine(episode.relativePath)
        episode.chapters.forEach { chapter ->
            val span = "${stamp(chapter.startMs)}-${stamp(chapter.endMs)}"
            out.appendLine("  $span  ${chapter.title ?: "(untitled)"}")
        }
    }

    out.appendLine()
    out.appendLine("chapter titles by episodes carrying them:")
    titleTally(survey.chaptered).forEach { (title, episodes, occurrences) ->
        out.appendLine("  $episodes episodes, $occurrences uses  $title")
    }
    return out.toString()
}

internal fun stamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, seconds)
}
