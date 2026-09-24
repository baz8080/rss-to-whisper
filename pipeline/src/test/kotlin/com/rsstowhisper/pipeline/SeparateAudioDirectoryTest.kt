package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeparateAudioDirectoryTest {
    private val podcasts = listOf(PodcastConfig(name = "Show", url = "https://feed"))

    private fun files(dir: Path): Set<String> = Files.list(dir).use { s -> s.map { it.name }.toList().toSet() }

    @Test
    fun `the mp3 goes to the audio directory and the transcript to the data directory`(
        @TempDir tempDir: Path,
    ) {
        val dataDir = Files.createDirectory(tempDir.resolve("data"))
        val audioDir = Files.createDirectory(tempDir.resolve("audio"))
        val (pipeline, txSvc, _) = buildPipeline(dataDir, podcasts, makeFeed(makeEntry("My Episode")), audioDir = audioDir)

        assertTrue(pipeline.run())

        val dataEpisode = Files.list(dataDir.resolve("Show")).use { it.toList() }.single()
        val audioEpisode = audioDir.resolve("Show").resolve(dataEpisode.name)
        assertEquals(setOf("transcript.json", "words.jsonl.gz"), files(dataEpisode))
        assertEquals(setOf("audio.mp3"), files(audioEpisode))
        assertEquals(audioEpisode.resolve("audio.mp3"), txSvc.calls.single())
        assertFalse(Files.exists(audioDir.resolve("logs")))
    }

    @Test
    fun `an episode downloaded by an earlier run keeps its directory name`(
        @TempDir tempDir: Path,
    ) {
        val dataDir = Files.createDirectory(tempDir.resolve("data"))
        val audioDir = Files.createDirectory(tempDir.resolve("audio"))
        val entry = makeEntry("Renamed Later")
        val earlier = "${PodcastPipeline.episodeStablePrefix(entry)}-Original-Title"
        val audioEpisode = Files.createDirectories(audioDir.resolve("Show").resolve(earlier))
        Files.write(audioEpisode.resolve("audio.mp3"), FAKE_MP3_BYTES)

        val (pipeline, _, feedSvc) = buildPipeline(dataDir, podcasts, makeFeed(entry), audioDir = audioDir)
        assertTrue(pipeline.run())

        assertTrue(feedSvc.downloads.isEmpty())
        assertTrue(Files.exists(dataDir.resolve("Show").resolve(earlier).resolve("transcript.json")))
    }

    @Test
    fun `an orphan whose audio is all there is gets its transcript in the data directory`(
        @TempDir tempDir: Path,
    ) {
        val dataDir = Files.createDirectory(tempDir.resolve("data"))
        val audioDir = Files.createDirectory(tempDir.resolve("audio"))
        val orphan = "2023-01-01-abcd1234-Aged-Out"
        Files.write(Files.createDirectories(audioDir.resolve("Show").resolve(orphan)).resolve("audio.mp3"), FAKE_MP3_BYTES)

        val (pipeline, txSvc, _) = buildPipeline(dataDir, podcasts, makeFeed(makeEntry("Current")), audioDir = audioDir)
        assertTrue(pipeline.run())

        assertTrue(audioDir.resolve("Show").resolve(orphan).resolve("audio.mp3") in txSvc.calls)
        assertTrue(Files.exists(dataDir.resolve("Show").resolve(orphan).resolve("transcript.json")))
    }

    @Test
    fun `re-transcription decodes the audio from the audio directory`(
        @TempDir tempDir: Path,
    ) {
        val dataDir = Files.createDirectory(tempDir.resolve("data"))
        val audioDir = Files.createDirectory(tempDir.resolve("audio"))
        val (first, _, _) = buildPipeline(dataDir, podcasts, makeFeed(makeEntry("My Episode")), audioDir = audioDir)
        first.run()
        val episode = Files.list(dataDir.resolve("Show")).use { it.toList() }.single().name

        val (again, txSvc, _) = buildPipeline(dataDir, podcasts, feed = null, audioDir = audioDir)
        again.retranscribe(RetranscribeRequest(paths = listOf("Show/$episode"), force = true))

        assertEquals(audioDir.resolve("Show").resolve(episode).resolve("audio.mp3"), txSvc.calls.single())
    }

    @Test
    fun `run refuses a missing audio directory`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, feedSvc) =
            buildPipeline(tempDir, podcasts, makeFeed(makeEntry("My Episode")), audioDir = tempDir.resolve("missing"))

        assertFalse(pipeline.run())
        assertTrue(feedSvc.requestedUrls.isEmpty())
    }
}
