package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.external.SpeakerTurn
import com.rsstowhisper.external.TimeWindow
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
    /** The voice that talks for at least half of [span], null where none does. */
    fun voiceOf(span: TimeWindow): Int? {
        val talk = HashMap<Int, Double>()
        for (turn in turns) {
            val overlap = minOf(span.end, turn.end) - maxOf(span.start, turn.start)
            if (overlap > 0) talk.merge(turn.speaker, overlap, Double::plus)
        }
        val (speaker, seconds) = talk.maxByOrNull { it.value } ?: return null
        return speaker.takeIf { seconds >= MIN_VOICE_SHARE * (span.end - span.start) }
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

        /** The turns for the audio with [audioSha256], null when there are none or they are another audio's. */
        fun read(
            episodeDir: Path,
            audioSha256: String?,
        ): SpeakerTurns? {
            val stored = stored(episodeDir) ?: return null
            if (audioSha256 == null || stored["audio_sha256"] != audioSha256) return null
            return SpeakerTurns(turnsOf(stored))
        }

        /** Whether [episodeDir] already has turns for this audio from these [models]. */
        fun current(
            episodeDir: Path,
            audioSha256: String,
            models: Map<String, Any>,
        ): Boolean {
            val stored = stored(episodeDir) ?: return false
            return stored["audio_sha256"] == audioSha256 && models.all { (key, value) -> stored[key]?.toString() == value.toString() }
        }

        fun write(
            episodeDir: Path,
            audioSha256: String,
            models: Map<String, Any>,
            turns: List<SpeakerTurn>,
        ) {
            val record =
                mapOf("audio_sha256" to audioSha256) + models + mapOf("turns" to turns.map { listOf(it.start, it.end, it.speaker) })
            val partial = Files.createTempFile(episodeDir, ".speaker-turns-", ".json")
            try {
                Files.writeString(partial, mapper.writeValueAsString(record))
                Files.move(partial, episodeDir.resolve(FILENAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }

        private fun stored(episodeDir: Path): Map<*, *>? {
            val file = episodeDir.resolve(FILENAME)
            if (!Files.exists(file)) return null
            return mapper.readValue(Files.readString(file), Map::class.java)
        }

        private fun turnsOf(stored: Map<*, *>): List<SpeakerTurn> =
            (stored["turns"] as? List<*>).orEmpty().map { entry ->
                val turn = entry as List<*>
                SpeakerTurn((turn[0] as Number).toDouble(), (turn[1] as Number).toDouble(), (turn[2] as Number).toInt())
            }
    }
}
