package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.rsstowhisper.external.Voice
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A voice Barry named: one diarized speaker of one episode, and its print. */
internal class NamedVoice(
    val name: String,
    val show: String,
    /** `<podcast dir>/<episode dir>`. */
    val episode: String,
    val speaker: Int,
    val voice: Voice,
) {
    /** An ad's voice can turn up in any show's episodes, so it is matched in all of them. */
    val isAd: Boolean get() = name.startsWith(AD_PREFIX)

    companion object {
        const val AD_PREFIX = "ad:"
    }
}

/** One row of a voice-labels TSV. */
internal class VoiceLabel(
    val episode: String,
    val speaker: Int,
    val name: String,
)

/** Every show's named voices, one `<show directory>.json` per show in a folder, and a hash of them all. */
internal class SpeakerVoices(
    val all: List<NamedVoice>,
    /** Changes whenever a voice is added or renamed, so names matched against older voices are stale. */
    val sha256: String,
) {
    /** What a speaker of [show] is matched against: the show's own voices, and every ad's. */
    fun candidates(show: String): List<NamedVoice> = all.filter { it.show == show || it.isAd }

    companion object {
        private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

        val NONE = SpeakerVoices(emptyList(), "")

        private val LABEL_COLUMNS = listOf("episode", "speaker", "name")

        /** The rows of a labels TSV, and a line for each row that cannot be read. */
        fun parseLabels(lines: List<String>): Pair<List<VoiceLabel>, List<String>> {
            val text = lines.withIndex().filter { it.value.isNotBlank() }
            if (text.isEmpty()) return emptyList<VoiceLabel>() to listOf("no header row")
            val header = text.first().value.split('\t')
            val missing = LABEL_COLUMNS.filter { it !in header }
            if (missing.isNotEmpty()) return emptyList<VoiceLabel>() to listOf("no ${missing.joinToString(", ")} column")
            val rows = mutableListOf<VoiceLabel>()
            val problems = mutableListOf<String>()
            for ((n, line) in text.drop(1)) {
                val cells = header.zip(line.split('\t')).toMap()
                val speaker = cells["speaker"]?.trim()?.toIntOrNull()
                val episode = cells["episode"]?.trim().orEmpty()
                val name = cells["name"]?.trim().orEmpty()
                if (speaker == null || name.isEmpty() || episode.split('/').size != 2) {
                    problems += "line ${n + 1} has an empty, missing or unreadable cell"
                    continue
                }
                rows += VoiceLabel(episode, speaker, name)
            }
            return rows to problems
        }

        /** Refuses the folder rather than skip a file: a voice that quietly stopped matching would unname a show. */
        fun load(dir: Path): SpeakerVoices {
            if (!Files.isDirectory(dir)) return NONE
            val files = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".json") }.toList() }.sorted()
            val bytes = files.map { it.fileName.toString().toByteArray() + Files.readAllBytes(it) }
            val all = files.zip(bytes).flatMap { (file, _) -> read(file) }
            return SpeakerVoices(all, VoicePrints.sha256(bytes.fold(ByteArray(0)) { a, b -> a + b }))
        }

        private fun read(file: Path): List<NamedVoice> {
            val show = file.fileName.toString().removeSuffix(".json")
            return mapper.readTree(file.toFile()).path("voices").map { node ->
                val where = "${file.fileName}, ${node.path("episode").asText("?")} speaker ${node.path("speaker").asText("?")}"
                val print = node.path("print").map { it.floatValue() }.toFloatArray()
                require(print.isNotEmpty()) { "$where: no print" }
                NamedVoice(
                    name = node.path("name").asText().also { require(it.isNotBlank()) { "$where: no name" } },
                    show = show,
                    episode = node.path("episode").asText().also { require(it.isNotBlank()) { "$where: no episode" } },
                    speaker = node.path("speaker").asInt(),
                    voice = Voice(node.path("seconds").asDouble(), print),
                )
            }
        }

        /** Adds [added] to [show]'s file; a speaker it has already is renamed. */
        fun add(
            dir: Path,
            show: String,
            podcastName: String,
            added: List<NamedVoice>,
        ) {
            Files.createDirectories(dir)
            val file = dir.resolve("$show.json")
            val replaced = added.map { it.episode to it.speaker }.toSet()
            val kept = (if (Files.exists(file)) read(file) else emptyList()).filter { (it.episode to it.speaker) !in replaced }
            val all =
                (kept + added.associateBy { it.episode to it.speaker }.values).sortedWith(
                    compareBy({ it.name }, { it.episode }, { it.speaker }),
                )
            val json =
                mapOf(
                    "podcast" to podcastName,
                    "voices" to
                        all.map { v ->
                            mapOf(
                                "name" to v.name,
                                "episode" to v.episode,
                                "speaker" to v.speaker,
                                "seconds" to v.voice.seconds,
                                "print" to v.voice.print,
                            )
                        },
                )
            val partial = Files.createTempFile(dir, ".voices-", ".json")
            Files.writeString(partial, mapper.writeValueAsString(json))
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
