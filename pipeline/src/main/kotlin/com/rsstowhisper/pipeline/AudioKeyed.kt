package com.rsstowhisper.pipeline

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What the files diarization writes beside the pair share: a key of the audio and models, and an atomic write. */
internal object AudioKeyed {
    /**
     * Whether [stored] is for the audio with [audioSha256]. Without a hash, as for a transcript from before decodes
     * recorded one, a file for audio of [audioBytes] is taken.
     */
    fun sameAudio(
        stored: Map<*, *>,
        audioSha256: String?,
        audioBytes: Long?,
    ): Boolean =
        if (audioSha256 != null) {
            stored["audio_sha256"] == audioSha256
        } else {
            audioBytes != null && (stored["audio_bytes"] as? Number)?.toLong() == audioBytes
        }

    fun current(
        stored: Map<*, *>,
        audioSha256: String,
        audioBytes: Long,
        models: Map<String, Any>,
    ): Boolean =
        stored["audio_sha256"] == audioSha256 && (stored["audio_bytes"] as? Number)?.toLong() == audioBytes &&
            models.all { (key, value) -> stored[key]?.toString() == value.toString() }

    fun write(
        episodeDir: Path,
        filename: String,
        json: String,
    ) {
        val partial = Files.createTempFile(episodeDir, ".${filename.substringBefore('.')}-", ".json")
        try {
            Files.writeString(partial, json)
            Files.move(partial, episodeDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
