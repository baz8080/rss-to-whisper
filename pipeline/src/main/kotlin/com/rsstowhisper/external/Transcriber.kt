package com.rsstowhisper.external

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit

open class Transcriber(
    private val serverUrl: String,
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.MINUTES)
            .build(),
    /**
     * Maximum segment length in characters. Without this, whisper.cpp will
     * happily emit a single segment spanning an entire episode -- 139 episodes
     * in the corpus are one cue covering the whole show, the worst over 7,200
     * seconds. A cue that long cannot carry a usable timestamp.
     *
     * ~200 characters is roughly 30-40 words, about 12 seconds of speech, so
     * it only bites on the runaway cases.
     */
    private val maxLen: Int = DEFAULT_MAX_LEN,
    /**
     * Whisper's `initial_prompt`, carried into every decode window.
     *
     * Whisper intermittently drops into a mode where it emits no punctuation
     * and no capitals for an entire episode. That is not cosmetic: it segments
     * on sentence structure, so with no full stops the cue boundaries stop
     * tracking speech and every timestamp derived from them is unreliable. It
     * hit 654 episodes, and 13 survived every attempt to re-decode them.
     *
     * An initial prompt fixed **all 13**. Measured on the same set:
     *
     *   initial_prompt      13/13
     *   whisper large-v3    10/13
     *   best VAD parameter   9/13
     *   Silero VAD v6.2.0    6/13
     *   another threshold    0/13
     *
     * It works because this is a DECODER mode, not a segmentation problem --
     * every VAD setting only changes what audio reaches the decoder, while a
     * prompt conditions the decoder itself, and punctuation is a style.
     *
     * Deliberately generic prose. An initial prompt biases VOCABULARY as well
     * as style, so anything domain-specific would contaminate transcripts.
     * Verified on a repaired episode: zero occurrences of any prompt fragment,
     * word count within 5% of the original.
     */
    private val initialPrompt: String = DEFAULT_INITIAL_PROMPT,
    /**
     * The language [initialPrompt] is written in.
     *
     * A prompt is not language-neutral, so it only rides with a decode in its
     * own language -- see the guard in [transcribe]. Supplying a prompt in
     * another language means setting this to match it.
     */
    private val promptLanguage: String = DEFAULT_LANGUAGE,
    /**
     * Beam width. whisper.cpp runs
     * `strategy = beam_size > 1 ? BEAM_SEARCH : GREEDY`, and the server
     * defaults to greedy while whisper-cli defaults to 5 -- so adopting the
     * server silently put this pipeline on greedy decoding.
     *
     * Greedy's characteristic failure here is repetition: it locks onto a
     * phrase and emits it for minutes. Measured across the first eleven
     * regenerated shows it hits 0.7%-5.0% of episodes per show, and the repair
     * pass that cleans them up runs beam. That pass has now fixed **57 of 57**
     * such episodes, most on its first attempt.
     *
     * A paired trial on one show -- same audio, same model, same fields, only
     * this value moved -- found beam equal or better on healthy material:
     *
     *   median punctuation/word   0.1546 -> 0.1611
     *   sub-threshold loops       5 of 5 cleared
     *   a shredded episode        0.74 s/cue -> 2.42, punctuation 0.109 -> 0.208
     *   clamped / unpunctuated    0 either way
     *
     * The costs are real but small: roughly 30% more decode time, and about
     * 12% fewer cues. Coarser cues used to matter because the cue was the
     * floor on how precisely a span boundary could be placed; with per-word
     * times in `words.jsonl.gz` it no longer is.
     *
     * Set to 1 for greedy.
     */
    private val beamSize: Int = DEFAULT_BEAM_SIZE,
) {
    private val logger = LoggerFactory.getLogger(Transcriber::class.java)

    /**
     * The whisper.cpp server decodes the upload with miniaudio, which detects the
     * format from the content and resamples to 16 kHz mono itself, so the mp3 can
     * go straight up without a local ffmpeg pass.
     */
    open fun transcribe(
        audioPath: Path,
        language: String = DEFAULT_LANGUAGE,
    ): String {
        // whisper.cpp looks the code up in a map keyed by lower-case codes and
        // never checks the result: an unmatched one returns -1, and the caller
        // adds it to the language token's base index, so "EN" silently selects
        // the wrong token instead of failing. A hand-edited pods.yaml is the
        // likely source, and whisper only ever emits lower case itself.
        val code = language.lowercase()

        val bodyBuilder =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioPath.fileName.toString(),
                    audioPath.toFile().asRequestBody("audio/mpeg".toMediaType()),
                )
                .addFormDataPart("language", code)
                // verbose_json rather than vtt: per-word start/end are gated on
                // token_timestamps, which is already on below, so the decode
                // ALREADY computes these times and VTT discards them. A cue is
                // the finest a span boundary can be placed, and 13.4% of the
                // labelled advertisement airtime downstream currently sits in a
                // cue too long to resolve. Word times take that to ~0.2s.
                .addFormDataPart("response_format", "verbose_json")
                // Explicit, and off. server.cpp:833 overrides only the fields a
                // request actually sends, so omitting this silently inherits
                // whatever the server was launched with -- which is how the
                // corpus ended up with no record of its own VAD state.
                //
                // It has to be off: with VAD on, token timestamps stay in
                // VAD-compressed time while segment timestamps are remapped to
                // real time. Measured on one episode, the two drift apart from
                // -1.79s at the start to -6.51s by the end, the gap being the
                // silence VAD removed. Every word time would be early by a
                // growing, episode-dependent, invisible amount.
                .addFormDataPart("vad", "false")
                // whisper.cpp only applies max_len when token_timestamps is on:
                // the wrap call is nested inside `if (params.token_timestamps)`
                // in whisper_full. Sending max_len alone is silently ignored.
                .addFormDataPart("token_timestamps", "true")
                .addFormDataPart("max_len", maxLen.toString())
                // Cut on word boundaries rather than mid-token.
                .addFormDataPart("split_on_word", "true")
                .addFormDataPart("beam_size", beamSize.toString())

        // The prompt rides only with the language it is written in. It biases
        // VOCABULARY as well as style (see [initialPrompt]), so conditioning a
        // French decode on English prose is exactly the contamination that
        // comment exists to avoid -- and it would be carried into every window.
        // For "auto" it is worse than useless: an English prompt skews whisper's
        // own language detection toward English before it decodes anything, so
        // the detection the setting exists to enable is what it would break.
        val promptApplies = code == promptLanguage.lowercase()
        if (initialPrompt.isNotBlank() && promptApplies) {
            bodyBuilder.addFormDataPart("prompt", initialPrompt)
            // Without this the prompt conditions only the FIRST window, so an
            // episode that degrades part-way through still degrades -- which is
            // exactly what a whole-episode failure looks like. 13/13 fixed with
            // it, 12/13 without.
            bodyBuilder.addFormDataPart("carry_initial_prompt", "true")
        } else if (initialPrompt.isNotBlank()) {
            logger.debug(
                "Decoding as {}; the initial prompt is {} so it is not being sent",
                code,
                promptLanguage,
            )
        }

        val requestBody = bodyBuilder.build()

        val request =
            Request.Builder()
                .url("$serverUrl/inference")
                .post(requestBody)
                .build()

        logger.debug("Sending {} to whisper server", audioPath.fileName)

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Whisper server returned ${response.code}: ${response.body?.string()}")
            }
            response.body?.string() ?: throw RuntimeException("Whisper server returned empty body")
        }
    }

    companion object {
        const val DEFAULT_MAX_LEN = 200

        /**
         * Whisper takes an ISO 639-1 code, or "auto" to detect from the audio.
         * Every feed in the corpus is English, so that stays the default and a
         * podcast opts out of it rather than into it.
         */
        const val DEFAULT_LANGUAGE = "en"

        /** See [beamSize]. 1 is greedy, which is what the server defaults to. */
        const val DEFAULT_BEAM_SIZE = 5

        /** See [initialPrompt]. Ordinary punctuated prose, nothing domain-specific. */
        const val DEFAULT_INITIAL_PROMPT =
            "Hello, and welcome back to the show. Today we're going to talk about " +
                "a few different things, and I think you'll enjoy it. Let's get started."
    }
}
