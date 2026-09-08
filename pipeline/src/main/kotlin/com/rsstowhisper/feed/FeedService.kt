package com.rsstowhisper.feed

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val APP_VERSION = "2.0.0"

open class FeedService(private val httpClient: OkHttpClient = OkHttpClient()) {
    private val logger = LoggerFactory.getLogger(FeedService::class.java)
    private val userAgent = "rss-to-whisper/$APP_VERSION https://github.com/baz8080/rss_to_whisper"

    open fun fetchFeed(url: String): SyndFeed? {
        return try {
            val request =
                Request.Builder().url(url)
                    .addHeader("User-Agent", userAgent).build()
            val body =
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()
                    } else {
                        logger.error("Feed failed to load ${response.code}")
                        null
                    }
                }

            if (body != null) {
                SyndFeedInput().build(InputSource(StringReader(body)))
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to get feed: $url", e)
            null
        }
    }

    /** Downloads [url] to [targetPath]. Returns true when the file is present on disk. */
    open fun downloadAudio(
        url: String,
        targetPath: Path,
    ): Boolean {
        if (Files.exists(targetPath)) {
            logger.debug("Audio is already downloaded")
            return true
        }

        logger.debug("Downloading audio")
        val request =
            Request.Builder().url(url)
                .addHeader("User-Agent", userAgent).build()

        // The audio file is kept permanently, so a partial download must never
        // occupy the real name -- the existence check above would then treat a
        // truncated file as complete forever. Stage to a sibling and move only
        // once the stream has drained.
        val partialPath = targetPath.resolveSibling("${targetPath.fileName}.part")

        try {
            targetPath.parent?.let { Files.createDirectories(it) }
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Named, not positional: this runs on the prefetch thread, an episode ahead of the log around it.
                    logger.error("Error ${response.code} downloading $url")
                    return false
                }

                val body = response.body
                if (body == null) {
                    logger.error("No response body for $url")
                    return false
                }

                logger.debug("Writing... {}", targetPath)
                body.byteStream().use { input ->
                    Files.newOutputStream(partialPath).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            Files.move(partialPath, targetPath, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: Exception) {
            logger.error("Failed to download $url", e)
            return false
        } finally {
            runCatching { Files.deleteIfExists(partialPath) }
        }
    }
}
