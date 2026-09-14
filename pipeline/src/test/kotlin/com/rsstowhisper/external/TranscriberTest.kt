package com.rsstowhisper.external

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriberTest {
    private fun clientReturning(
        body: String = "",
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
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

    private fun mp3File(tmp: Path): Path = tmp.resolve("audio.mp3").also { Files.writeString(it, "fake-mp3-data") }

    private fun partValue(
        request: okhttp3.Request,
        name: String,
    ): String {
        val body = request.body as okhttp3.MultipartBody
        val part = body.parts.single { it.headers!!["Content-Disposition"]!!.contains("name=\"$name\"") }
        val sink = okio.Buffer()
        part.body.writeTo(sink)
        return sink.readUtf8()
    }

    @Test
    fun `transcribe sends the configured beam size, and 1 means greedy`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = requests))
            .transcribe(mp3File(tmp))
        assertEquals(
            Transcriber.DEFAULT_BEAM_SIZE.toString(),
            partValue(requests.single(), "beam_size"),
        )

        val greedy = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", beamSize = 1, httpClient = clientReturning("{}", captureRequests = greedy))
            .transcribe(mp3File(tmp))
        assertEquals("1", partValue(greedy.single(), "beam_size"))
    }

    @Test
    fun `transcribe posts to the inference endpoint`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("http://whisper-server/inference", request.url.toString())
    }

    @Test
    fun `transcribe sends file as multipart with correct fields`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        val body = requests.single().body as okhttp3.MultipartBody
        val partNames = body.parts.mapNotNull { it.headers?.get("Content-Disposition") }
        assertTrue(partNames.any { it.contains("name=\"file\"") })
        assertTrue(partNames.any { it.contains("name=\"language\"") })
        assertTrue(partNames.any { it.contains("name=\"response_format\"") })
        assertTrue(partNames.any { it.contains("name=\"vad\"") })
        assertTrue(partNames.any { it.contains("name=\"beam_size\"") })
    }

    @Test
    fun `transcribe sends the requested language, defaulting to English`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = requests))
            .transcribe(mp3File(tmp))
        assertEquals(Transcriber.DEFAULT_LANGUAGE, partValue(requests.single(), "language"))

        val french = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = french))
            .transcribe(mp3File(tmp), "fr")
        assertEquals("fr", partValue(french.single(), "language"))
    }

    /**
     * The mp3 goes up as-is; the whisper.cpp server decodes and resamples it with
     * miniaudio, so there is no local ffmpeg pass and nothing named audio.wav.
     */
    @Test
    fun `transcribe uploads the mp3 as audio mpeg`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        val body = requests.single().body as okhttp3.MultipartBody
        val filePart =
            body.parts.single { it.headers?.get("Content-Disposition")?.contains("filename=") == true }
        assertTrue(filePart.headers!!["Content-Disposition"]!!.contains("filename=\"audio.mp3\""))
        assertEquals("audio/mpeg", filePart.body.contentType().toString())
    }

    /**
     * whisper.cpp ignores max_len unless token_timestamps is also set -- the
     * segment wrap is nested inside `if (params.token_timestamps)`. Sending one
     * without the other silently does nothing, so assert they travel together.
     */
    @Test
    fun `transcribe caps segment length so a cue cannot span the episode`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        val fields = formFields(requests.single().body as okhttp3.MultipartBody)
        assertEquals("true", fields["token_timestamps"])
        assertEquals(Transcriber.DEFAULT_MAX_LEN.toString(), fields["max_len"])
        assertEquals("true", fields["split_on_word"])
    }

    /**
     * The prompt is what fixed all 13 episodes that no VAD setting could, and
     * `carry_initial_prompt` is what makes it apply past the first window --
     * 13/13 with it, 12/13 without. They have to travel together.
     */
    @Test
    fun `transcribe sends an initial prompt and carries it across windows`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", httpClient = clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        val fields = formFields(requests.single().body as okhttp3.MultipartBody)
        assertEquals(Transcriber.DEFAULT_INITIAL_PROMPT, fields["prompt"])
        assertEquals("true", fields["carry_initial_prompt"])
    }

    /**
     * An initial prompt biases vocabulary as well as style, so it has to be
     * possible to turn off without editing the class.
     */
    @Test
    fun `transcribe omits the prompt fields when the prompt is blank`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", initialPrompt = "", httpClient = clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        val fields = formFields(requests.single().body as okhttp3.MultipartBody)
        assertEquals(null, fields["prompt"])
        assertEquals(null, fields["carry_initial_prompt"])
    }

    /**
     * The prompt is English prose, and an initial prompt biases vocabulary as
     * well as style. Conditioning a French decode on it would pull the
     * transcript toward English -- carried into every window, since
     * carry_initial_prompt travels with it.
     */
    @Test
    fun `transcribe omits the prompt when decoding another language`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = requests))
            .transcribe(mp3File(tmp), "fr")

        val fields = formFields(requests.single().body as okhttp3.MultipartBody)
        assertEquals("fr", fields["language"])
        assertEquals(null, fields["prompt"])
        assertEquals(null, fields["carry_initial_prompt"])
    }

    /**
     * Worse than useless under "auto": an English prompt skews whisper's own
     * language detection toward English before it decodes anything, breaking
     * the detection the setting exists to enable.
     */
    @Test
    fun `transcribe omits the prompt when the language is auto-detected`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = requests))
            .transcribe(mp3File(tmp), "auto")

        val fields = formFields(requests.single().body as okhttp3.MultipartBody)
        assertEquals(null, fields["prompt"])
        assertEquals(null, fields["carry_initial_prompt"])
    }

    /** A prompt in the decode's own language still rides along. */
    @Test
    fun `transcribe sends a prompt written in the language being decoded`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber(
            "http://whisper-server",
            initialPrompt = "Bonjour, et bienvenue dans cette emission.",
            promptLanguage = "fr",
            httpClient = clientReturning("{}", captureRequests = requests),
        ).transcribe(mp3File(tmp), "fr")

        val fields = formFields(requests.single().body as okhttp3.MultipartBody)
        assertEquals("Bonjour, et bienvenue dans cette emission.", fields["prompt"])
        assertEquals("true", fields["carry_initial_prompt"])
    }

    /** An upper-case code reaches whisper as the wrong language, not as an error. */
    @Test
    fun `transcribe lower-cases the language code it posts`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = requests))
            .transcribe(mp3File(tmp), "EN")

        assertEquals("en", partValue(requests.single(), "language"))
    }

    /** Whisper's codes are lower-case, but a hand-edited pods.yaml need not be. */
    @Test
    fun `the prompt language match is case-insensitive`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", clientReturning("{}", captureRequests = requests))
            .transcribe(mp3File(tmp), "EN")

        assertEquals(
            Transcriber.DEFAULT_INITIAL_PROMPT,
            formFields(requests.single().body as okhttp3.MultipartBody)["prompt"],
        )
    }

    @Test
    fun `transcribe honours a custom max length`(
        @TempDir tmp: Path,
    ) {
        val requests = mutableListOf<okhttp3.Request>()
        Transcriber("http://whisper-server", maxLen = 150, httpClient = clientReturning("WEBVTT\n", captureRequests = requests))
            .transcribe(mp3File(tmp))

        assertEquals("150", formFields(requests.single().body as okhttp3.MultipartBody)["max_len"])
    }

    /** Read back the simple (non-file) multipart form fields as name -> value. */
    private fun formFields(body: okhttp3.MultipartBody): Map<String, String> =
        body.parts.mapNotNull { part ->
            val disposition = part.headers?.get("Content-Disposition") ?: return@mapNotNull null
            if (disposition.contains("filename=")) return@mapNotNull null
            val name = Regex("name=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1) ?: return@mapNotNull null
            val sink = okio.Buffer()
            part.body.writeTo(sink)
            name to sink.readUtf8()
        }.toMap()

    @Test
    fun `transcribe returns response body on success`(
        @TempDir tmp: Path,
    ) {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHello world\n"
        val result = Transcriber("http://whisper-server", clientReturning(vtt)).transcribe(mp3File(tmp))
        assertEquals(vtt, result)
    }

    @Test
    fun `transcribe throws on non-200 response`(
        @TempDir tmp: Path,
    ) {
        val ex =
            assertFailsWith<RuntimeException> {
                Transcriber("http://whisper-server", clientReturning(responseCode = 500))
                    .transcribe(mp3File(tmp))
            }
        assertTrue(ex.message!!.contains("500"))
    }

    /**
     * whisper.cpp answers 400 "failed to read audio data" for an mp3 it cannot
     * decode, and 500 for one it cannot process. Both are the server talking
     * about THIS request, so neither is evidence about the next episode -- and
     * counting them would let three bad audio files abandon the run, then
     * abandon every later run too, since the episodes ahead of them are already
     * transcribed and never decode to clear the count.
     */
    @Test
    fun `a status the server chose about this request is not the server being gone`(
        @TempDir tmp: Path,
    ) {
        for (code in listOf(400, 404, 413, 500)) {
            assertFailsWith<TranscriberRejected>("$code was treated as an unreachable server") {
                Transcriber("http://whisper-server", clientReturning(responseCode = code))
                    .transcribe(mp3File(tmp))
            }
        }
    }

    /** A gateway with nothing behind it, and whisper.cpp's own 503 while its model loads. */
    @Test
    fun `a gateway saying its upstream is gone is the server being gone`(
        @TempDir tmp: Path,
    ) {
        for (code in listOf(502, 503, 504)) {
            assertFailsWith<TranscriberUnavailable>("$code was not treated as an unreachable server") {
                Transcriber("http://whisper-server", clientReturning(responseCode = code))
                    .transcribe(mp3File(tmp))
            }
        }
    }

    /**
     * A decode that found no speech still answers with a segment list, so an
     * empty body is the server being broken rather than the audio being
     * silent -- and the next episode will fare no better.
     */
    @Test
    fun `transcribe throws on empty body`(
        @TempDir tmp: Path,
    ) {
        assertFailsWith<TranscriberUnavailable> {
            Transcriber("http://whisper-server", clientReturning("")).transcribe(mp3File(tmp))
        }
        assertFailsWith<TranscriberUnavailable> {
            Transcriber("http://whisper-server", clientReturning("  \n")).transcribe(mp3File(tmp))
        }
    }

    /**
     * A server that is not listening surfaces as an IOException from OkHttp.
     * Left as one, it reads to the pipeline like any other bad episode, and a
     * whole run is spent downloading audio to fail on it one file at a time.
     */
    @Test
    fun `transcribe reports an unreachable server as unavailable rather than an IO error`(
        @TempDir tmp: Path,
    ) {
        val ex =
            assertFailsWith<TranscriberUnavailable> {
                Transcriber("http://whisper-server", clientThrowing()).transcribe(mp3File(tmp))
            }
        assertTrue(ex.message!!.contains("http://whisper-server"))
        assertTrue(ex.cause is IOException)
    }

    @Test
    fun `ping gets the base url`() {
        val requests = mutableListOf<okhttp3.Request>()
        assertTrue(Transcriber("http://whisper-server", clientReturning(captureRequests = requests)).ping())

        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("http://whisper-server/", request.url.toString())

        assertTrue(Transcriber("http://whisper-server", clientReturning(responseCode = 201)).ping())
    }

    @Test
    fun `ping is false only when nothing is there`() {
        assertFalse(Transcriber("http://whisper-server", clientThrowing()).ping())
        for (code in listOf(502, 503, 504)) {
            assertFalse(Transcriber("http://whisper-server", clientReturning(responseCode = code)).ping(), "$code")
        }
    }

    /**
     * `--request-path` moves whisper.cpp's page off `/`, and a proxy may route
     * only `/inference`. Neither is a reason to refuse to run: something
     * answered, which is all the preflight is asking.
     */
    @Test
    fun `ping is true for a server that answers the base url with an error`() {
        for (code in listOf(401, 404, 500)) {
            assertTrue(Transcriber("http://whisper-server", clientReturning(responseCode = code)).ping(), "$code")
        }
    }

    /**
     * The decode client reads for ninety minutes, which is right for a decode
     * and useless for a preflight: a server that accepts the connection and
     * then says nothing would hang the run before it started.
     */
    @Test
    fun `ping does not inherit the read timeout sized for a decode`() {
        var readTimeoutMillis = -1
        val decodeClient =
            OkHttpClient.Builder()
                .readTimeout(90, TimeUnit.MINUTES)
                .addInterceptor { chain ->
                    readTimeoutMillis = chain.readTimeoutMillis()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody())
                        .build()
                }
                .build()

        assertTrue(Transcriber("http://whisper-server", decodeClient).ping())
        assertTrue(
            readTimeoutMillis in 1..TimeUnit.MINUTES.toMillis(1),
            "ping read timeout was ${readTimeoutMillis}ms",
        )
    }

    /**
     * Only `execute()` used to be guarded, so a server killed while its
     * response was still streaming failed at the body read instead -- which is
     * the same unreachable server, arriving as a raw IOException that the
     * pipeline's per-episode catch would swallow.
     */
    @Test
    fun `a server that stops mid-response is an unreachable server`(
        @TempDir tmp: Path,
    ) {
        val truncated =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            object : okhttp3.ResponseBody() {
                                override fun contentType() = null

                                override fun contentLength() = -1L

                                override fun source(): okio.BufferedSource =
                                    object : okio.ForwardingSource(okio.Buffer()) {
                                        override fun read(
                                            sink: okio.Buffer,
                                            byteCount: Long,
                                        ): Long = throw IOException("Connection reset")
                                    }.buffer()
                            },
                        )
                        .build()
                }
                .build()

        assertFailsWith<TranscriberUnavailable> {
            Transcriber("http://whisper-server", truncated).transcribe(mp3File(tmp))
        }
    }

    /** A typo in `.env` should be reported, not thrown out of the preflight. */
    @Test
    fun `ping is false for a url that is not one`() {
        assertFalse(Transcriber("localhost:8080", clientReturning()).ping())
    }

    /** An OkHttp client that cannot reach anything, which is what a server that is down looks like. */
    private fun clientThrowing(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { throw IOException("Connection refused") }
            .build()
}
