package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunReportTest {
    private val mapper = ObjectMapper()

    private fun readReport(tempDir: Path): Map<*, *> {
        val latest = tempDir.resolve("logs").resolve(RunReport.LATEST_FILENAME)
        assertTrue(Files.exists(latest), "no $latest")
        return mapper.readValue(Files.readString(latest), Map::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun podcast(
        report: Map<*, *>,
        name: String,
    ): Map<String, Any> = (report["podcasts"] as Map<String, Any>).getValue(name) as Map<String, Any>

    @Test
    fun `a run records what it transcribed, per podcast and in total`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry("One"), makeEntry("Two"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), feed)

        pipeline.run()

        val report = readReport(tempDir)
        assertEquals(2, podcast(report, "Show")["transcribed"])
        assertEquals(2, (report["totals"] as Map<*, *>)["transcribed"])
        assertEquals(0, (report["totals"] as Map<*, *>)["failed"])
    }

    @Test
    fun `skips are recorded by reason`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Trailer: coming soon"),
                makeEntry("Encore: an old one"),
                makeEntry("Short one", durationSeconds = 30),
                makeEntry("Real Episode"),
            )
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), feed)

        pipeline.run()

        val skipped = podcast(readReport(tempDir), "Show")["skipped"] as Map<*, *>
        assertEquals(2, skipped["global_keyword"])
        assertEquals(1, skipped["too_short"])
        assertEquals(1, podcast(readReport(tempDir), "Show")["transcribed"])
    }

    @Test
    fun `an episode that could not be transcribed is counted as failed`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry("One"), makeEntry("Two"))
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                transcriberFails = { throw RuntimeException("whisper said no") },
            )

        pipeline.run()

        val show = podcast(readReport(tempDir), "Show")
        assertEquals(0, show["transcribed"])
        assertEquals(2, show["failed"])
    }

    @Test
    fun `a recovered orphan is counted as recovered, not transcribed`(
        @TempDir tempDir: Path,
    ) {
        val orphan = tempDir.resolve("Show").resolve("2019-01-01-deadbeef-aged-out")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("audio.mp3"), "fake-mp3-bytes")

        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), makeFeed(makeEntry("One")))

        pipeline.run()

        val show = podcast(readReport(tempDir), "Show")
        assertEquals(1, show["transcribed"])
        assertEquals(1, show["recovered"])
    }

    /** The stable name is what anything watching the directory reads; the stamped one is the history. */
    @Test
    fun `the report is written under a timestamped name and copied to the latest one`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), makeFeed(makeEntry("One")))

        pipeline.run()

        val logs = tempDir.resolve("logs")
        val stamped = Files.list(logs).use { it.toList() }.filter { it.fileName.toString().startsWith("run-") }
        assertEquals(1, stamped.size, "expected one stamped report, got $stamped")
        assertEquals(
            Files.readString(stamped.single()),
            Files.readString(logs.resolve(RunReport.LATEST_FILENAME)),
        )
    }

    @Test
    fun `the report carries the times the run spanned`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), makeFeed(makeEntry("One")))

        pipeline.run()

        val report = readReport(tempDir)
        assertNotNull(report["started_at"])
        assertNotNull(report["finished_at"])
        assertTrue((report["duration_seconds"] as Int) >= 0)
    }

    /** A report that cannot be written must not take the run down with it. */
    @Test
    fun `a run whose report cannot be written still succeeds`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("logs"), "not a directory")

        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), makeFeed(makeEntry("One")))

        assertTrue(pipeline.run())
        assertTrue(Files.exists(tempDir.resolve("Show")))
    }
}
