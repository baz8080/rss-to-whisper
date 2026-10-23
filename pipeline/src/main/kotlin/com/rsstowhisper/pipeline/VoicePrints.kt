package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.external.SpeakerDiarizer
import com.rsstowhisper.external.Voice
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Each diarized speaker's voice print, stored beside the turns in [FILENAME] under the same key, so turns diarized
 * again with other models make them stale too. Nothing here goes into the pair.
 */
object VoicePrints {
    const val FILENAME = "voice-prints.json"

    private val mapper = ObjectMapper()

    class Stored(
        val voices: Map<Int, Voice>,
        val audioSha256: String,
        /** Of the file itself, so names matched from it can tell when it changes. */
        val fileSha256: String,
    )

    /**
     * The prints for the audio with [audioSha256]; null when there are none or they are another audio's. Without a hash,
     * as for a transcript from before decodes recorded one, prints for audio of [audioBytes] are taken, as turns are.
     */
    fun read(
        episodeDir: Path,
        audioSha256: String?,
        audioBytes: Long? = null,
    ): Stored? {
        val (stored, sha) = stored(episodeDir) ?: return null
        val storedAudio = stored["audio_sha256"] as? String ?: return null
        if (!AudioKeyed.sameAudio(stored, audioSha256, audioBytes)) return null
        return voicesOf(stored)?.let { Stored(it, storedAudio, sha) }
    }

    fun current(
        episodeDir: Path,
        audioSha256: String,
        audioBytes: Long,
        models: Map<String, Any>,
    ): Boolean {
        val (stored, _) = stored(episodeDir) ?: return false
        return AudioKeyed.current(stored, audioSha256, audioBytes, models) && voicesOf(stored) != null
    }

    fun write(
        episodeDir: Path,
        audioSha256: String,
        audioBytes: Long,
        models: Map<String, Any>,
        voices: Map<Int, Voice>,
    ) {
        val record =
            mapOf("audio_sha256" to audioSha256, "audio_bytes" to audioBytes) + models +
                mapOf(
                    "voices" to
                        voices.toSortedMap().mapKeys { it.key.toString() }.mapValues {
                                (_, v) ->
                            mapOf("seconds" to v.seconds, "print" to v.print)
                        },
                )
        AudioKeyed.write(episodeDir, FILENAME, mapper.writeValueAsString(record))
    }

    /** Null for a file that is missing or will not parse: it reads as no prints, and they are made again. */
    private fun stored(episodeDir: Path): Pair<Map<*, *>, String>? {
        val file = episodeDir.resolve(FILENAME)
        if (!Files.exists(file)) return null
        return try {
            val bytes = Files.readAllBytes(file)
            mapper.readValue(bytes, Map::class.java) to sha256(bytes)
        } catch (e: IOException) {
            null
        }
    }

    private fun voicesOf(stored: Map<*, *>): Map<Int, Voice>? {
        val voices = stored["voices"] as? Map<*, *> ?: return null
        return voices.entries.associate {
                (k, v) ->
            (k.toString().toIntOrNull() ?: return null) to (SpeakerDiarizer.voiceOf(v) ?: return null)
        }
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance(
            "SHA-256",
        ).digest(bytes).joinToString("") { "%02x".format(it) }
}
