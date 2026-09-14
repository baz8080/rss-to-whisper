package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.TranscriberUnavailable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhisperPreflightTest {
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

    /**
     * Without this a run that downloaded the whole backlog and transcribed none
     * of it still exits 0, so nothing driving the pipeline can tell.
     */
    @Test
    fun `a run that could not reach whisper reports itself as failed`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(3),
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        assertFalse(pipeline.run())

        // The run still walks the whole feed: the downloads are what the next run reuses.
        assertEquals(3, txSvc.calls.size)
    }

    @Test
    fun `the count of episodes that got no transcript reaches the log`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(3),
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        val messages = logged { pipeline.run() }

        assertTrue(
            messages.any { it.contains("3 episodes got no transcript from the whisper server") },
            "logged: $messages",
        )
    }

    /** The episode still has a usable transcript, so the run did its job. */
    @Test
    fun `a quality retry that could not reach whisper does not fail the run`(
        @TempDir tempDir: Path,
    ) {
        val looping =
            whisperJson(
                *(0 until 20).map { Triple(it * 3.0, it * 3.0 + 3.0, "And that is the thing about it, really.") }
                    .toTypedArray(),
            )
        var call = 0
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(1),
                vtt = looping,
                onTranscribe = {
                    call++
                    if (call == 2) throw TranscriberUnavailable("nothing listening")
                },
            )

        assertTrue(pipeline.run())

        assertEquals(2, txSvc.calls.size, "the flagged first decode should have been retried")
    }

    /** An mp3 whisper refuses is that episode's problem, and says nothing about the run. */
    @Test
    fun `an episode that fails on its own merits does not fail the run`(
        @TempDir tempDir: Path,
    ) {
        var call = 0
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(3),
                onTranscribe = {
                    call++
                    if (call == 2) throw RuntimeException("Whisper server returned 400: failed to read audio data")
                },
            )

        assertTrue(pipeline.run())

        assertEquals(3, txSvc.calls.size)
    }

    /**
     * A build that answers every request without decoding anything passes the
     * preflight and fails each episode on its own apparent merits, so nothing
     * below would notice. The run still transcribed nothing.
     */
    @Test
    fun `a run that decoded nothing at all reports itself as failed`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                podcasts,
                entries(3),
                transcriberFails = { throw RuntimeException("Whisper server returned 500: failed to process audio") },
            )

        assertFalse(pipeline.run())

        assertEquals(3, txSvc.calls.size)
    }
}
