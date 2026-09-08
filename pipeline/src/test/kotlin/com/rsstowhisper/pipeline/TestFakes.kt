package com.rsstowhisper.pipeline

import com.rometools.modules.itunes.EntryInformationImpl
import com.rometools.modules.itunes.types.Duration
import com.rometools.rome.feed.module.Module
import com.rometools.rome.feed.synd.SyndEnclosureImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rsstowhisper.AppConfig
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.Transcriber
import com.rsstowhisper.feed.FeedService
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Date

internal const val FAKE_SERVER_URL = "http://localhost:9000"

// The transcriber now returns verbose_json and the pipeline renders the WebVTT
// from it, so a stub has to speak the same language the server does.
internal fun whisperJson(vararg cues: Triple<Double, Double, String>): String =
    cues.joinToString(",", prefix = """{"task":"transcribe","segments":[""", postfix = "]}") { (start, end, text) ->
        """{"id":0,"start":$start,"end":$end,"text":" $text",""" +
            """"words":[{"word":" $text","start":$start,"end":$end,"probability":0.9}]}"""
    }

internal val MINIMAL_VTT = whisperJson(Triple(0.0, 1.0, "Hello world."))

internal open class FakeFeedService(
    private val feeds: Map<String, SyndFeed?>,
    private val downloadFails: (String) -> Boolean = { false },
) : FeedService() {
    val requestedUrls = mutableListOf<String>()

    // Written from the prefetch thread while a test's transcribe hook reads it.
    val downloads: MutableList<Pair<String, Path>> = Collections.synchronizedList(mutableListOf())

    override fun fetchFeed(url: String): SyndFeed? {
        requestedUrls.add(url)
        return feeds[url]
    }

    override fun downloadAudio(
        url: String,
        targetPath: Path,
    ): Boolean {
        // Mirrors the real skip-if-present contract, so tests can assert on it.
        if (Files.exists(targetPath)) return true
        // Created before the failure check, as the real service does, so a failed download still leaves the directory.
        Files.createDirectories(targetPath.parent)
        if (downloadFails(url)) return false
        downloads.add(url to targetPath)
        Files.writeString(targetPath, "fake-mp3-bytes")
        return true
    }
}

internal class FakeTranscriber(
    serverUrl: String,
    private val vtt: String,
    private val failWith: (() -> Nothing)? = null,
    /** Runs mid-transcription, so a test can simulate another instance finishing first. */
    private val onCall: ((Path) -> Unit)? = null,
) : Transcriber(serverUrl) {
    val calls = mutableListOf<Path>()

    override fun transcribe(audioPath: Path): String {
        calls.add(audioPath)
        onCall?.invoke(audioPath)
        failWith?.invoke()
        return vtt
    }
}

internal fun makeEntry(
    title: String?,
    audioUrl: String? = "https://cdn/ep.mp3",
    publishedDate: Date? = Date(1_700_000_000_000L),
    guid: String? = "https://example.com/guid/$title",
    durationSeconds: Int? = null,
): SyndEntryImpl =
    SyndEntryImpl().apply {
        this.title = title
        if (durationSeconds != null) {
            modules =
                mutableListOf<Module>(
                    EntryInformationImpl().apply {
                        duration = Duration(durationSeconds * 1000L)
                    },
                )
        }
        this.link = "https://example.com/ep"
        this.publishedDate = publishedDate
        this.uri = guid
        this.enclosures =
            if (audioUrl != null) {
                listOf(
                    SyndEnclosureImpl().apply {
                        url = audioUrl
                        type = "audio/mpeg"
                        length = 1L
                    },
                )
            } else {
                emptyList()
            }
    }

internal fun makeFeed(vararg entries: SyndEntryImpl): SyndFeed =
    SyndFeedImpl().apply {
        this.feedType = "rss_2.0"
        this.title = "Test Podcast"
        this.link = "https://example.com"
        this.entries = entries.toMutableList()
    }

internal fun buildPipeline(
    dataDir: Path,
    podcasts: List<PodcastConfig>,
    feed: SyndFeed?,
    vtt: String = MINIMAL_VTT,
    feedUrl: String = "https://feed",
    skipAfterConsecutive: Int = 20,
    minEpisodeDurationSeconds: Int = 150,
    recoverOrphans: Boolean = true,
    orphanRecoveryLimit: Int = 0,
    transcriberFails: (() -> Nothing)? = null,
    onTranscribe: ((Path) -> Unit)? = null,
    /** Supply one when the test needs a reference to it before the pipeline exists. */
    feedService: FakeFeedService? = null,
): Triple<PodcastPipeline, FakeTranscriber, FakeFeedService> {
    val config =
        AppConfig(
            dataDirectory = dataDir.toAbsolutePath().toString(),
            whisperServerUrl = FAKE_SERVER_URL,
            skipAfterConsecutive = skipAfterConsecutive,
            minEpisodeDurationSeconds = minEpisodeDurationSeconds,
            recoverOrphans = recoverOrphans,
            orphanRecoveryLimit = orphanRecoveryLimit,
            podcasts = podcasts,
        )
    val feedSvc = feedService ?: FakeFeedService(mapOf(feedUrl to feed))
    val txSvc = FakeTranscriber(FAKE_SERVER_URL, vtt, transcriberFails, onTranscribe)
    val pipeline =
        PodcastPipeline(
            config = config,
            feedService = feedSvc,
            transcriber = txSvc,
        )
    return Triple(pipeline, txSvc, feedSvc)
}
