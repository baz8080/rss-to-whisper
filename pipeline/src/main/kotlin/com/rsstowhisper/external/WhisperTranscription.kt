package com.rsstowhisper.external

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * One word, with the times whisper.cpp already computed for it.
 *
 * @param segment index of the cue this word came from, so the sidecar can be
 *   joined back to the WebVTT without re-aligning the two.
 * @param probability the decoder's own confidence. A run of low values is a
 *   far better hallucination signal than anything derivable from the text.
 */
data class Word(
    val text: String,
    val start: Double,
    val end: Double,
    val probability: Double,
    val segment: Int,
)

/**
 * A parsed `verbose_json` response.
 *
 * The server can return WebVTT directly, and did until now. It is derived here
 * instead because the two artifacts must describe the same decode: whisper is
 * not deterministic across runs, so asking for both formats would mean two
 * decodes whose cues and words could disagree in ways nothing downstream could
 * detect. One request, one parse, two files written from it.
 */
data class WhisperTranscription(
    val vtt: String,
    val words: List<Word>,
    val lastCueEnd: Double? = null,
) {
    val isEmpty: Boolean get() = vtt.isBlank() || vtt.trim() == VTT_HEADER

    /**
     * Where the decoded speech ends, in whole seconds. Not the file's duration --
     * it undershoots trailing silence -- but the feed carries no duration for an
     * episode that has aged out of it, and null drops the episode out of the web
     * module's duration filters entirely.
     */
    val durationSeconds: Int?
        get() =
            listOfNotNull(words.lastOrNull()?.end, lastCueEnd)
                .filter { it.isFinite() && it > 0 }
                .maxOrNull()
                ?.toInt()

    /** Newline-delimited JSON, gzipped. ~274 KB per episode before compression. */
    fun writeWords(path: Path) {
        val mapper = ObjectMapper()
        Files.newOutputStream(path).use { raw ->
            GZIPOutputStream(raw).use { gz ->
                BufferedWriter(OutputStreamWriter(gz, StandardCharsets.UTF_8)).use { out ->
                    words.forEach { w ->
                        val node = mapper.createObjectNode()
                        node.put("w", w.text)
                        node.put("s", w.start)
                        node.put("e", w.end)
                        node.put("p", w.probability)
                        node.put("seg", w.segment)
                        out.write(mapper.writeValueAsString(node))
                        out.newLine()
                    }
                }
            }
        }
    }

    companion object {
        const val VTT_HEADER = "WEBVTT"
        const val WORDS_FILENAME = "words.jsonl.gz"

        private val mapper = ObjectMapper()

        fun parse(json: String): WhisperTranscription {
            val root = mapper.readTree(json)
            val segments = root.path("segments")
            if (!segments.isArray) return WhisperTranscription("$VTT_HEADER\n\n", emptyList())

            val vtt = StringBuilder(VTT_HEADER).append("\n\n")
            val words = mutableListOf<Word>()
            var lastCueEnd: Double? = null
            segments.forEachIndexed { index, segment ->
                val start = segment.path("start").asDouble()
                val end = segment.path("end").asDouble()
                lastCueEnd = end
                vtt.append(timestamp(start)).append(" --> ").append(timestamp(end)).append('\n')
                vtt.append(segment.path("text").asText()).append("\n\n")
                segment.path("words").forEach { word ->
                    words += word.toWord(index) ?: return@forEach
                }
            }
            return WhisperTranscription(vtt.toString(), words, lastCueEnd)
        }

        /**
         * Absent when the server was asked for timestamps it did not produce.
         * Dropping the word is right: a word with no time cannot place a
         * boundary, and a zero would place one at the start of the episode.
         *
         * Some words arrive with `end` before `start`. It comes from
         * `whisper_exp_compute_token_level_timestamps` in whisper.cpp, whose
         * monotonicity fix-up is guarded on `j > 0` and so never repairs a
         * segment's first token, and runs per segment and so cannot order
         * across a segment boundary. Over a 17,553-episode corpus it hit 2.1%
         * of episodes, and within an affected one about 4.8% of its words;
         * 54% of the inversions sit on the first token of their segment.
         *
         * The pair is clamped to `start <= end` here rather than left to
         * readers, so the file is correct on disk and no consumer has to
         * rediscover the defect. Clamp, not drop: an inverted word still marks
         * a real position in the stream, and dropping it would shift every
         * index built on the sidecar.
         */
        private fun JsonNode.toWord(segment: Int): Word? {
            if (!has("start") || !has("end")) return null
            val start = path("start").asDouble()
            val end = path("end").asDouble()
            return Word(
                text = path("word").asText(),
                start = minOf(start, end),
                end = end,
                probability = path("probability").asDouble(),
                segment = segment,
            )
        }

        /** `HH:MM:SS.mmm`, the WebVTT the server itself emits. */
        internal fun timestamp(seconds: Double): String {
            val safe = if (seconds.isFinite() && seconds > 0) seconds else 0.0
            val millisTotal = Math.round(safe * 1000.0)
            val hours = millisTotal / 3_600_000
            val minutes = millisTotal % 3_600_000 / 60_000
            val secs = millisTotal % 60_000 / 1000
            val millis = millisTotal % 1000
            return "%02d:%02d:%02d.%03d".format(hours, minutes, secs, millis)
        }
    }
}
