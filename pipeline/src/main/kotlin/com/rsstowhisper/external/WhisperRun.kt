package com.rsstowhisper.external

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Where one decode came from. The same [runId] goes into both files it produces,
 * so whether a `transcript.json` and a `words.jsonl.gz` belong together is an
 * exact comparison rather than a guess from their text.
 */
data class WhisperRun(
    val runId: String,
    val decodedAt: String,
    val serverUrl: String,
    /** Only what the config says: the server does not report the model it loaded. */
    val model: String?,
    /** The form fields sent with the audio. */
    val request: Map<String, String>,
    val audioSha256: String,
    val audioBytes: Long,
    val pipelineVersion: String?,
    /** How many lines `words.jsonl.gz` holds, so a decode with none is told apart from a lost file. */
    val words: Int,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "run_id" to runId,
            "decoded_at" to decodedAt,
            "server_url" to serverUrl,
            "model" to model,
            "request" to request,
            "audio_sha256" to audioSha256,
            "audio_bytes" to audioBytes,
            "pipeline_version" to pipelineVersion,
            "words" to words,
        )

    companion object {
        const val FIELD = "whisper_run"

        /** The key each `words.jsonl.gz` line carries the run id under. */
        const val WORD_FIELD = "run"

        fun newId(): String = UUID.randomUUID().toString()

        fun now(): String = Instant.now().toString()

        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val pipelineVersion: String? by lazy { WhisperRun::class.java.`package`?.implementationVersion }
    }
}
