package com.rsstowhisper

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotifierTest {
    private fun clientReturning(
        responseCode: Int = 200,
        captureRequests: MutableList<okhttp3.Request>? = null,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                captureRequests?.add(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Error")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun `notify posts the body as text plain`() {
        val requests = mutableListOf<okhttp3.Request>()
        Notifier(clientReturning(captureRequests = requests))
            .notify("https://ntfy.sh/my-topic", "Run finished: 2 warnings, 0 errors")

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://ntfy.sh/my-topic", request.url.toString())
        assertEquals("text/plain; charset=utf-8", request.body!!.contentType().toString())

        val sink = okio.Buffer()
        request.body!!.writeTo(sink)
        assertEquals("Run finished: 2 warnings, 0 errors", sink.readUtf8())
    }

    /** The run has already finished, so nothing here is worth failing it for. */
    @Test
    fun `a notification that fails is a warning, not an exception`() {
        Notifier(clientReturning(responseCode = 500)).notify("https://ntfy.sh/my-topic", "body")

        val refused =
            OkHttpClient.Builder().addInterceptor { throw IOException("Connection refused") }.build()
        Notifier(refused).notify("https://ntfy.sh/my-topic", "body")
    }

    @Test
    fun `a notify_url that is not a url is reported and not sent`() {
        val requests = mutableListOf<okhttp3.Request>()
        Notifier(clientReturning(captureRequests = requests)).notify("ntfy.sh/my-topic", "body")

        assertTrue(requests.isEmpty())
    }
}
