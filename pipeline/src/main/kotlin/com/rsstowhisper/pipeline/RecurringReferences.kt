package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.escapeFilename
import com.rsstowhisper.external.Fingerprinter.Companion.HOP
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A recurring sound Barry named, cut from one episode's fingerprint. */
internal class Reference(
    val id: String,
    val kind: Kind,
    val episode: String,
    val from: Double,
    val to: Double,
    val fingerprint: IntArray,
    val note: String? = null,
) {
    enum class Kind(
        val label: String,
        /** Marked and not transcribed; the others are only known, so discovery stops proposing them. */
        val marks: Boolean,
    ) {
        INTRO("intro", true),
        OUTRO("outro", true),
        MUSIC("music", true),
        AD("ad", false),
        SPEECH("speech", false),
        ;

        companion object {
            fun of(label: String): Kind = entries.firstOrNull { it.label == label } ?: error("Unknown kind \"$label\"")
        }
    }
}

/** One row of a listening page's labels: a candidate, the letter it got, and the stretch to cut its reference from. */
internal class LabelRow(
    val show: String,
    val label: String,
    val candidate: String,
    val episode: String,
    val from: Double,
    val to: Double,
    val note: String?,
)

/** Each show's references, one `<show directory>.json` per show in a folder. */
internal class RecurringReferences(
    private val byShow: Map<String, List<Reference>>,
) {
    fun of(podcast: PodcastConfig): List<Reference> = byShow[escapeFilename(podcast.name)].orEmpty()

    val size: Int get() = byShow.values.sumOf { it.size }

    companion object {
        private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

        /** Chromaprint's default algorithm, the one fpcalc runs and [HOP] describes. */
        private const val ALGORITHM = 2

        /** About 2 s: shorter is too few values to align on. */
        const val MIN_VALUES = 16

        val NONE = RecurringReferences(emptyMap())

        private val LABEL_COLUMNS = listOf("show", "label", "candidate", "source_episode", "source_from", "source_to")

        /** The rows of a labels TSV, and a line for each row that cannot be read. */
        fun parseLabels(lines: List<String>): Pair<List<LabelRow>, List<String>> {
            val text = lines.filter { it.isNotBlank() }
            if (text.isEmpty()) return emptyList<LabelRow>() to listOf("no header row")
            val header = text.first().split('\t')
            val missing = LABEL_COLUMNS.filter { it !in header }
            if (missing.isNotEmpty()) return emptyList<LabelRow>() to listOf("no ${missing.joinToString(", ")} column")
            val rows = mutableListOf<LabelRow>()
            val problems = mutableListOf<String>()
            for ((n, line) in text.drop(1).withIndex()) {
                val cells = header.zip(line.split('\t')).toMap()
                val from = cells["source_from"]?.toDoubleOrNull()
                val to = cells["source_to"]?.toDoubleOrNull()
                if (LABEL_COLUMNS.any { cells[it].isNullOrBlank() } || from == null || to == null) {
                    problems += "row ${n + 2} has an empty, missing or unreadable cell"
                    continue
                }
                rows +=
                    LabelRow(
                        show = cells.getValue("show"),
                        label = cells.getValue("label"),
                        candidate = cells.getValue("candidate"),
                        episode = cells.getValue("source_episode"),
                        from = from,
                        to = to,
                        note = cells["note"]?.takeIf { it.isNotBlank() },
                    )
            }
            return rows to problems
        }

        /** The values of [episode] whose window is `[from, to]`, or null where that falls outside it or is too short. */
        fun cut(
            episode: IntArray,
            from: Double,
            to: Double,
        ): IntArray? {
            val range = RecurringAudio.values(from, to)
            if (range.first < 0 || range.last >= episode.size || range.count() < MIN_VALUES) return null
            return episode.copyOfRange(range.first, range.last + 1)
        }

        /** Refuses the folder rather than skip a file: a reference that quietly stopped acting would put lyrics back. */
        fun load(dir: Path): RecurringReferences {
            if (!Files.isDirectory(dir)) return NONE
            val files = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".json") }.toList() }.sorted()
            return RecurringReferences(files.associate { it.fileName.toString().removeSuffix(".json") to read(it) })
        }

        private fun read(file: Path): List<Reference> {
            val root = mapper.readTree(file.toFile())
            val refs =
                root.path("references").map { node ->
                    val where = "${file.fileName}, reference ${node.path("id").asText("?")}"
                    require(node.path("algorithm").asInt() == ALGORITHM) { "$where: not Chromaprint algorithm $ALGORITHM" }
                    val values = node.path("fingerprint").map { it.asLong().toInt() }.toIntArray()
                    require(values.size >= MIN_VALUES) { "$where: ${values.size} fingerprint values, fewer than $MIN_VALUES" }
                    Reference(
                        id = node.path("id").asText().also { require(it.isNotBlank()) { "$where: no id" } },
                        kind = Reference.Kind.of(node.path("kind").asText()),
                        episode = node.path("source").path("episode").asText(),
                        from = node.path("source").path("from").asDouble(),
                        to = node.path("source").path("to").asDouble(),
                        fingerprint = values,
                        note = node.path("note").takeIf(JsonNode::isTextual)?.asText(),
                    )
                }
            refs.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("${file.fileName}: id $it is used twice") }
            return refs
        }

        /** Adds [added] to [show]'s file, refusing an id it already has. */
        fun add(
            dir: Path,
            show: String,
            podcastName: String,
            added: List<Reference>,
        ) {
            Files.createDirectories(dir)
            val file = dir.resolve("$show.json")
            val existing = if (Files.exists(file)) read(file) else emptyList()
            added.firstOrNull { a -> existing.any { it.id == a.id } }?.let { error("$show already has a reference ${it.id}") }
            added.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { error("$show: id $it is added twice") }
            val all = existing + added
            val json =
                mapOf(
                    "podcast" to podcastName,
                    "references" to
                        all.map { r ->
                            mapOf(
                                "id" to r.id,
                                "kind" to r.kind.label,
                                "source" to mapOf("episode" to r.episode, "from" to r.from, "to" to r.to),
                                "algorithm" to ALGORITHM,
                                "hop" to HOP,
                                "note" to r.note,
                                "fingerprint" to r.fingerprint.map { it.toLong() and 0xFFFFFFFFL },
                            )
                        },
                )
            val partial = Files.createTempFile(dir, ".refs-", ".json")
            Files.writeString(partial, mapper.writeValueAsString(json))
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
