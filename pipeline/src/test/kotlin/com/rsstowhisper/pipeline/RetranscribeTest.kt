package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetranscribeTest {
    private val mapper = ObjectMapper()

    private val podcast = PodcastConfig(name = "Show", url = "https://feed")

    /** Twenty identical cues, which the quality gate scores as a repetition loop. */
    private fun loopingJson(): String =
        whisperJson(
            *(0 until 20)
                .map { Triple(it * 3.0, it * 3.0 + 3.0, "And that is the thing about it, really.") }
                .toTypedArray(),
        )

    private fun healthyJson(): String =
        whisperJson(
            Triple(0.0, 3.0, "So that is where the story begins, and it gets stranger."),
            Triple(3.0, 6.0, "We looked at the data again, carefully, and found something odd."),
        )

    /**
     * An episode as the pipeline leaves it: audio, a transcript, and whatever
     * metadata the feed entry carried when it was first processed.
     */
    private fun episode(
        dataDir: Path,
        dirName: String = "2024-01-02-abcd1234-hello-there",
        podcastDir: String = "Show",
        extra: Map<String, Any?> = emptyMap(),
    ): Path {
        val dir = dataDir.resolve(podcastDir).resolve(dirName)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("audio.mp3"), "fake-mp3-bytes")
        val fields =
            linkedMapOf<String, Any?>(
                "_id" to "abcd1234",
                "podcast_title" to "Show",
                "episode_title" to "Hello There",
                "episode_published_on" to "2024-01-02",
                "episode_summary" to "A summary the feed supplied once.",
                "episode_duration" to 1000,
                "all_tags" to listOf("talk"),
                "episode_transcript" to "WEBVTT\n\nold transcript\n",
                "episode_relative_audio_path" to "$podcastDir/$dirName/audio.mp3",
                "episode_quality" to mapOf("flags" to listOf("repetition-loop")),
            ) + extra
        Files.writeString(dir.resolve("transcript.json"), mapper.writeValueAsString(fields))
        return dir
    }

    @Suppress("UNCHECKED_CAST")
    private fun readTranscript(dir: Path): Map<String, Any?> =
        mapper.readValue(Files.readString(dir.resolve("transcript.json")), Map::class.java) as Map<String, Any?>

    // ---------- target selection ----------

    @Test
    fun `a path names one episode`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertEquals(listOf(dir), found)
    }

    @Test
    fun `a path that does not exist selects nothing`(
        @TempDir tempDir: Path,
    ) {
        episode(tempDir)

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(paths = listOf("Show/not-here")))

        assertEquals(emptyList(), found)
    }

    @Test
    fun `an id finds the episode whose directory carries it`(
        @TempDir tempDir: Path,
    ) {
        episode(tempDir, dirName = "2024-01-01-11111111-first")
        val wanted = episode(tempDir, dirName = "2024-01-02-22222222-second")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(ids = listOf("22222222")))

        assertEquals(listOf(wanted), found)
    }

    /** The id is part of a path, not a unique key: two podcasts can carry the same guid. */
    @Test
    fun `an id shared by two podcasts selects both copies`(
        @TempDir tempDir: Path,
    ) {
        val a = episode(tempDir, dirName = "2024-01-01-abcd1234-x", podcastDir = "Show A")
        val b = episode(tempDir, dirName = "2024-01-01-abcd1234-x", podcastDir = "Show B")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(ids = listOf("abcd1234")))

        assertEquals(setOf(a, b), found.toSet())
    }

    @Test
    fun `naming the same episode by path and id decodes it once`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, dirName = "2024-01-02-abcd1234-hello-there")

        val found =
            RetranscribeTargets.find(
                tempDir,
                RetranscribeRequest(paths = listOf("Show/2024-01-02-abcd1234-hello-there"), ids = listOf("abcd1234")),
            )

        assertEquals(listOf(dir), found)
    }

    @Test
    fun `the flagged scan selects only episodes with flags`(
        @TempDir tempDir: Path,
    ) {
        val flagged = episode(tempDir, dirName = "2024-01-01-11111111-flagged")
        episode(
            tempDir,
            dirName = "2024-01-02-22222222-clean",
            extra = mapOf("episode_quality" to mapOf("flags" to emptyList<String>())),
        )

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true))

        assertEquals(listOf(flagged), found)
    }

    /** Unscored is not the same as flagged; episodes predating the gate are left alone. */
    @Test
    fun `the flagged scan ignores episodes with no quality block at all`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.resolve("Show").resolve("2024-01-01-11111111-old")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("audio.mp3"), "bytes")
        Files.writeString(dir.resolve("transcript.json"), """{"_id":"11111111","episode_transcript":"WEBVTT"}""")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true))

        assertEquals(emptyList(), found)
    }

    @Test
    fun `the flagged scan honours its limit`(
        @TempDir tempDir: Path,
    ) {
        episode(tempDir, dirName = "2024-01-01-11111111-one")
        episode(tempDir, dirName = "2024-01-02-22222222-two")
        episode(tempDir, dirName = "2024-01-03-33333333-three")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true, limit = 2))

        assertEquals(2, found.size)
    }

    /** The error log lives beside the podcast directories and holds no episodes. */
    @Test
    fun `the flagged scan does not walk into the logs directory`(
        @TempDir tempDir: Path,
    ) {
        Files.createDirectories(tempDir.resolve("logs").resolve("something"))
        val flagged = episode(tempDir)

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true))

        assertEquals(listOf(flagged), found)
    }

    // ---------- decoding and rewriting ----------

    @Test
    fun `re-transcription replaces the transcript and keeps every other field`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val before = readTranscript(dir)
        val (pipeline, _, feedSvc) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        assertTrue(pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"))))

        val after = readTranscript(dir)
        assertTrue("We looked at the data again" in after["episode_transcript"].toString())
        assertTrue("old transcript" !in after["episode_transcript"].toString())

        // Everything the feed supplied survives the rewrite byte for byte.
        for (key in before.keys - setOf("episode_transcript", "episode_quality")) {
            assertEquals(before[key], after[key], "field $key should not have changed")
        }

        // No feed was fetched: the targets are already on disk.
        assertTrue(feedSvc.requestedUrls.isEmpty())
    }

    @Test
    fun `re-transcription records the new quality score`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        @Suppress("UNCHECKED_CAST")
        val quality = readTranscript(dir)["episode_quality"] as Map<String, Any?>
        assertEquals(emptyList<String>(), quality["flags"])
    }

    /**
     * A recovered episode's duration came from its previous decode, so this
     * decode supersedes it. Every other episode's came from the feed.
     */
    @Test
    fun `only a recovered episode has its duration replaced`(
        @TempDir tempDir: Path,
    ) {
        val fromFeed = episode(tempDir, dirName = "2024-01-01-11111111-feed")
        val recovered =
            episode(
                tempDir,
                dirName = "2024-01-02-22222222-recovered",
                extra = mapOf("episode_metadata_recovered" to true),
            )
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        pipeline.retranscribe(
            RetranscribeRequest(paths = listOf("Show/${fromFeed.fileName}", "Show/${recovered.fileName}")),
        )

        assertEquals(1000, readTranscript(fromFeed)["episode_duration"])
        assertEquals(6, readTranscript(recovered)["episode_duration"])
    }

    @Test
    fun `re-transcription rewrites the word sidecar and leaves no staging file`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertTrue(Files.exists(dir.resolve("words.jsonl.gz")))
        assertFalse(Files.exists(dir.resolve("transcript.json.new")))
    }

    @Test
    fun `an episode with no audio is left alone`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        Files.delete(dir.resolve("audio.mp3"))
        val before = Files.readString(dir.resolve("transcript.json"))
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertTrue(txSvc.calls.isEmpty())
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    /** A flagged decode still gets the quality gate's retry. */
    @Test
    fun `a re-transcribed episode that scores badly is decoded once more`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(loopingJson(), healthyJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertEquals(2, txSvc.calls.size)
        assertTrue("We looked at the data again" in readTranscript(dir)["episode_transcript"].toString())
    }

    @Test
    fun `a request that matches nothing fails the run`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        assertFalse(pipeline.retranscribe(RetranscribeRequest(ids = listOf("deadbeef"))))
        assertTrue(txSvc.calls.isEmpty())
    }
}
