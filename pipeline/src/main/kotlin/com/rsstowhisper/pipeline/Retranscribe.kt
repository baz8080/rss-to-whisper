package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * What a re-transcription run was asked to redo.
 *
 * Any non-empty selection puts the run in re-transcription mode, where no feed
 * is fetched at all: the targets are already on disk and the feed has nothing
 * left to say about them.
 */
data class RetranscribeRequest(
    /** `<podcast dir>/<episode dir>`, relative to the data directory. */
    val paths: List<String> = emptyList(),
    /** The `<hex8>` in an episode directory name, which is also its `_id`. */
    val ids: List<String> = emptyList(),
    /** Every episode whose recorded `episode_quality.flags` is non-empty. */
    val flagged: Boolean = false,
    /** Caps the flagged scan, which can select hundreds on a first run. 0 means no limit. */
    val limit: Int = 0,
) {
    val isRequested: Boolean get() = paths.isNotEmpty() || ids.isNotEmpty() || flagged
}

/**
 * Finds the episode directories a [RetranscribeRequest] names.
 *
 * Separate from the decoding so the selection -- the part with the directory
 * walking and the edge cases -- can be tested without a whisper server.
 */
internal object RetranscribeTargets {
    private val logger = LoggerFactory.getLogger(RetranscribeTargets::class.java)
    private val mapper = ObjectMapper()

    fun find(
        dataDir: Path,
        request: RetranscribeRequest,
    ): List<Path> {
        // Ordered and de-duplicated: naming an episode by both path and id
        // should decode it once, not twice.
        val targets = LinkedHashSet<Path>()

        for (relative in request.paths) {
            val path = dataDir.resolve(relative)
            if (!Files.isDirectory(path)) {
                logger.error("No episode directory at $relative")
                continue
            }
            targets.add(path)
        }

        if (request.ids.isNotEmpty()) targets.addAll(findByIds(dataDir, request.ids))
        if (request.flagged) targets.addAll(findFlagged(dataDir, request.limit))

        return targets.toList()
    }

    private fun findByIds(
        dataDir: Path,
        ids: List<String>,
    ): List<Path> {
        val wanted = ids.map { it.lowercase() }.toSet()
        val found = mutableMapOf<String, Path>()

        for (episodeDir in episodeDirs(dataDir)) {
            val id = EpisodeDirName.parse(episodeDir.name)?.episodeId ?: continue
            if (id !in wanted) continue
            // The id is part of a path, not a unique key: the same guid can
            // appear under two podcasts. Redo every copy rather than guessing.
            found.putIfAbsent("$id:${episodeDir.parent.name}", episodeDir)
        }

        val missing = wanted - found.values.mapNotNull { EpisodeDirName.parse(it.name)?.episodeId }.toSet()
        missing.forEach { logger.error("No episode directory found for id $it") }
        return found.values.toList()
    }

    /**
     * Reads every `transcript.json` under the data directory. Slow on a network
     * volume, which is why it is opt-in and why `--retranscribe-limit` exists.
     */
    private fun findFlagged(
        dataDir: Path,
        limit: Int,
    ): List<Path> {
        val flagged = mutableListOf<Path>()

        for (episodeDir in episodeDirs(dataDir)) {
            val transcript = episodeDir.resolve(PodcastPipeline.TRANSCRIPT_FILENAME)
            if (!Files.exists(transcript)) continue
            val flags =
                try {
                    mapper.readTree(Files.readString(transcript))
                        .path("episode_quality")
                        .path("flags")
                } catch (e: Exception) {
                    logger.warn("Could not read ${episodeDir.name} while scanning for flagged episodes", e)
                    continue
                }
            // Absent for anything transcribed before the quality gate existed;
            // unscored is not the same as flagged, so it is left alone.
            if (flags.isArray && !flags.isEmpty) flagged.add(episodeDir)
        }

        logger.info("Found ${flagged.size} flagged episodes")
        return if (limit > 0) flagged.take(limit) else flagged
    }

    /** Every `<data dir>/<podcast>/<episode>` directory, in a stable order. */
    private fun episodeDirs(dataDir: Path): List<Path> =
        listDirectories(dataDir)
            // logs/ sits beside the podcast directories and holds no episodes.
            .filter { it.name != "logs" }
            .flatMap { listDirectories(it) }

    private fun listDirectories(path: Path): List<Path> =
        try {
            Files.list(path).use { stream ->
                stream.filter { Files.isDirectory(it) }.toList().sortedBy { it.name }
            }
        } catch (e: Exception) {
            logger.error("Could not list $path", e)
            emptyList()
        }
}
