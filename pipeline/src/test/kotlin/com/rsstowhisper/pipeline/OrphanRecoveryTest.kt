package com.rsstowhisper.pipeline

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FEED_URL = "https://feed"
private const val PODCAST_NAME = "Test Podcast"

class OrphanRecoveryTest {
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val pipelineLogger = context.getLogger("com.rsstowhisper")
    private val logged = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun captureLogs() {
        logged.context = context
        logged.start()
        pipelineLogger.addAppender(logged)
    }

    @AfterEach
    fun releaseLogs() {
        pipelineLogger.detachAppender(logged)
        logged.stop()
    }

    private fun errors() = logged.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    private val podcast = PodcastConfig(name = PODCAST_NAME, url = FEED_URL, collections = listOf("science"))

    /** A directory holding audio nobody has transcribed, exactly as an aged-out episode leaves one. */
    private fun orphanDir(
        dataDir: Path,
        dirName: String,
        vararg files: Pair<String, String>,
    ): Path {
        val dir = dataDir.resolve(PODCAST_NAME.replace(' ', '-')).resolve(dirName)
        Files.createDirectories(dir)
        files.forEach { (name, content) -> Files.writeString(dir.resolve(name), content) }
        return dir
    }

    private fun liveEntry(title: String = "Current Episode"): SyndEntryImpl = makeEntry(title)

    /**
     * A feed whose one entry is already transcribed on disk, so the entry loop is a no-op
     * and anything the transcriber is asked to do came from recovery.
     */
    private fun settledFeed(
        dataDir: Path,
        title: String = "Current Episode",
    ): SyndFeed {
        val entry = liveEntry(title)
        orphanDir(
            dataDir,
            PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-'),
            "audio.mp3" to "bytes",
            "transcript.json" to "{}",
        )
        return makeFeed(entry)
    }

    private fun readJson(path: Path): JsonNode = ObjectMapper().readTree(Files.readString(path))

