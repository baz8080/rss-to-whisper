package com.rsstowhisper.pipeline

import com.rsstowhisper.AppConfig
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.escapeFilename
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodcastPipelineRunTest {
    @Test
    fun `run returns false when the data dir is missing`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, feedSvc) =
            buildPipeline(
                tempDir.resolve("not-created"),
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                makeFeed(makeEntry("My Episode")),
            )

        assertFalse(pipeline.run())
        assertTrue(feedSvc.requestedUrls.isEmpty())
    }

    @Test
    fun `run returns true when there is nothing to do`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(tempDir, emptyList(), makeFeed())

        assertTrue(pipeline.run())
    }

    @Test
    fun `run transcribes episode and writes json output`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry("My Episode"))
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
            )

        pipeline.run()

        val podcastDir = tempDir.resolve("Show")
        assertTrue(Files.isDirectory(podcastDir))
        val episodeDir = Files.list(podcastDir).use { it.toList() }.single()
        assertTrue(Files.exists(episodeDir.resolve("transcript.json")))
        assertTrue(Files.exists(episodeDir.resolve("audio.mp3"))) // mp3 retained
        assertFalse(Files.exists(episodeDir.resolve("audio.wav"))) // no wav produced
        assertEquals(1, txSvc.calls.size)
        assertEquals("audio.mp3", txSvc.calls.single().fileName.toString())

        val json = Files.readString(episodeDir.resolve("transcript.json"))
        assertTrue(json.contains("\"episode_title\""))
        assertTrue(json.contains("My Episode"))
        assertTrue(json.contains("audio.mp3"))
    }

    /**
     * Regression: the download skip-check tests audio.mp3, but the old pipeline
     * deleted the mp3 and kept a wav, so the file it looked for never survived.
     * Re-transcribing an episode re-downloaded audio already on disk.
     */
    @Test
    fun `run does not re-download when the mp3 is already on disk`(
        @TempDir tempDir: Path,
    ) {
        val entry = makeEntry("My Episode")
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                makeFeed(entry),
            )

        val episodeDir =
            Files.createDirectories(
                tempDir.resolve("Show").resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(entry))),
            )
        Files.writeString(episodeDir.resolve("audio.mp3"), "already-downloaded")

        pipeline.run()

        assertTrue(feedSvc.downloads.isEmpty())
        assertEquals(1, txSvc.calls.size)
        assertEquals("already-downloaded", Files.readString(episodeDir.resolve("audio.mp3")))
        assertTrue(Files.exists(episodeDir.resolve("transcript.json")))
    }

    @Test
    fun `run deletes a stale wav left behind by the old ffmpeg pipeline`(
        @TempDir tempDir: Path,
    ) {
        val entry = makeEntry("My Episode")
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                makeFeed(entry),
            )

        val episodeDir =
            Files.createDirectories(
                tempDir.resolve("Show").resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(entry))),
            )
        Files.writeString(episodeDir.resolve("audio.wav"), "stale-wav-bytes")

        pipeline.run()

        assertFalse(Files.exists(episodeDir.resolve("audio.wav")))
        assertTrue(Files.exists(episodeDir.resolve("audio.mp3")))
        assertTrue(Files.exists(episodeDir.resolve("transcript.json")))
    }

    @Test
    fun `run skips entries whose title matches excludes case-insensitively`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Bonus: Patreon Only"),
                makeEntry("Episode 42: Real Content"),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(
                    PodcastConfig(
                        name = "Show",
                        url = "https://feed",
                        excludes = listOf("PATREON"),
                    ),
                ),
                feed,
            )

        pipeline.run()

        assertEquals(1, txSvc.calls.size)
        val episodes = Files.list(tempDir.resolve("Show")).use { it.toList() }
        assertEquals(1, episodes.size)
        assertTrue(episodes[0].fileName.toString().contains("Real-Content"))
    }

    @Test
    fun `run skips entries matching the global keyword list on whole words only`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Season 3, Trailer"),
                makeEntry("Curb Cuts (Repeat)"),
                makeEntry("Jack the Ripper [From the Archives]"),
                makeEntry("Another Repeater Found"),
                makeEntry("Inside the Archives"),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), feed)

        pipeline.run()

        assertEquals(2, txSvc.calls.size)
        val kept = Files.list(tempDir.resolve("Show")).use { it.toList() }.map { it.fileName.toString() }
        assertTrue(kept.any { it.contains("Repeater") })
        assertTrue(kept.any { it.contains("Inside-the-Archives") })
    }

    @Test
    fun `run skips entries under the minimum duration but keeps ones with no duration`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Short Promo", durationSeconds = 90),
                makeEntry("Real Episode", durationSeconds = 1800),
                makeEntry("Unknown Length"),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), feed)

        pipeline.run()

        assertEquals(2, txSvc.calls.size)
        val kept = Files.list(tempDir.resolve("Show")).use { it.toList() }.map { it.fileName.toString() }
        assertFalse(kept.any { it.contains("Short-Promo") })
    }

    @Test
    fun `run lets a podcast override the global minimum duration`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry("Quick Answer", durationSeconds = 90))
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed", minEpisodeDurationSeconds = 0)),
                feed,
            )

        pipeline.run()

        assertEquals(1, txSvc.calls.size)
    }

    @Test
    fun `run continues past isolated transcribed episodes and transcribes gaps`(
        @TempDir tempDir: Path,
    ) {
        val newEntry = makeEntry("New Episode", audioUrl = "https://cdn/new.mp3")
        val oldEntry = makeEntry("Old Episode", audioUrl = "https://cdn/old.mp3")
        val feed = makeFeed(newEntry, oldEntry)

        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                skipAfterConsecutive = 2,
            )

        val podcastDir = Files.createDirectories(tempDir.resolve("Show"))
        val newEpisodeDir =
            Files.createDirectories(
                podcastDir.resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(newEntry))),
            )
        Files.writeString(newEpisodeDir.resolve("transcript.json"), "{}")

        pipeline.run()

        assertEquals(1, txSvc.calls.size)
        val oldDir = podcastDir.resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(oldEntry)))
        assertTrue(Files.exists(oldDir.resolve("transcript.json")))
    }

    @Test
    fun `run skips remainder of feed after threshold consecutive transcribed`(
        @TempDir tempDir: Path,
    ) {
        val e1 = makeEntry("Ep One")
        val e2 = makeEntry("Ep Two")
        val e3 = makeEntry("Ep Three")
        val e4 = makeEntry("Ep Four")
        val feed = makeFeed(e1, e2, e3, e4)

        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                skipAfterConsecutive = 3,
            )

        val podcastDir = Files.createDirectories(tempDir.resolve("Show"))
        for (e in listOf(e1, e2, e3)) {
            val d =
                Files.createDirectories(
                    podcastDir.resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(e))),
                )
            Files.writeString(d.resolve("transcript.json"), "{}")
        }

        pipeline.run()

        assertEquals(0, txSvc.calls.size)
        val fourthDir = podcastDir.resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(e4)))
        assertFalse(Files.exists(fourthDir))
    }

    @Test
    fun `run does not transcribe an episode whose download failed`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry("Unfetchable"))
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                feedService = FakeFeedService(mapOf("https://feed" to feed), downloadFails = { true }),
            )

        pipeline.run()

        assertEquals(0, txSvc.calls.size)
        val episodeDirs = Files.list(tempDir.resolve("Show")).use { it.toList() }
        // The directory is created by the download attempt itself, so this has something to assert on.
        assertEquals(1, episodeDirs.size)
        assertFalse(episodeDirs.any { Files.exists(it.resolve("transcript.json")) })
    }

    @Test
    fun `run continues past podcasts whose feed fails to fetch`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Broken", url = "https://feed")),
                feed = null,
            )

        pipeline.run()

        assertEquals(1, feedSvc.requestedUrls.size)
        assertEquals(0, txSvc.calls.size)
    }

    @Test
    fun `run skips entries without audio enclosure but processes siblings`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("No Audio", audioUrl = null),
                makeEntry("Has Audio"),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
            )

        pipeline.run()

        assertEquals(1, txSvc.calls.size)
        val episodeDirs = Files.list(tempDir.resolve("Show")).use { it.toList() }
        assertEquals(1, episodeDirs.size)
        assertTrue(episodeDirs[0].fileName.toString().contains("Has-Audio"))
        assertTrue(Files.exists(episodeDirs[0].resolve("transcript.json")))
    }

    @Test
    fun `run skips entries with null title`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(makeEntry(title = null), makeEntry("Good"))
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
            )

        pipeline.run()

        assertEquals(1, txSvc.calls.size)
    }

    @Test
    fun `run bails out early when data directory is not writable`(
        @TempDir tempDir: Path,
    ) {
        val missing = tempDir.resolve("does-not-exist")
        val config =
            AppConfig(
                dataDirectory = missing.toAbsolutePath().toString(),
                whisperServerUrl = FAKE_SERVER_URL,
                podcasts = listOf(PodcastConfig(name = "Show", url = "https://feed")),
            )
        val feedSvc = FakeFeedService(mapOf("https://feed" to makeFeed(makeEntry("E"))))
        val txSvc = FakeTranscriber(FAKE_SERVER_URL, MINIMAL_VTT)
        val pipeline =
            PodcastPipeline(
                config = config,
                feedService = feedSvc,
                transcriber = txSvc,
            )

        pipeline.run()

        assertEquals(0, txSvc.calls.size)
        assertTrue(feedSvc.requestedUrls.isEmpty())
    }

    @Test
    fun `run produces transcript json with expected fields and content`(
        @TempDir tempDir: Path,
    ) {
        val vtt =
            whisperJson(
                Triple(0.0, 1.5, "First sentence."),
                Triple(1.5, 3.0, "Second sentence."),
            )
        val feed = makeFeed(makeEntry("Episode"))
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed", collections = listOf("tech"))),
                feed,
                vtt = vtt,
            )

        pipeline.run()

        val episodeDir = Files.list(tempDir.resolve("Show")).use { it.toList() }.single()
        val json = Files.readString(episodeDir.resolve("transcript.json"))
        assertTrue(json.contains("First sentence."))
        assertTrue(json.contains("Second sentence."))
        assertTrue(json.contains("\"podcast_collections\""))
        assertTrue(json.contains("tech"))
        assertTrue(json.contains("\"episode_relative_audio_path\""))
        assertTrue(json.contains("Show/"))
    }

    @Test
    fun `run downloads the next episode while the current one is decoding`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Ep One", audioUrl = "https://cdn/one.mp3"),
                makeEntry("Ep Two", audioUrl = "https://cdn/two.mp3"),
                makeEntry("Ep Three", audioUrl = "https://cdn/three.mp3"),
            )
        val feedSvc = FakeFeedService(mapOf("https://feed" to feed))
        val downloadedDuringFirstDecode = mutableListOf<String>()
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                feedService = feedSvc,
                onTranscribe = { audioPath ->
                    if (audioPath.parent.fileName.toString().contains("Ep-One")) {
                        // The prefetch runs on its own thread, so give it a moment to be recorded.
                        awaitDownloads(feedSvc, 2)
                        downloadedDuringFirstDecode += synchronized(feedSvc.downloads) { feedSvc.downloads.map { it.first } }
                    }
                },
            )

        pipeline.run()

        assertEquals(listOf("https://cdn/one.mp3", "https://cdn/two.mp3"), downloadedDuringFirstDecode)
        assertEquals(3, txSvc.calls.size)
        assertEquals(3, feedSvc.downloads.size)
        val episodeDirs = Files.list(tempDir.resolve("Show")).use { it.toList() }
        assertEquals(3, episodeDirs.count { Files.exists(it.resolve("transcript.json")) })
    }

    /**
     * A feed that lists one guid twice used to be deduped by the first entry creating its
     * directory before the second was examined. Deciding before downloading removed that.
     */
    @Test
    fun `run transcribes an episode once when the feed lists its guid twice`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Ep Twelve", guid = "https://example.com/guid/twelve"),
                makeEntry("Ep Twelve (fixed audio)", guid = "https://example.com/guid/twelve"),
            )
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(tempDir, listOf(PodcastConfig(name = "Show", url = "https://feed")), feed)

        pipeline.run()

        assertEquals(1, txSvc.calls.size)
        assertEquals(1, feedSvc.downloads.size)
        val episodeDirs = Files.list(tempDir.resolve("Show")).use { it.toList() }
        assertEquals(1, episodeDirs.size)
        assertTrue(Files.exists(episodeDirs.single().resolve("transcript.json")))
    }

    @Test
    fun `run skips an episode whose prefetched download failed and continues`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Ep One", audioUrl = "https://cdn/one.mp3"),
                makeEntry("Ep Two", audioUrl = "https://cdn/two.mp3"),
                makeEntry("Ep Three", audioUrl = "https://cdn/three.mp3"),
            )
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                feedService =
                    FakeFeedService(
                        mapOf("https://feed" to feed),
                        downloadFails = { url -> url.endsWith("two.mp3") },
                    ),
            )

        pipeline.run()

        val decoded = txSvc.calls.map { it.parent.fileName.toString() }
        assertEquals(2, decoded.size)
        assertTrue(decoded.any { it.contains("Ep-One") })
        assertTrue(decoded.any { it.contains("Ep-Three") })
    }

    @Test
    fun `run keeps going when the transcriber fails on one episode`(
        @TempDir tempDir: Path,
    ) {
        val feed =
            makeFeed(
                makeEntry("Ep One", audioUrl = "https://cdn/one.mp3"),
                makeEntry("Ep Two", audioUrl = "https://cdn/two.mp3"),
                makeEntry("Ep Three", audioUrl = "https://cdn/three.mp3"),
            )
        val (pipeline, txSvc, feedSvc) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                feed,
                transcriberFails = { throw IllegalStateException("whisper is down") },
            )

        assertTrue(pipeline.run())

        assertEquals(3, txSvc.calls.size)
        assertEquals(3, feedSvc.downloads.size)
        val episodeDirs = Files.list(tempDir.resolve("Show")).use { it.toList() }
        assertFalse(episodeDirs.any { Files.exists(it.resolve("transcript.json")) })
    }

    private fun awaitDownloads(
        feedSvc: FakeFeedService,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (feedSvc.downloads.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(count, feedSvc.downloads.size, "timed out waiting for $count downloads")
    }

    @Test
    fun `a podcast language overrides the top-level default`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed", language = "fr")),
                makeFeed(makeEntry("My Episode")),
                language = "de",
            )

        pipeline.run()

        assertEquals(listOf("fr"), txSvc.languages)
    }

    @Test
    fun `a podcast without a language falls back to the top-level one`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(PodcastConfig(name = "Show", url = "https://feed")),
                makeFeed(makeEntry("My Episode")),
                language = "de",
            )

        pipeline.run()

        assertEquals(listOf("de"), txSvc.languages)
    }
}
