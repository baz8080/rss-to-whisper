package com.rsstowhisper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.rsstowhisper.audio.audioChapterReport
import com.rsstowhisper.audio.surveyAudioChapters
import com.rsstowhisper.feed.FeedService
import com.rsstowhisper.feed.feedMarkupReport
import com.rsstowhisper.pipeline.PodcastPipeline
import com.rsstowhisper.pipeline.RetranscribeRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
    val args: Args =
        try {
            parseArgs(argv)
        } catch (e: IllegalStateException) {
            System.err.println(e.message)
            exitProcess(2)
        }

    if (args.help) {
        println(USAGE)
        return
    }

    args.dumpFeedMarkup?.let { url ->
        val feed = FeedService().fetchFeed(url)
        if (feed == null) {
            System.err.println("Could not fetch $url")
            exitProcess(1)
        }
        print(feedMarkupReport(feed, args.dumpLimit))
        return
    }

    args.dumpAudioChapters?.let { directory ->
        val path = Path.of(directory)
        if (!Files.isDirectory(path)) {
            System.err.println("Not a directory: $directory")
            exitProcess(1)
        }
        print(audioChapterReport(surveyAudioChapters(path), args.dumpLimit))
        return
    }

    val config: AppConfig =
        try {
            AppConfig.load(args)
        } catch (e: Exception) {
            System.err.println("Cannot load configuration: ${e.message}")
            exitProcess(1)
        }

    if (config.verbose) {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        loggerContext.getLogger("com.rsstowhisper").level = Level.DEBUG
    }

    val logPath = installErrorLog(config.dataDirectory)
    val tally = installRunTally()

    val pipeline = PodcastPipeline(config)
    val ok =
        try {
            if (args.isRetranscribe) {
                pipeline.retranscribe(
                    RetranscribeRequest(
                        paths = args.retranscribePaths,
                        ids = args.retranscribeIds,
                        flagged = args.retranscribeFlagged,
                        limit = args.retranscribeLimit,
                        force = args.retranscribeForce,
                    ),
                )
            } else {
                pipeline.run()
            }
        } finally {
            // A run that died still has to say so: silence is the one outcome
            // indistinguishable from a run that never launched.
            println(tally.summary(logPath))
            // Without the log path -- it is a local filesystem path, and the
            // notification may land on a public topic.
            config.notifyUrl?.takeIf { it.isNotBlank() }?.let { Notifier().notify(it, tally.summary(null)) }
        }
    if (!ok) {
        exitProcess(1)
    }
}