    @Test
    fun `recovers a directory that is no longer in the feed`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, tx, feedSvc) = buildPipeline(dataDir, listOf(podcast), makeFeed(liveEntry()))

        pipeline.run()

        assertTrue(tx.calls.contains(orphan.resolve("audio.mp3")), "the orphan's audio was never transcribed")
        assertTrue(Files.exists(orphan.resolve("transcript.json")))
        assertTrue(Files.exists(orphan.resolve("words.jsonl.gz")))
        assertTrue(Files.isDirectory(orphan), "the directory must keep its name; indexed rows point at it")
        assertTrue(feedSvc.downloads.none { it.second.startsWith(orphan) }, "audio was re-downloaded")

        val json = readJson(orphan.resolve("transcript.json"))
        assertEquals("deadbeef", json["_id"].asText())
        assertEquals("2019-01-01", json["episode_published_on"].asText())
        assertEquals("An Old Episode", json["episode_title"].asText())
        assertEquals(true, json["episode_metadata_recovered"].asBoolean())
        assertEquals("Test Podcast", json["podcast_title"].asText())
        assertEquals("science", json["podcast_collections"][0].asText())
        assertTrue(json["episode_relative_audio_path"].asText().isNotBlank())
    }

    @Test
    fun `writes null, not empty strings, for everything the feed would have supplied`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, _, _) = buildPipeline(dataDir, listOf(podcast), makeFeed(liveEntry()))

        pipeline.run()

        val json = readJson(orphan.resolve("transcript.json"))
        listOf(
            "episode_audio_link", "episode_web_link", "episode_image", "episode_summary",
            "episode_subtitle", "episode_authors", "episode_number", "episode_season", "episode_type",
        ).forEach { key ->
            assertTrue(json.has(key), "$key is missing entirely")
            // An empty string would slip past the web module's `!= null` guards and render a dead link.
            assertTrue(json[key].isNull, "$key should be null, was ${json[key]}")
        }
    }

    @Test
    fun `derives a duration from the transcript so the episode is not dropped from duration filters`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, _, _) =
            buildPipeline(
                dataDir,
                listOf(podcast),
                makeFeed(liveEntry()),
                vtt = whisperJson(Triple(0.0, 10.0, "Hello"), Triple(10.0, 1830.5, "Goodbye")),
            )

        pipeline.run()

        assertEquals(1830, readJson(orphan.resolve("transcript.json"))["episode_duration"].asInt())
    }

    @Test
    fun `leaves an orphan that already has a transcript alone`(
        @TempDir dataDir: Path,
    ) {
        val orphan =
            orphanDir(
                dataDir,
                "2019-01-01-deadbeef-An-Old-Episode",
                "audio.mp3" to "bytes",
                "transcript.json" to """{"_id":"deadbeef"}""",
            )
        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir))

        pipeline.run()

        assertTrue(tx.calls.isEmpty())
        assertEquals("""{"_id":"deadbeef"}""", Files.readString(orphan.resolve("transcript.json")))
    }

    @Test
    fun `does not recover a directory whose episode is still in the feed`(
        @TempDir dataDir: Path,
    ) {
        val entry = makeEntry("Current Episode")
        val dirName = PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-')
        orphanDir(dataDir, dirName, "audio.mp3" to "bytes")

        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), makeFeed(entry))
        pipeline.run()

        // The entry loop owns it: one transcription, driven by the feed, not by recovery.
        assertEquals(1, tx.calls.size)
        assertTrue(errors().isEmpty(), "unexpected errors: ${errors()}")
    }

    @Test
    fun `errors instead of recovering when the feed re-dated the same episode`(
        @TempDir dataDir: Path,
    ) {
        val guid = "https://example.com/guid/Re-dated"
        val reDated = makeEntry("Re-dated", publishedDate = Date(1_700_000_000_000L), guid = guid)
        val oldPrefix = "2010-05-05-${PodcastPipeline.episodeId(reDated)}"
        val stale = orphanDir(dataDir, "$oldPrefix-Re-dated", "audio.mp3" to "bytes")

        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), makeFeed(reDated))
        pipeline.run()

        assertFalse(Files.exists(stale.resolve("transcript.json")), "the stale directory must not be recovered")
        assertEquals(1, tx.calls.size, "only the live feed entry should be transcribed")
        assertTrue(errors().any { "different date" in it }, "expected a re-dated error, got ${errors()}")
    }

    @Test
    fun `warns and skips an orphan directory with no audio`(
        @TempDir dataDir: Path,
    ) {
        orphanDir(dataDir, "2019-01-01-deadbeef-No-Audio-Here")
        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir))

        pipeline.run()

        assertTrue(tx.calls.isEmpty())
        assertTrue(
            logged.list.any { it.level == Level.WARN && "neither audio nor a transcript" in it.formattedMessage },
        )
    }

    @Test
    fun `does not recover anything when the feed could not be fetched`(
        @TempDir dataDir: Path,
    ) {
        orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), feed = null)

        pipeline.run()

        assertTrue(tx.calls.isEmpty())
    }

    @Test
    fun `does not recover anything when the feed parsed but carries no entries`(
        @TempDir dataDir: Path,
    ) {
        orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), makeFeed())

        pipeline.run()

        // Every directory would look orphaned. Recovering the back catalogue on a publisher
        // hiccup is far worse than doing nothing.
        assertTrue(tx.calls.isEmpty())
    }

    @Test
    fun `recovers even when the skip threshold stopped the feed walk`(
        @TempDir dataDir: Path,
    ) {
        val entries = (1..3).map { makeEntry("Episode $it", guid = "guid-$it") }
        entries.forEach { entry ->
            orphanDir(
                dataDir,
                PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-'),
                "audio.mp3" to "bytes",
                "transcript.json" to "{}",
            )
        }
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")

        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), makeFeed(*entries.toTypedArray()), skipAfterConsecutive = 2)
        pipeline.run()

        assertEquals(listOf(orphan.resolve("audio.mp3")), tx.calls)
    }

    @Test
    fun `errors when the skip threshold hid an untranscribed episode that is still in the feed`(
        @TempDir dataDir: Path,
    ) {
        val entries = (1..3).map { makeEntry("Episode $it", guid = "guid-$it") }
        // The first two are done; the third is not, and the threshold stops before it.
        entries.take(2).forEach { entry ->
            orphanDir(
                dataDir,
                PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-'),
                "audio.mp3" to "bytes",
                "transcript.json" to "{}",
            )
        }
        orphanDir(
            dataDir,
            PodcastPipeline.getEpisodeDirName(entries[2]).replace(' ', '-'),
            "audio.mp3" to "bytes",
        )

        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), makeFeed(*entries.toTypedArray()), skipAfterConsecutive = 2)
        pipeline.run()

        assertTrue(tx.calls.isEmpty(), "the shadowed episode must be reported, not recovered degraded")
        assertTrue(
            errors().any { "skip threshold stopped before them" in it },
            "expected a shadowed-episode error, got ${errors()}",
        )
    }

    @Test
    fun `logs no error when every eligible entry has been reached`(
        @TempDir dataDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(dataDir, listOf(podcast), makeFeed(liveEntry()))
        pipeline.run()
        assertTrue(errors().isEmpty(), "unexpected errors: ${errors()}")
    }

    @Test
    fun `ignores directory names that are not episode slugs`(
        @TempDir dataDir: Path,
    ) {
        orphanDir(dataDir, "not-an-episode", "audio.mp3" to "bytes")
        orphanDir(dataDir, "unknown-date-abcd1234-Thing", "audio.mp3" to "bytes")
        val podDir = dataDir.resolve(PODCAST_NAME.replace(' ', '-'))
        Files.createDirectories(podDir)
        Files.writeString(podDir.resolve("podcasts.db"), "not a directory")

        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir))
        pipeline.run()

        assertTrue(tx.calls.isEmpty())
    }

    @Test
    fun `stops after the orphan recovery limit, newest first`(
        @TempDir dataDir: Path,
    ) {
        val oldest = orphanDir(dataDir, "2015-01-01-aaaaaaaa-Oldest", "audio.mp3" to "bytes")
        val middle = orphanDir(dataDir, "2018-01-01-bbbbbbbb-Middle", "audio.mp3" to "bytes")
        val newest = orphanDir(dataDir, "2021-01-01-cccccccc-Newest", "audio.mp3" to "bytes")

        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir), orphanRecoveryLimit = 2)
        pipeline.run()

        assertEquals(listOf(newest.resolve("audio.mp3"), middle.resolve("audio.mp3")), tx.calls)
        assertFalse(Files.exists(oldest.resolve("transcript.json")))
    }

    @Test
    fun `does nothing when orphan recovery is switched off`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir), recoverOrphans = false)

        pipeline.run()

        assertTrue(tx.calls.isEmpty())
        assertFalse(Files.exists(orphan.resolve("transcript.json")))
    }

    @Test
    fun `marks audio that decodes to nothing and never retries it`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-Silent", "audio.mp3" to "bytes")
        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir), vtt = """{"segments":[]}""")

        pipeline.run()
        // Two calls, not one: an empty decode is flagged, so the quality gate
        // spends its one retry before the marker abandons the episode for good.
        assertEquals(2, tx.calls.size)
        assertFalse(Files.exists(orphan.resolve("transcript.json")))
        assertTrue(Files.exists(orphan.resolve("recovery-failed")))

        // Without the marker this episode would be re-uploaded to whisper on every run, forever.
        pipeline.run()
        assertEquals(2, tx.calls.size)
    }

    @Test
    fun `does not mark an orphan when the transcriber throws, so an outage is retried`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-Server-Down", "audio.mp3" to "bytes")
        val (pipeline, tx, _) =
            buildPipeline(
                dataDir,
                listOf(podcast),
                settledFeed(dataDir),
                transcriberFails = { throw RuntimeException("whisper server returned 502") },
            )

        pipeline.run()
        assertFalse(Files.exists(orphan.resolve("recovery-failed")))

        pipeline.run()
        assertEquals(2, tx.calls.size, "a transient failure must be retried")
    }

    @Test
    fun `skips an orphan whose audio file is empty`(
        @TempDir dataDir: Path,
    ) {
        orphanDir(dataDir, "2019-01-01-deadbeef-Truncated", "audio.mp3" to "")
        val (pipeline, tx, _) = buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir))

        pipeline.run()

        assertTrue(tx.calls.isEmpty())
        assertTrue(logged.list.any { it.level == Level.WARN && "audio file is empty" in it.formattedMessage })
    }

    @Test
    fun `recovered episodes survive a round trip through the transcript reader`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, _, _) = buildPipeline(dataDir, listOf(podcast), makeFeed(liveEntry()))
        pipeline.run()

        val json = assertNotNull(readJson(orphan.resolve("transcript.json")))
        // index.py needs exactly these two to index a row at all.
        assertTrue(json["_id"].asText().isNotBlank())
        assertTrue(json["episode_transcript"].asText().contains("WEBVTT"))
    }

    /**
     * The check used to call findExistingEpisodeDir per prefix, re-listing the whole
     * podcast directory each time. Sized so the quadratic version would be visible.
     */
    @Test
    fun `reports every shadowed episode the skip threshold walked past`(
        @TempDir dataDir: Path,
    ) {
        val entries = (1..40).map { makeEntry("Episode %02d".format(it), guid = "guid-$it") }
        entries.take(3).forEach { entry ->
            orphanDir(
                dataDir,
                PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-'),
                "audio.mp3" to "bytes",
                "transcript.json" to "{}",
            )
        }
        entries.drop(3).forEach { entry ->
            orphanDir(dataDir, PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-'), "audio.mp3" to "bytes")
        }

        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), makeFeed(*entries.toTypedArray()), skipAfterConsecutive = 3)
        pipeline.run()

        assertTrue(tx.calls.isEmpty(), "shadowed episodes are reported, never recovered degraded")
        val shadowed = errors().single { "skip threshold stopped before them" in it }
        assertTrue("37 episodes" in shadowed, shadowed)
        // Every shadowed entry does have a directory, so the missing-directory report --
        // the same set arithmetic minus one term -- must stay silent.
        assertTrue(errors().none { "have no directory" in it }, errors().toString())
    }

    @Test
    fun `does not overwrite a transcript another instance wrote while this one decoded`(
        @TempDir dataDir: Path,
    ) {
        val orphan = orphanDir(dataDir, "2019-01-01-deadbeef-An-Old-Episode", "audio.mp3" to "bytes")
        val (pipeline, tx, _) =
            buildPipeline(
                dataDir,
                listOf(podcast),
                settledFeed(dataDir),
                // The other instance finishes while this one is still talking to whisper.
                onTranscribe = { Files.writeString(orphan.resolve("transcript.json"), """{"_id":"other"}""") },
            )

        pipeline.run()

        assertEquals(1, tx.calls.size)
        assertEquals("""{"_id":"other"}""", Files.readString(orphan.resolve("transcript.json")))
        assertFalse(Files.exists(orphan.resolve("words.jsonl.gz")), "the sidecar must not be clobbered either")
    }

    @Test
    fun `errors when the skip threshold left an eligible entry with no directory at all`(
        @TempDir dataDir: Path,
    ) {
        val entries = (1..4).map { makeEntry("Episode $it", guid = "guid-$it") }
        // The first three are done, so the threshold breaks before entry four is ever
        // looked at -- and entry four has no directory on disk to show for itself.
        entries.take(3).forEach { entry ->
            orphanDir(
                dataDir,
                PodcastPipeline.getEpisodeDirName(entry).replace(' ', '-'),
                "audio.mp3" to "bytes",
                "transcript.json" to "{}",
            )
        }

        val (pipeline, _, _) =
            buildPipeline(dataDir, listOf(podcast), makeFeed(*entries.toTypedArray()), skipAfterConsecutive = 3)
        pipeline.run()

        val missing = errors().single { "have no directory and were never" in it }
        assertTrue("1 eligible feed entries" in missing, missing)
        assertTrue(
            PodcastPipeline.episodeStablePrefix(entries[3]) in missing,
            "the report should name the entry it could not find: $missing",
        )
    }

    @Test
    fun `logs no missing-directory error when the threshold never fires`(
        @TempDir dataDir: Path,
    ) {
        val entries = (1..3).map { makeEntry("Episode $it", guid = "guid-$it") }
        val (pipeline, _, _) =
            buildPipeline(dataDir, listOf(podcast), makeFeed(*entries.toTypedArray()), skipAfterConsecutive = 20)
        pipeline.run()
        assertTrue(errors().isEmpty(), "unexpected errors: ${errors()}")
    }

    @Test
    fun `stops opening candidate directories once the limit is spent`(
        @TempDir dataDir: Path,
    ) {
        val orphans =
            listOf("2015-01-01-aaaaaaaa-Oldest", "2018-01-01-bbbbbbbb-Middle", "2021-01-01-cccccccc-Newest")
                .map { orphanDir(dataDir, it, "audio.mp3" to "bytes") }

        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir), orphanRecoveryLimit = 1)
        pipeline.run()

        assertEquals(listOf(orphans[2].resolve("audio.mp3")), tx.calls)
        // The two it did not recover were never opened either.
        assertTrue(
            logged.list.any { it.level == Level.INFO && "2 left for a later run" in it.formattedMessage },
            logged.list.map { it.formattedMessage }.toString(),
        )
    }

    @Test
    fun `the recovery limit applies per run, not per pipeline instance`(
        @TempDir dataDir: Path,
    ) {
        val first = orphanDir(dataDir, "2021-01-01-cccccccc-Newest", "audio.mp3" to "bytes")
        val second = orphanDir(dataDir, "2018-01-01-bbbbbbbb-Middle", "audio.mp3" to "bytes")

        val (pipeline, tx, _) =
            buildPipeline(dataDir, listOf(podcast), settledFeed(dataDir), orphanRecoveryLimit = 1)

        pipeline.run()
        assertEquals(listOf(first.resolve("audio.mp3")), tx.calls)

        // A second run gets its own budget; a counter that never reset would recover nothing.
        pipeline.run()
        assertEquals(listOf(first.resolve("audio.mp3"), second.resolve("audio.mp3")), tx.calls)
    }
}
