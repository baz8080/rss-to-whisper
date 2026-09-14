package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.TranscriberUnavailable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetranscribeTest {
    private val mapper = ObjectMapper()

    private val podcast = PodcastConfig(name = "Show", url = "https://feed")

    /** What the quality gate scores as a repetition loop. */
    private fun loopingJson(): String =
        whisperJson(
            *(0 until 20)
                .map { Triple(it * 3.0, it * 3.0 + 3.0, "And that is the thing about it, really.") }
                .toTypedArray(),
        )

    /**
     * The shape [TranscriptQuality] writes, which is what a re-decode is measured
     * against -- `word_count` included, since every report it writes has one and a
     * stored report without it reads back as having produced no speech.
     */
    private fun qualityMap(
        flags: List<String>,
        punctuation: Double,
        meanWordProbability: Double? = null,
        wordCount: Int = 160,
    ): Map<String, Any?> =
        mapOf(
            "flags" to flags,
            "punctuation_per_word" to punctuation,
            "mean_word_probability" to meanWordProbability,
            "word_count" to wordCount,
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

    /** Named per process, so the assertion cannot hard-code the run's own pid. */
    private fun stagingFiles(dir: Path): List<String> =
        Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.filter { it.endsWith(".new") }.toList() }

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

    private fun markAttempted(
        episodeDir: Path,
        at: String,
    ) {
        Files.writeString(episodeDir.resolve(PodcastPipeline.RETRANSCRIBE_ATTEMPTED_FILENAME), at)
    }

    /**
     * The starvation #69 is about: an episode whose flags cannot clear sits at
     * the front of the sorted list forever, so a repeated limited run redoes the
     * same prefix and never reaches the tail.
     */
    @Test
    fun `the flagged scan takes the least recently attempted first`(
        @TempDir tempDir: Path,
    ) {
        // Named so the alphabetical order is the opposite of the answer, or the
        // test would pass on the old prefix behaviour.
        val fresh = episode(tempDir, dirName = "2024-01-01-11111111-fresh")
        val stale = episode(tempDir, dirName = "2024-01-02-22222222-stale")
        markAttempted(fresh, "2026-01-01T00:00:00Z")
        markAttempted(stale, "2020-01-01T00:00:00Z")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true, limit = 1))

        assertEquals(listOf(stale), found)
    }

    @Test
    fun `an episode never attempted is taken before any that has been`(
        @TempDir tempDir: Path,
    ) {
        val attempted = episode(tempDir, dirName = "2024-01-01-11111111-attempted")
        val untouched = episode(tempDir, dirName = "2024-01-02-22222222-untouched")
        markAttempted(attempted, "2020-01-01T00:00:00Z")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true))

        assertEquals(listOf(untouched, attempted), found)
    }

    /** Shuffling would fix the starvation too, but a run you cannot repeat is worse to measure. */
    @Test
    fun `episodes attempted at the same time keep a stable order`(
        @TempDir tempDir: Path,
    ) {
        val first = episode(tempDir, dirName = "2024-01-01-11111111-one")
        val second = episode(tempDir, dirName = "2024-01-02-22222222-two")
        markAttempted(first, "2020-01-01T00:00:00Z")
        markAttempted(second, "2020-01-01T00:00:00Z")

        repeat(3) {
            assertEquals(listOf(first, second), RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true)))
        }
    }

    @Test
    fun `an unreadable attempt marker is treated as never attempted`(
        @TempDir tempDir: Path,
    ) {
        val attempted = episode(tempDir, dirName = "2024-01-01-11111111-attempted")
        val broken = episode(tempDir, dirName = "2024-01-02-22222222-broken")
        markAttempted(attempted, "2020-01-01T00:00:00Z")
        markAttempted(broken, "not a timestamp")

        val found = RetranscribeTargets.find(tempDir, RetranscribeRequest(flagged = true))

        assertEquals(listOf(broken, attempted), found)
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

    private fun attemptMarker(episodeDir: Path): Path = episodeDir.resolve(PodcastPipeline.RETRANSCRIBE_ATTEMPTED_FILENAME)

    /**
     * The whole point of recording the attempt: the episodes that starve the
     * selection are the ones every re-decode refuses, so a marker written only
     * on success would never reach them.
     */
    @Test
    fun `a re-decode that is discarded still records the attempt`(
        @TempDir tempDir: Path,
    ) {
        // On disk: one flag and good punctuation. The re-decode loops, so it
        // scores worse and is refused -- which is how an episode starves.
        val dir =
            episode(
                tempDir,
                extra = mapOf("episode_quality" to qualityMap(flags = listOf("unpunctuated"), punctuation = 0.16)),
            )
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(loopingJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertTrue(Files.exists(attemptMarker(dir)), "no attempt marker after a discarded re-decode")
        assertTrue(Instant.parse(Files.readString(attemptMarker(dir))).epochSecond > 0)
    }

    @Test
    fun `an episode with no audio still records the attempt`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        Files.delete(dir.resolve("audio.mp3"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null)

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertTrue(Files.exists(attemptMarker(dir)), "no attempt marker after an episode with no audio")
    }

    @Test
    fun `a dry run records no attempt`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, dryRun = true)

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertFalse(Files.exists(attemptMarker(dir)))
    }

    /**
     * A server that dies partway would otherwise send the rest of the window to
     * the back of the queue unexamined, for as many runs as it takes to come
     * round again.
     */
    @Test
    fun `an episode whose decode could not reach whisper records no attempt`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                transcriberFails = { throw TranscriberUnavailable("nothing listening") },
            )

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertFalse(Files.exists(attemptMarker(dir)), "an untried episode was rotated to the back")
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
        // Bytes, not text: what replaces it is gzip.
        val stale = "the word times of the decode being replaced".toByteArray()
        Files.write(dir.resolve("words.jsonl.gz"), stale)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertFalse(stale.contentEquals(Files.readAllBytes(dir.resolve("words.jsonl.gz"))))
        assertEquals(emptyList(), stagingFiles(dir))
    }

    /**
     * Whisper is not deterministic, so a redo can come back worse than the
     * transcript it would overwrite -- and that write is the only copy.
     */
    @Test
    fun `a re-decode that scores worse than what is on disk is discarded`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, extra = mapOf("episode_quality" to qualityMap(emptyList(), 0.15)))
        val before = Files.readString(dir.resolve("transcript.json"))
        // Both decodes loop, so the quality gate's retry cannot rescue it either.
        val (pipeline, txSvc, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(loopingJson(), loopingJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertEquals(2, txSvc.calls.size)
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    @Test
    fun `--retranscribe-force keeps a decode that scored worse`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episode(
                tempDir,
                extra = mapOf("episode_quality" to qualityMap(flags = emptyList(), punctuation = 0.16)),
            )
        val before = readTranscript(dir)["episode_transcript"]
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(loopingJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), force = true))

        assertFalse(before == readTranscript(dir)["episode_transcript"], "the forced decode was discarded")
    }

    /** The same request without the flag is the control: the decode is refused. */
    @Test
    fun `without the flag the same decode is discarded`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episode(
                tempDir,
                extra = mapOf("episode_quality" to qualityMap(flags = emptyList(), punctuation = 0.16)),
            )
        val before = readTranscript(dir)["episode_transcript"]
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(loopingJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertEquals(before, readTranscript(dir)["episode_transcript"])
    }

    /**
     * Segments carrying timestamps and no text render a non-blank VTT, so the
     * isEmpty check ahead of the comparison does not catch them. Force covers
     * the score, and a decode with no words is not a worse redo but no redo.
     */
    @Test
    fun `--retranscribe-force does not let a speechless decode replace a real one`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episode(
                tempDir,
                extra = mapOf("episode_quality" to qualityMap(flags = emptyList(), punctuation = 0.16)),
            )
        val before = readTranscript(dir)["episode_transcript"]
        val speechless =
            """{"task":"transcribe","segments":[""" +
                """{"id":0,"start":0.0,"end":3.0,"text":"   ","words":[]}""" +
                "]}"
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(speechless))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), force = true))

        assertEquals(before, readTranscript(dir)["episode_transcript"])
    }

    /**
     * Force means "I know better than the score", not "ignore a server that
     * stopped sending token_timestamps" -- that would silently drop the
     * words.jsonl.gz the episode already has.
     */
    @Test
    fun `--retranscribe-force does not override the missing word timestamps guard`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episode(
                tempDir,
                extra =
                    mapOf(
                        "episode_quality" to
                            qualityMap(flags = emptyList(), punctuation = 0.16, meanWordProbability = 0.9),
                    ),
            )
        val before = readTranscript(dir)["episode_transcript"]
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(WORDLESS_JSON))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), force = true))

        assertEquals(before, readTranscript(dir)["episode_transcript"])
    }

    /** Unscored is not the same as passing: there is nothing to lose the decode to. */
    @Test
    fun `a transcript written before the quality gate is replaced without comparison`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, extra = mapOf("episode_quality" to null))
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(loopingJson(), loopingJson()))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        val after = readTranscript(dir)
        assertTrue("old transcript" !in after["episode_transcript"].toString())
        assertEquals(listOf("repetition-loop"), (after["episode_quality"] as Map<*, *>)["flags"])
    }

    /**
     * Word times decide only once flags and punctuation are level, so a decode
     * that lost them can still win on a flag the other tripped -- and would
     * take the sidecar down with it.
     */
    @Test
    fun `a re-decode with no word timestamps keeps the transcript that has them`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir, extra = mapOf("episode_quality" to qualityMap(listOf("low-confidence"), 0.15)))
        val sidecar = dir.resolve("words.jsonl.gz")
        Files.writeString(sidecar, "the word times of the decode on disk")
        val before = Files.readString(dir.resolve("transcript.json"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(WORDLESS_JSON))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
        assertEquals("the word times of the decode on disk", Files.readString(sidecar))
    }

    /**
     * Writing the sidecar is allowed to fail, so an episode can have a decode
     * that produced word times, flags that say so, and no file to find them in.
     * The stored score is what knows that; the missing file does not.
     */
    @Test
    fun `a wordless re-decode is refused for an episode whose sidecar write failed`(
        @TempDir tempDir: Path,
    ) {
        val dir =
            episode(
                tempDir,
                extra = mapOf("episode_quality" to qualityMap(listOf("low-confidence"), 0.15, meanWordProbability = 0.4)),
            )
        val before = Files.readString(dir.resolve("transcript.json"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(WORDLESS_JSON))

        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}")))

        assertFalse(Files.exists(dir.resolve("words.jsonl.gz")))
        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
    }

    /**
     * A full disk should cost the run this episode, not the word times of the
     * decode it was going to replace. Blocked by putting a directory where the
     * staged sidecar has to go, which is why this knows the staging name.
     */
    @Test
    fun `a sidecar that cannot be written leaves the episode untouched`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val sidecar = "the word times of the decode on disk".toByteArray()
        Files.write(dir.resolve("words.jsonl.gz"), sidecar)
        val before = Files.readString(dir.resolve("transcript.json"))
        Files.createDirectory(dir.resolve("words.jsonl.gz.${ProcessHandle.current().pid()}.new"))
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(podcast), feed = null, vtts = listOf(healthyJson()))

        val messages = logged { pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"))) }

        assertEquals(before, Files.readString(dir.resolve("transcript.json")))
        assertTrue(sidecar.contentEquals(Files.readAllBytes(dir.resolve("words.jsonl.gz"))))
        // An episode counted as re-transcribed is one nobody goes back to.
        assertFalse(messages.any { it.startsWith("Re-transcribed Show/") }, messages.toString())
        assertTrue(messages.any { it == "Re-transcribed 0 of 1 episodes" }, messages.toString())
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
