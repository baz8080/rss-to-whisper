package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What one run did, per podcast.
 *
 * The run tally counts warnings and errors, which says whether anything went
 * wrong but not what was done. This is the other half.
 */
internal class RunReport {
    class Counts {
        var transcribed = 0
        var recovered = 0
        var failed = 0
        val skipped = linkedMapOf<String, Int>()
    }

    private val podcasts = linkedMapOf<String, Counts>()
    private var startedAt: Instant = Instant.now()

    fun start() {
        startedAt = Instant.now()
        podcasts.clear()
    }

    fun forPodcast(name: String): Counts = podcasts.getOrPut(name) { Counts() }

    internal fun countSkip(
        name: String,
        reason: SkipReason,
    ) {
        forPodcast(name).skipped.merge(reason.name.lowercase(), 1, Int::plus)
    }

    fun toMap(finishedAt: Instant = Instant.now()): Map<String, Any> =
        mapOf(
            "started_at" to STAMP.format(startedAt),
            "finished_at" to STAMP.format(finishedAt),
            "duration_seconds" to Duration.between(startedAt, finishedAt).seconds,
            "totals" to
                mapOf(
                    "transcribed" to podcasts.values.sumOf { it.transcribed },
                    "recovered" to podcasts.values.sumOf { it.recovered },
                    "failed" to podcasts.values.sumOf { it.failed },
                    "skipped" to podcasts.values.sumOf { counts -> counts.skipped.values.sum() },
                ),
            "podcasts" to
                podcasts.mapValues { (_, counts) ->
                    mapOf(
                        "transcribed" to counts.transcribed,
                        "recovered" to counts.recovered,
                        "failed" to counts.failed,
                        "skipped" to counts.skipped,
                    )
                },
        )

    /**
     * Writes `logs/run-<stamp>.json`, then copies it to `logs/latest-run.json`.
     *
     * The timestamped file is the history; the stable name is what anything
     * watching the data directory can read without listing it first.
     */
    fun write(
        dataDirectory: String,
        mapper: ObjectMapper,
    ): Path? {
        val finishedAt = Instant.now()
        return try {
            val logDir = Path.of(dataDirectory).resolve("logs")
            Files.createDirectories(logDir)
            val path = logDir.resolve("run-${FILE_STAMP.format(finishedAt)}.json")
            Files.writeString(path, mapper.writeValueAsString(toMap(finishedAt)))
            Files.copy(path, logDir.resolve(LATEST_FILENAME), StandardCopyOption.REPLACE_EXISTING)
            path
        } catch (e: Exception) {
            logger.warn("Could not write the run report under $dataDirectory", e)
            null
        }
    }

    companion object {
        const val LATEST_FILENAME = "latest-run.json"

        private val logger = LoggerFactory.getLogger(RunReport::class.java)
        private val STAMP = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
