package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DryRunTest {
    private val podcasts = listOf(PodcastConfig(name = "Show", url = "https://feed"))

    private fun entries(count: Int) = makeFeed(*(1..count).map { makeEntry("Episode $it") }.toTypedArray())

    private fun treeUnder(dir: Path): List<String> =
        Files.walk(dir).use { paths ->
            paths.filter { it != dir }.map { dir.relativize(it).toString() }.sorted().toList()
        }

    @Test
    fun `a dry run downloads nothing, decodes nothing and creates nothing`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, feedSvc) = buildPipeline(tempDir, podcasts, entries(3), dryRun = true)

        assertTrue(pipeline.run())

        assertTrue(txSvc.calls.isEmpty())
        assertTrue(feedSvc.downloads.isEmpty())
        assertEquals(emptyList(), treeUnder(tempDir))
    }

    /** Whisper is not consulted, so a dry run still works with the server off. */
    @Test
    fun `a dry run does not preflight the whisper server`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(tempDir, podcasts, entries(2), dryRun = true, transcriberAnswersPing = false)

        assertTrue(pipeline.run())

        assertEquals(0, txSvc.pings)
        assertEquals(listOf("https://feed"), feedSvc.requestedUrls)
    }

    @Test
    fun `a dry run names each episode it would transcribe, and counts them`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, entries(3), dryRun = true)

        val messages = logged { pipeline.run() }

        assertEquals(3, messages.count { it.startsWith("Would transcribe Show/") })
        assertTrue(messages.any { it == "Show: would transcribe 3 episodes" }, "logged: $messages")
        assertTrue(
            messages.any { it == "Dry run: would transcribe 3 and recover 0 episodes" },
            "logged: $messages",
        )
    }

    /** Every filter still applies, so a dry run reports the same set a real one would take. */
    @Test
    fun `a dry run applies the skip rules`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry("Trailer: coming soon"), makeEntry("Real Episode"))
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, feed, dryRun = true)

        val messages = logged { pipeline.run() }

        assertEquals(1, messages.count { it.startsWith("Would transcribe Show/") })
        assertTrue(messages.any { it.startsWith("Would transcribe Show/") && it.contains("Real-Episode") })
    }

    @Test
    fun `a dry run reports orphans it would recover without decoding them`(
        @TempDir tempDir: Path,
    ) {
        val orphan = tempDir.resolve("Show").resolve("2019-01-01-deadbeef-aged-out")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("audio.mp3"), "fake-mp3-bytes")

        val (pipeline, txSvc, _) = buildPipeline(tempDir, podcasts, entries(1), dryRun = true)

        val messages = logged { pipeline.run() }

        assertTrue(txSvc.calls.isEmpty())
        assertTrue(
            messages.any { it == "Would recover Show/2019-01-01-deadbeef-aged-out" },
            "logged: $messages",
        )
        assertTrue(Files.notExists(orphan.resolve("transcript.json")))
    }

    /** A podcast with no directory yet has nothing orphaned, and saying so is not an error. */
    @Test
    fun `a dry run over a data directory with nothing in it logs no errors`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, entries(2), dryRun = true)

        val errors = loggedAtError { pipeline.run() }

        assertEquals(emptyList(), errors)
    }

    /** Recovery refuses a zero-byte mp3, so a dry run must not offer it. */
    @Test
    fun `a dry run does not offer an orphan whose audio is empty`(
        @TempDir tempDir: Path,
    ) {
        val orphan = tempDir.resolve("Show").resolve("2019-01-01-deadbeef-aged-out")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("audio.mp3"), "")

        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, entries(1), dryRun = true)

        val messages = logged { pipeline.run() }

        assertTrue(messages.none { it.startsWith("Would recover") }, "logged: $messages")
        assertTrue(
            messages.any { it == "Dry run: would transcribe 1 and recover 0 episodes" },
            "logged: $messages",
        )
    }

    /** The summary is shared with a real run, and must not claim work that did not happen. */
    @Test
    fun `a dry run does not report orphans as recovered`(
        @TempDir tempDir: Path,
    ) {
        val orphan = tempDir.resolve("Show").resolve("2019-01-01-deadbeef-aged-out")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("audio.mp3"), "fake-mp3-bytes")

        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, entries(1), dryRun = true)

        val messages = logged { pipeline.run() }

        val summary = messages.single { it.contains("directories absent from the feed") }
        assertTrue(summary.contains("1 to recover"), summary)
        assertTrue(!summary.contains("recovered"), summary)
    }

    /** The report records work done, and latest-run.json would lose the last real run. */
    @Test
    fun `a dry run writes no run report`(
        @TempDir tempDir: Path,
    ) {
        val logs = Files.createDirectories(tempDir.resolve("logs"))
        val lastReal = logs.resolve(RunReport.LATEST_FILENAME)
        Files.writeString(lastReal, "{\"totals\":{\"transcribed\":41}}")

        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, entries(3), dryRun = true)

        assertTrue(pipeline.run())

        assertEquals("{\"totals\":{\"transcribed\":41}}", Files.readString(lastReal))
        assertTrue(
            Files.list(logs).use { it.toList() }.none { it.fileName.toString().startsWith("run-") },
            "a dry run left a stamped report behind",
        )
    }

    @Test
    fun `a dry run lists the episodes a re-transcription would redo`(
        @TempDir tempDir: Path,
    ) {
        val episode = tempDir.resolve("Show").resolve("2024-01-01-11111111-old")
        Files.createDirectories(episode)
        Files.writeString(episode.resolve("audio.mp3"), "fake-mp3-bytes")
        Files.writeString(episode.resolve("transcript.json"), "{}")

        val (pipeline, txSvc, _) = buildPipeline(tempDir, podcasts, entries(1), dryRun = true)

        val messages =
            logged {
                assertTrue(pipeline.retranscribe(RetranscribeRequest(ids = listOf("11111111"))))
            }

        assertTrue(txSvc.calls.isEmpty())
        assertEquals(0, txSvc.pings)
        assertTrue(
            messages.any { it == "Would re-transcribe Show/2024-01-01-11111111-old" },
            "logged: $messages",
        )
        assertEquals("{}", Files.readString(episode.resolve("transcript.json")))
    }
}
