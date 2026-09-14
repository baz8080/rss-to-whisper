package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.TranscriberUnavailable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A whisper server that is down used to cost a whole run: every episode is
 * downloaded before it is decoded, and the per-episode catch that stops one
 * bad episode ending a run is exactly what hides a server that will fail on
 * all of them. These cover the preflight and the give-up threshold.
 */
class WhisperCircuitBreakerTest {
    private val podcasts = listOf(PodcastConfig(name = "Show", url = "https://feed"))

    private fun entries(count: Int) = makeFeed(*(1..count).map { makeEntry("Episode $it") }.toTypedArray())

    @Test
    fun `run asks the server once, before a feed is fetched`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, feedSvc) = buildPipeline(tempDir, podcasts, entries(1))

        assertTrue(pipeline.run())

        assertEquals(1, txSvc.pings)
        assertEquals(1, feedSvc.requestedUrls.size)
    }

    @Test
    fun `run fetches nothing and fails when the server does not answer`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(tempDir, podcasts, entries(3), transcriberAnswersPing = false)

        assertFalse(pipeline.run())

        assertTrue(feedSvc.requestedUrls.isEmpty())
        assertTrue(feedSvc.downloads.isEmpty())
        assertTrue(txSvc.calls.isEmpty())
    }

    @Test
    fun `run gives up once enough decodes in a row cannot reach the server`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(8),
                maxConsecutiveTranscriberErrors = 3,
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        assertFalse(pipeline.run())

        assertEquals(3, txSvc.calls.size)
        // One episode is prefetched while the current one decodes, so the last
        // failure can have one download already in flight behind it -- but no
        // more: the rest of the feed must stay unfetched.
        assertTrue(feedSvc.downloads.size <= 4, "downloaded ${feedSvc.downloads.size} episodes")
    }

    @Test
    fun `a decode that reaches the server clears the count`(
        @TempDir tempDir: Path,
    ) {
        var call = 0
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(5),
                maxConsecutiveTranscriberErrors = 3,
                // Fails twice, answers, then fails twice: never three in a row.
                onTranscribe = {
                    call++
                    if (call != 3) throw TranscriberUnavailable("nothing listening")
                },
            )

        assertTrue(pipeline.run())

        assertEquals(5, txSvc.calls.size)
    }

    /**
     * The breaker is for a server that is not there. An episode whose decode
     * throws for its own reasons says nothing about the next one, and must not
     * end the run.
     */
    @Test
    fun `an ordinary decode failure never trips the breaker`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(5),
                maxConsecutiveTranscriberErrors = 3,
                transcriberFails = { throw RuntimeException("bad audio") },
            )

        assertTrue(pipeline.run())

        assertEquals(5, txSvc.calls.size)
    }

    @Test
    fun `zero means the run never gives up`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(5),
                maxConsecutiveTranscriberErrors = 0,
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        assertTrue(pipeline.run())

        assertEquals(5, txSvc.calls.size)
    }

    @Test
    fun `giving up stops the podcasts that have not been reached yet`(
        @TempDir tempDir: Path,
    ) {
        val feedSvc =
            FakeFeedService(
                mapOf(
                    "https://feed" to entries(4),
                    "https://feed-b" to entries(4),
                ),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(
                    PodcastConfig(name = "Show", url = "https://feed"),
                    PodcastConfig(name = "Show B", url = "https://feed-b"),
                ),
                feed = null,
                maxConsecutiveTranscriberErrors = 2,
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
                feedService = feedSvc,
            )

        assertFalse(pipeline.run())

        assertEquals(2, txSvc.calls.size)
        assertEquals(listOf("https://feed"), feedSvc.requestedUrls)
    }

    /**
     * Orphan recovery reports what it left for a later run as the orphan limit
     * being reached. Reaching it with the breaker already tripped names the
     * wrong cause for a run that stopped for a quite different reason.
     */
    @Test
    fun `giving up does not blame the orphan limit for what it left behind`(
        @TempDir tempDir: Path,
    ) {
        val orphan = tempDir.resolve("Show").resolve("2019-01-01-deadbeef-aged-out")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("audio.mp3"), "fake-mp3-bytes")

        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(4),
                maxConsecutiveTranscriberErrors = 2,
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        val messages = logged { pipeline.run() }

        assertFalse(
            messages.any { it.contains("orphan-limit") || it.contains("absent from the feed") },
            "logged: $messages",
        )
    }

    @Test
    fun `the reason the run gave up reaches the log`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(5),
                maxConsecutiveTranscriberErrors = 2,
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        val messages = logged { pipeline.run() }

        assertTrue(
            messages.any { it.contains("Giving up") && it.contains("nothing listening") },
            "logged: $messages",
        )
    }
}
