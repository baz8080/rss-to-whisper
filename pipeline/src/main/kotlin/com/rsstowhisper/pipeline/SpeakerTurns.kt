package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.external.SpeakerDiarizer
import com.rsstowhisper.external.SpeakerTurn
import com.rsstowhisper.external.TimeWindow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * An episode's speaker turns, stored beside the pair in [FILENAME] and keyed on the audio's SHA-256: repairs change
 * the words, never the audio, so they cannot make the turns stale. Nothing here goes into the pair itself.
 */
class SpeakerTurns(
    val turns: List<SpeakerTurn>,
) {
    /** The one voice that talks for at least half of [span]; null where none does, or where turns overlap and two do. */
    fun voiceOf(span: TimeWindow): Int? {
        val talk = HashMap<Int, Double>()
        for (turn in turns) {
            val overlap = minOf(span.end, turn.end) - maxOf(span.start, turn.start)
            if (overlap > 0) talk.merge(turn.speaker, overlap, Double::plus)
        }
        return talk.filterValues { it >= MIN_VOICE_SHARE * (span.end - span.start) }.keys.singleOrNull()
    }

    /**
     * Every lap one voice's and the voice changing at each: a line traded between people. Not merely crossing a
     * speaker change, since whisper looping over a conversation crosses them too, and its laps rarely sit in turns.
     */
    fun traded(laps: List<TimeWindow>): Boolean {
        if (laps.size < 2) return false
        val voices = laps.map { voiceOf(it) ?: return false }
        return voices.zipWithNext().all { (a, b) -> a != b }
    }

    companion object {
        const val FILENAME = "speaker-turns.json"

        private const val MIN_VOICE_SHARE = 0.5

        private val mapper = ObjectMapper()

        /**
         * The turns for the audio with [audioSha256], null when there are none or they are another audio's. A transcript
         * from before decodes recorded their audio has no hash to give, and takes turns for audio of its mp3's [audioBytes].
         */
        fun read(
            episodeDir: Path,
            audioSha256: String?,
            audioBytes: Long? = null,
        ): SpeakerTurns? {
            val stored = stored(episodeDir) ?: return null
            val same =
                if (audioSha256 != null) {
                    stored["audio_sha256"] == audioSha256
                } else {
                    audioBytes != null && (stored["audio_bytes"] as? Number)?.toLong() == audioBytes
                }
            return if (same) turnsOf(stored)?.let { SpeakerTurns(it) } else null
        }

        /** Whether [episodeDir] already has turns for this audio from these [models]. */
        fun current(
            episodeDir: Path,
            audioSha256: String,
            audioBytes: Long,
            models: Map<String, Any>,
        ): Boolean {
            val stored = stored(episodeDir) ?: return false
            return stored["audio_sha256"] == audioSha256 && (stored["audio_bytes"] as? Number)?.toLong() == audioBytes &&
                models.all { (key, value) -> stored[key]?.toString() == value.toString() } && turnsOf(stored) != null
        }

        fun write(
            episodeDir: Path,
            audioSha256: String,
            audioBytes: Long,
            models: Map<String, Any>,
            turns: List<SpeakerTurn>,
        ) {
            val record =
                mapOf("audio_sha256" to audioSha256, "audio_bytes" to audioBytes) + models +
                    mapOf("turns" to turns.map { listOf(it.start, it.end, it.speaker) })
            val partial = Files.createTempFile(episodeDir, ".speaker-turns-", ".json")
            try {
                Files.writeString(partial, mapper.writeValueAsString(record))
                Files.move(partial, episodeDir.resolve(FILENAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }

        /** Null for a file that is missing or will not parse: it reads as no turns, and is diarized again. */
        private fun stored(episodeDir: Path): Map<*, *>? {
            val file = episodeDir.resolve(FILENAME)
            if (!Files.exists(file)) return null
            return try {
                mapper.readValue(Files.readString(file), Map::class.java)
            } catch (e: IOException) {
                null
            }
        }

        private fun turnsOf(stored: Map<*, *>): List<SpeakerTurn>? {
            val list = stored["turns"] as? List<*> ?: return null
            return list.map { SpeakerDiarizer.turnOf(it) ?: return null }
        }
    }
}
