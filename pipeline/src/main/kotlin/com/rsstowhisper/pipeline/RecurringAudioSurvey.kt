package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.rsstowhisper.external.Fingerprinter
import com.rsstowhisper.external.Fingerprinter.Companion.HOP
import com.rsstowhisper.external.Word
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** One show's recurring sounds, proposed for Barry to name: a candidates list to listen through, written to a folder. */
internal class RecurringAudioSurvey(
    private val fingerprinter: Fingerprinter,
    private val threads: Int,
    private val words: (Path) -> List<Word>,
) {
    class Episode(
        val name: String,
        val dataDir: Path,
        val audio: Path,
    )

    fun write(
        episodes: List<Episode>,
        out: Path,
    ): Boolean {
        val pool = Executors.newFixedThreadPool(threads)
        val fingerprints =
            try {
                episodes.map { e -> pool.submit(Callable { runCatching { fingerprinter.fingerprint(e.audio) } }) }.map { it.get() }
            } finally {
                pool.shutdownNow()
            }
        val failed = fingerprints.count { it.isFailure }
        fingerprints.mapNotNull { it.exceptionOrNull() }.forEach { logger.warn(it.message) }
        val heard = episodes.zip(fingerprints).mapNotNull { (e, f) -> f.getOrNull()?.let { e to it } }
        logger.info("Fingerprinted ${heard.size} of ${episodes.size} episodes; comparing them")
        val clusters =
            RecurringAudio.discover(heard.map { (e, f) -> e.name to f.values })
                .filter { it.episodes >= maxOf(MIN_EPISODES, (MIN_SHARE * heard.size).toInt()) }
        val byName = heard.associate { (e, f) -> e.name to (e to f.duration) }
        val spoken = HashMap<String, List<Word>>()

        fun said(o: RecurringAudio.Occurrence): String {
            val list =
                spoken.getOrPut(
                    o.episode,
                ) { runCatching { words(byName.getValue(o.episode).first.dataDir) }.getOrDefault(emptyList()) }
            val w = o.window
            return list.filter { it.start >= w.start && it.start < w.end }.joinToString("") { it.text }.trim()
        }
        Files.createDirectories(out)
        val rows = mutableListOf<Map<String, Any?>>()
        val tsv = StringBuilder(TSV_HEADER).append('\n')
        for ((n, cluster) in clusters.withIndex()) {
            val id = n + 1
            val occurrences = cluster.occurrences.map { o -> o to said(o) }
            val texts = occurrences.map { normalised(it.second) }
            val common = texts.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            val commonSaid = occurrences.firstOrNull { normalised(it.second) == common }?.second.orEmpty()
            val rep = cluster.representative.window
            val starts = cluster.occurrences.map { it.window.start }
            val fromEnd = cluster.occurrences.map { byName.getValue(it.episode).second - it.window.end }
            val lengths = cluster.occurrences.map { it.window.end - it.window.start }
            val examples =
                cluster.occurrences.distinctBy { it.episode }.let {
                        all ->
                    all.filterIndexed { i, _ -> i % maxOf(1, all.size / 3) == 0 }.take(3)
                }
            tsv.append(
                listOf(
                    id,
                    cluster.episodes,
                    fmt(cluster.episodes.toDouble() / heard.size),
                    fmt(median(starts)),
                    fmt(median(fromEnd)),
                    fmt(median(lengths)),
                    fmt(cluster.ber),
                    commonSaid.take(TEXT_COLUMN).replace('\t', ' '),
                    texts.count { it.isEmpty() },
                    texts.filter { it.isNotEmpty() }.distinct().size,
                    cluster.representative.episode,
                    fmt(rep.start),
                    fmt(rep.end),
                    examples.joinToString(" ") { "${it.episode}@${fmt(it.window.start)}" },
                ).joinToString("\t"),
            ).append('\n')
            rows +=
                mapOf(
                    "candidate" to id,
                    "episodes" to cluster.episodes,
                    "ber" to cluster.ber,
                    "representative" to mapOf("episode" to cluster.representative.episode, "from" to rep.start, "to" to rep.end),
                    "occurrences" to
                        occurrences.map { (o, text) ->
                            mapOf("episode" to o.episode, "from" to o.window.start, "to" to o.window.end, "said" to text)
                        },
                )
        }
        Files.writeString(out.resolve("candidates.tsv"), tsv)
        Files.writeString(
            out.resolve("candidates.json"),
            ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(
                mapOf("hop" to HOP, "episodes" to heard.size, "candidates" to rows),
            ),
        )
        logger.info("${clusters.size} recurring sounds in ${heard.size} episodes; written to $out")
        return failed == 0
    }

    private fun normalised(text: String) = text.lowercase().filter { it.isLetter() || it == ' ' }.trim().replace(Regex(" +"), " ")

    private fun median(values: List<Double>) = values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    companion object {
        private val logger = LoggerFactory.getLogger(RecurringAudioSurvey::class.java)
        private const val MIN_EPISODES = 3
        private const val MIN_SHARE = 0.05
        private const val TEXT_COLUMN = 120
        private const val TSV_HEADER =
            "candidate\tepisodes\tshare\tmedian_start_s\tmedian_from_end_s\tduration_s\tmean_ber\t" +
                "transcript_says\tno_words\tdistinct_texts\tsource_episode\tsource_from\tsource_to\texamples"
    }
}
