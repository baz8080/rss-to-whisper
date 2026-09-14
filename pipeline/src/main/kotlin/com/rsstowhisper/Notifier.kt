package com.rsstowhisper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * POSTs the run's summary line as `text/plain`, which is what ntfy.sh expects.
 *
 * No auth and no retries: a run that finished is not worth holding open for a
 * notification that did not, so a failure here is a warning and nothing more.
 */
open class Notifier(
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
) {
    private val logger = LoggerFactory.getLogger(Notifier::class.java)

    open fun notify(
        url: String,
        body: String,
    ) {
        val request =
            try {
                Request.Builder().url(url).post(body.toRequestBody(TEXT_PLAIN)).build()
            } catch (e: IllegalArgumentException) {
                logger.warn("Not a usable notify_url (${e.message})")
                return
            }
        // Host only: an ntfy topic is the whole credential, and this goes to the error log.
        val host = request.url.host
        try {
            httpClient.newCall(request).execute().use {
                if (!it.isSuccessful) logger.warn("Notification to $host returned ${it.code}")
            }
        } catch (e: Exception) {
            logger.warn("Could not notify $host: ${e.message}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}
