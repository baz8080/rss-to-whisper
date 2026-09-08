package com.rsstowhisper.feed

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MINIMAL_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0">
      <channel>
        <title>Test Feed</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <item><title>Episode One</title><link>https://example.com/ep1</link></item>
      </channel>
    </rss>
    """.trimIndent()

class FeedServiceTest {
    private fun clientReturning(
        body: String = "",
        responseCode: Int = 200,
        captureHeaders: MutableList<String?>? = null,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                captureHeaders?.add(chain.request().header("User-Agent"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Error")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

    private fun clientThrowing(exception: IOException): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { throw exception }
            .build()

    // --- fetchFeed ---

    @Test
    fun `fetchFeed sends user-agent header`() {
        val headers = mutableListOf<String?>()
        FeedService(clientReturning(MINIMAL_RSS, captureHeaders = headers))
            .fetchFeed("https://example.com/feed.rss")

        val userAgent = headers.single()
        assertNotNull(userAgent)
        assertTrue(userAgent.startsWith("rss-to-whisper/"))
        assertTrue(userAgent.contains("github.com/baz8080/rss_to_whisper"))
    }

    @Test
    fun `fetchFeed parses valid RSS and returns feed`() {
        val feed = FeedService(clientReturning(MINIMAL_RSS)).fetchFeed("https://example.com/feed.rss")

        assertNotNull(feed)
        assertEquals("Test Feed", feed.title)
        assertEquals(1, feed.entries.size)
        assertEquals("Episode One", feed.entries[0].title)
    }

    @Test
    fun `fetchFeed returns null on non-200 response`() {
        val feed = FeedService(clientReturning(responseCode = 404)).fetchFeed("https://example.com/feed.rss")
        assertNull(feed)
    }

    @Test
    fun `fetchFeed returns null on network exception`() {
        val feed =
            FeedService(clientThrowing(IOException("connection refused")))
                .fetchFeed("https://example.com/feed.rss")
        assertNull(feed)
    }

    // --- downloadAudio ---

    @Test
    fun `downloadAudio sends user-agent header`() {
        val headers = mutableListOf<String?>()
        FeedService(clientReturning(captureHeaders = headers, responseCode = 404))
            .downloadAudio("https://example.com/episode.mp3", Path.of("episode.mp3"))

        val userAgent = headers.single()
        assertNotNull(userAgent)
        assertTrue(userAgent.startsWith("rss-to-whisper/"))
        assertTrue(userAgent.contains("github.com/baz8080/rss_to_whisper"))
    }

    @Test
    fun `downloadAudio writes body to target path and returns true`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        val ok = FeedService(clientReturning(body = "audio-bytes")).downloadAudio("https://example.com/ep.mp3", target)

        assertTrue(ok)
        assertTrue(Files.exists(target))
        assertEquals("audio-bytes", Files.readString(target))
        assertTrue(Files.notExists(tmp.resolve("episode.mp3.part")))
    }

    /** The pipeline stopped creating episode directories when the download moved to a prefetch thread. */
    @Test
    fun `downloadAudio creates a missing parent directory`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("Show/2026-09-08-abcd1234-Ep/episode.mp3")
        val ok = FeedService(clientReturning(body = "audio-bytes")).downloadAudio("https://example.com/ep.mp3", target)

        assertTrue(ok)
        assertEquals("audio-bytes", Files.readString(target))
    }

    @Test
    fun `downloadAudio returns true without a request when file already present`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        Files.writeString(target, "existing")

        assertTrue(FeedService(clientReturning(responseCode = 500)).downloadAudio("https://example.com/ep.mp3", target))
    }

    @Test
    fun `downloadAudio returns false on non-200 response`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        val ok = FeedService(clientReturning(responseCode = 503)).downloadAudio("https://example.com/ep.mp3", target)
        assertEquals(false, ok)
    }

    @Test
    fun `downloadAudio returns false on network exception`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        val ok =
            FeedService(clientThrowing(IOException("connection reset")))
                .downloadAudio("https://example.com/ep.mp3", target)
        assertEquals(false, ok)
    }

    /**
     * The audio file is kept permanently now, so the existence check in
     * downloadAudio doubles as a completeness check. A failed download must
     * leave nothing behind at either name, or the episode is poisoned forever.
     */
    @Test
    fun `downloadAudio leaves no partial file behind when the stream fails`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        FeedService(clientThrowing(IOException("connection reset")))
            .downloadAudio("https://example.com/ep.mp3", target)

        assertTrue(Files.notExists(target))
        assertTrue(Files.notExists(tmp.resolve("episode.mp3.part")))
    }

    @Test
    fun `downloadAudio skips request when file already exists`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        Files.writeString(target, "existing")

        var requestCount = 0
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body("".toResponseBody()).build()
                }
                .build()

        FeedService(client).downloadAudio("https://example.com/ep.mp3", target)

        assertEquals(0, requestCount)
        assertEquals("existing", Files.readString(target))
    }

    @Test
    fun `downloadAudio does not create file on non-200 response`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        FeedService(clientReturning(responseCode = 503)).downloadAudio("https://example.com/ep.mp3", target)

        assertTrue(Files.notExists(target))
    }

    @Test
    fun `downloadAudio does not throw on network exception`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("episode.mp3")
        FeedService(clientThrowing(IOException("timeout")))
            .downloadAudio("https://example.com/ep.mp3", target)

        assertTrue(Files.notExists(target))
    }
}
