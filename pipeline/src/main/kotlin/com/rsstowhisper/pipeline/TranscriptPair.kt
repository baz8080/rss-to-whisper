package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.external.WhisperRun
import com.rsstowhisper.external.WhisperTranscription
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Whether an episode's `transcript.json` and `words.jsonl.gz` describe the same decode. */
internal object TranscriptPair {
    sealed interface Verdict

    data object Consistent : Verdict

    /** A transcript from before word timings, with no sidecar and no run to expect one from. */
    data object Unpaired : Verdict

    data class Diverged(val reason: String) : Verdict

    private val mapper = ObjectMapper()

    fun check(episodeDir: Path): Verdict {
        val transcript =
            try {
                mapper.readTree(Files.readString(episodeDir.resolve(PodcastPipeline.TRANSCRIPT_FILENAME)))
            } catch (e: Exception) {
                return Diverged("transcript.json cannot be read (${e.message})")
            }
        return check(transcript, episodeDir.resolve(WhisperTranscription.WORDS_FILENAME))
    }

    fun check(
        transcript: JsonNode,
        wordsPath: Path,
    ): Verdict {
        val run = transcript.path(WhisperRun.FIELD)
        val runId = run.path("run_id").textValue()
        val expectedWords = run.path("words").takeIf { it.isNumber }?.asInt()

        if (!Files.exists(wordsPath)) {
            return when {
                runId == null -> Unpaired
                expectedWords == 0 -> Consistent
                else -> Diverged("run $runId recorded ${expectedWords ?: "some"} words and words.jsonl.gz is missing")
            }
        }

        val words =
            try {
                GZIPInputStream(Files.newInputStream(wordsPath)).bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() }.map { mapper.readTree(it) }.toList()
                }
            } catch (e: Exception) {
                return Diverged("words.jsonl.gz cannot be read (${e.message})")
            }

        val wordRuns = words.mapNotNullTo(HashSet()) { it.path(WhisperRun.WORD_FIELD).textValue() }
        if (runId != null) {
            if (wordRuns != setOf(runId) && words.isNotEmpty()) {
                return Diverged("transcript.json is run $runId, words.jsonl.gz is ${wordRuns.ifEmpty { "unstamped" }}")
            }
            if (words.any { it.path(WhisperRun.WORD_FIELD).textValue() == null }) {
                return Diverged("words.jsonl.gz has lines with no run id")
            }
            if (expectedWords != null && expectedWords != words.size) {
                return Diverged("run $runId recorded $expectedWords words, words.jsonl.gz has ${words.size}")
            }
        } else if (wordRuns.isNotEmpty()) {
            return Diverged("words.jsonl.gz is run ${wordRuns.first()}, transcript.json records no run")
        }

        return checkText(cueTexts(transcript.path("episode_transcript").asText("")), words)
    }

    /**
     * Each word names its cue by ordinal, so every cue's words must rebuild that
     * cue: in order, whitespace aside, allowing for a word whisper gave no times
     * and the pipeline dropped. The rule episode.html applies before it trusts a sidecar.
     */
    private fun checkText(
        cues: List<String>,
        words: List<JsonNode>,
    ): Verdict {
        val bySegment = words.groupBy { it.path("seg").asInt(-1) }
        for ((segment, segmentWords) in bySegment.toSortedMap()) {
            if (segment !in cues.indices) {
                return Diverged("words.jsonl.gz has words for cue $segment, transcript has ${cues.size} cues")
            }
            val joined = letters(segmentWords.joinToString("") { it.path("w").asText("") })
            if (!isSubsequence(joined, letters(cues[segment]))) {
                return Diverged("cue $segment does not contain its words")
            }
        }
        return Consistent
    }

    /** Every cue's text, blank cues included: the word ordinals count them. */
    internal fun cueTexts(vtt: String): List<String> {
        val texts = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in vtt.lineSequence()) {
            when {
                "-->" in line -> {
                    current?.let { texts += it.toString() }
                    current = StringBuilder()
                }
                line.isBlank() -> {
                    current?.let { texts += it.toString() }
                    current = null
                }
                else -> current?.append(line)?.append('\n')
            }
        }
        current?.let { texts += it.toString() }
        return texts
    }

    private fun letters(text: String): String = text.filterNot { it.isWhitespace() }

    private fun isSubsequence(
        needle: String,
        haystack: String,
    ): Boolean {
        var at = 0
        for (ch in needle) {
            while (at < haystack.length && haystack[at] != ch) at++
            if (at == haystack.length) return false
            at++
        }
        return true
    }
}
