package com.rsstowhisper.external

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `transcribe throws on empty body`(
        @TempDir tmp: Path,
    ) {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .build()
                }
                .build()

        assertFailsWith<RuntimeException> {
            Transcriber("http://whisper-server", client).transcribe(mp3File(tmp))
        }
    }
}
