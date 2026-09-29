package com.rsstowhisper.pipeline

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.external.WhisperRun
import com.rsstowhisper.external.WhisperTranscription
import java.io.EOFException
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** Whether an episode's `transcript.json` and `words.jsonl.gz` describe the same decode. */
internal object TranscriptPair {
    sealed interface Verdict

    data object Consistent : Verdict

    /** A transcript from before word timings, with no sidecar and no run to expect one from. */
    data object Unpaired : Verdict

    data class Diverged(val reason: String) : Verdict

    /** The files could not be read just now, which says nothing about whether they agree. */
    data class Unreadable(val reason: String) : Verdict

    private val mapper = ObjectMapper()

    fun check(episodeDir: Path): Verdict {
        val transcript =
            try {
                mapper.readTree(Files.readString(episodeDir.resolve(PodcastPipeline.TRANSCRIPT_FILENAME)))
            } catch (e: JsonProcessingException) {
                return Diverged("transcript.json is not JSON (${e.originalMessage})")
            } catch (e: CharacterCodingException) {
                return Diverged("transcript.json is not UTF-8 (${e.message})")
            } catch (e: IOException) {
                return Unreadable("transcript.json cannot be read (${e.message})")
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

        // Neither exists nor absent: a network volume that could not answer.
        if (!Files.exists(wordsPath) && !Files.notExists(wordsPath)) return Unreadable("words.jsonl.gz cannot be found or ruled out")
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
            } catch (e: ZipException) {
                return Diverged("words.jsonl.gz is corrupt (${e.message})")
            } catch (e: EOFException) {
                return Diverged("words.jsonl.gz is truncated (${e.message})")
            } catch (e: JsonProcessingException) {
                return Diverged("words.jsonl.gz has a line that is not JSON (${e.originalMessage})")
            } catch (e: IOException) {
                return Unreadable("words.jsonl.gz cannot be read (${e.message})")
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

        return checkCues(parseCues(transcript.path("episode_transcript").asText("")), words)
    }

    /**
     * Each word names its cue by ordinal, so every cue's words must rebuild that
     * cue: in order, whitespace aside, allowing for a word whisper gave no times
     * and the pipeline dropped. The rule episode.html applies before it trusts a sidecar.
     */
    private fun checkCues(
        cues: List<VttCue>,
        words: List<JsonNode>,
    ): Verdict {
        val bySegment = words.groupBy { it.path("seg").asInt(-1) }
        var timed = 0
        var misplaced = 0
        for ((segment, segmentWords) in bySegment.toSortedMap()) {
            if (segment !in cues.indices) {
                return Diverged("words.jsonl.gz has words for cue $segment, transcript has ${cues.size} cues")
            }
            val cue = cues[segment]
            val joined = letters(segmentWords.joinToString("") { it.path("w").asText("") })
            if (!isSubsequence(joined, letters(cue.text))) {
                return Diverged("cue $segment does not contain its words")
            }
            if (cue.start != null && cue.end != null) {
                timed++
                val fits =
                    WhisperTranscription.wordsFitCue(
                        cue.start,
                        cue.end,
                        segmentWords.first().path("s").asDouble(),
                        segmentWords.last().path("e").asDouble(),
                    )
                if (!fits) misplaced++
            }
        }
        if (timed > 0 && misplaced.toDouble() / timed > WhisperTranscription.MAX_MISPLACED_WORD_SHARE) {
            return Diverged("$misplaced of $timed cues have their words outside the cue's time")
        }
        return Consistent
    }

    internal class VttCue(val start: Double?, val end: Double?, val text: String)

    /** Every cue's text, blank cues included: the word ordinals count them. */
    internal fun cueTexts(vtt: String): List<String> = parseCues(vtt).map { it.text }

    internal fun parseCues(vtt: String): List<VttCue> {
        val cues = mutableListOf<VttCue>()
        var times: List<Double?>? = null
        var text: StringBuilder? = null

        fun flush() {
            val t = times ?: return
            cues += VttCue(t[0], t[1], text.toString())
            times = null
        }
        for (line in vtt.lineSequence()) {
            val timing = TIMING.find(line)
            when {
                timing != null -> {
                    flush()
                    times = timing.groupValues.drop(1).map { seconds(it) }
                    text = StringBuilder()
                }
                line.isBlank() -> flush()
                times != null -> text?.append(line)?.append('\n')
            }
        }
        flush()
        return cues
    }

    /** Null for a timestamp that does not parse, which some externally edited files carry. */
    private fun seconds(stamp: String): Double? {
        val parts = stamp.split(':')
        if (parts.size != 3 || parts[0].length > 6) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3600.0 + m * 60 + s
    }

    /** Cue text can hold an arrow of its own, so only a line that starts with a stamp opens a cue. */
    private val TIMING = Regex("""^\s*([0-9:.]+)\s*-->\s*([0-9:.]+)""")

    /**
     * Whitespace aside, and U+FFFD: a server that sends one word per token splits a
     * character across two, and each half arrives as a replacement character.
     */
    private fun letters(text: String): String = text.filterNot { it.isWhitespace() || it == '\uFFFD' }

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
