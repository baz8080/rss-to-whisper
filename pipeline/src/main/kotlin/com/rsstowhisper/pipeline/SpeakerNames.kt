package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.external.Voice
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The named voice a speaker matched, and how closely. */
internal class SpeakerName(
    val name: String,
    val similarity: Double,
)

/**
 * Who each diarized speaker is, stored beside the turns in [FILENAME]: its nearest named voice, where that is at least
 * [CUT] alike. Keyed on the audio, the prints and the named voices, so a change to any of them makes it stale.
 */
internal object SpeakerNames {
    const val FILENAME = "speaker-names.json"

    /** Cosine similarity at which a match is taken; on Barry's blind checks none above it named the wrong person. */
    const val CUT = 0.65

    private val mapper = ObjectMapper()

    fun match(
        voices: Map<Int, Voice>,
        candidates: List<NamedVoice>,
    ): Map<Int, SpeakerName> =
        voices.mapNotNull { (speaker, voice) ->
            candidates.map { it.name to cosine(voice.print, it.voice.print) }.maxByOrNull { it.second }
                ?.takeIf { it.second >= CUT }
                ?.let { (name, similarity) -> speaker to SpeakerName(name, similarity) }
        }.toMap()

    /** Both prints are unit length, so this is their dot product. */
    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun current(
        episodeDir: Path,
        audioSha256: String,
        printsSha256: String,
        voicesSha256: String,
    ): Boolean {
        val file = episodeDir.resolve(FILENAME)
        if (!Files.exists(file)) return false
        val stored =
            try {
                mapper.readValue(Files.readString(file), Map::class.java)
            } catch (e: IOException) {
                return false
            }
        return stored["audio_sha256"] == audioSha256 && stored["prints_sha256"] == printsSha256 &&
            stored["voices_sha256"] == voicesSha256 && stored["names"] is Map<*, *>
    }

    fun write(
        episodeDir: Path,
        audioSha256: String,
        printsSha256: String,
        voicesSha256: String,
        names: Map<Int, SpeakerName>,
    ) {
        val record =
            mapOf(
                "audio_sha256" to audioSha256,
                "prints_sha256" to printsSha256,
                "voices_sha256" to voicesSha256,
                "cut" to CUT,
                "names" to
                    names.toSortedMap().mapKeys { it.key.toString() }
                        .mapValues { (_, n) -> mapOf("name" to n.name, "similarity" to Math.round(n.similarity * 1000) / 1000.0) },
            )
        AudioKeyed.write(episodeDir, FILENAME, mapper.writeValueAsString(record))
    }
}
