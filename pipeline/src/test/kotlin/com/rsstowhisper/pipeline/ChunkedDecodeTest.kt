package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.Transcriber
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkedDecodeTest {
    private val mapper = ObjectMapper()
    private val podcast = PodcastConfig(name = "Show", url = "https://feed")
    private val begins = Cue(1.0, 4.0, " So that is where the story begins.")
    private val odd = Cue(40.0, 44.0, " And then we found something odd.")
    private val speech = listOf(TimeWindow(1.0, 4.0), TimeWindow(40.0, 44.0))

    private fun transcription(cues: List<Cue>): WhisperTranscription {
        val words =
            cues.flatMapIndexed { index, cue ->
                val tokens = cue.text.trim().split(" ").filter { it.isNotEmpty() }
                val step = (cue.end - cue.start) / tokens.size.coerceAtLeast(1)
                tokens.mapIndexed { i, token -> Word(" $token", cue.start + i * step, cue.start + (i + 1) * step, 0.9, index) }
            }
        return WhisperTranscription.of(cues, words)
    }

    private fun serverJson(vararg cues: Cue): String {
        val words = transcription(cues.toList()).words
        val segments =
            cues.mapIndexed { i, cue ->
                mapOf(
                    "start" to cue.start,
                    "end" to cue.end,
                    "text" to cue.text,
                    "words" to
                        words.filter { it.segment == i }.map {
                            mapOf("word" to it.text, "start" to it.start, "end" to it.end, "probability" to 0.9)
                        },
                )
            }
        return mapper.writeValueAsString(mapOf("segments" to segments))
    }

    private fun episode(dataDir: Path): Path {
        val dir = dataDir.resolve("Show").resolve("2024-01-02-abcd1234-hello")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("audio.mp3"), "fake-mp3-bytes")
        val base = transcription(listOf(Cue(0.0, 44.0, " Thank you.")))
        Files.writeString(
            dir.resolve("transcript.json"),
            mapper.writeValueAsString(mapOf("episode_transcript" to base.vtt, "_id" to "abcd1234")),
        )
        base.writeWords(dir.resolve(WhisperTranscription.WORDS_FILENAME))
        return dir
    }

    private fun redecode(
        tempDir: Path,
        vtts: List<String>,
        speech: List<TimeWindow> = this.speech,
        language: String = Transcriber.DEFAULT_LANGUAGE,
        chunkedDecode: Boolean = true,
    ): Pair<Path, FakeTranscriber> {
        val dir = episode(tempDir)
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                vtts = vtts,
                language = language,
                speechDetector = FakeSpeechDetector(speech),
                chunkedDecode = chunkedDecode,
            )
        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), force = true))
        return dir to txSvc
    }

    @Test
    fun `with VAD, speech is decoded a chunk at a time, unprompted, joined in the file's time, less what is over non-speech`(
        @TempDir tempDir: Path,
    ) {
        val (dir, txSvc) = redecode(tempDir, listOf(serverJson(begins), serverJson(odd, Cue(44.3, 45.4, " Thanks for watching."))))

        assertEquals(listOf(0.8, 39.8), txSvc.windows.map { it?.start })
        assertEquals(listOf(false, false), txSvc.conditioned)
        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        val vtt = json.path("episode_transcript").asText()
        assertTrue("00:00:01.000 --> 00:00:04.000\n So that is where the story begins." in vtt, vtt)
        assertTrue("00:00:40.000 --> 00:00:44.000\n And then we found something odd." in vtt, vtt)
        assertTrue("Thanks for watching" !in vtt, vtt)
        assertEquals(2, json.path("whisper_run").path("chunking").path("chunks").asInt())
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    /** Measured: Blindboy Soda Jerk, 2740–2752 s. */
    @Test
    fun `a chunk that loops is decoded again with other settings, and the attempt without the loop kept`(
        @TempDir tempDir: Path,
    ) {
        val lap = listOf(" We're going to talk about", " the", " whiskey", " sour.")
        val looped = serverJson(*(0 until 16).map { Cue(1.0 + it * 0.2, 1.2 + it * 0.2, lap[it % 4]) }.toTypedArray())

        val (dir, txSvc) = redecode(tempDir, listOf(looped, serverJson(begins), serverJson(odd)))

        assertEquals(listOf(false, true, false), txSvc.conditioned)
        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        assertTrue("whiskey" !in json.path("episode_transcript").asText())
        assertEquals(1, json.path("whisper_run").path("chunking").path("retried").size())
    }

    /** Measured: We Have Ways 2021-04-27, 10:03: chunks back in lower case with no marks at all. */
    @Test
    fun `a chunk back without punctuation is decoded again, and the punctuated attempt kept`(
        @TempDir tempDir: Path,
    ) {
        val said = "and i think it's fair to say that the um i mean it's a it's a it's the um 28th and 38th army isn't it so the 28th army"
        val marked = "And I think it's fair to say that the, um, I mean, it's the 28th and 38th Army, isn't it? So, the 28th Army,"
        val flat = serverJson(Cue(1.0, 4.0, " $said is in that part"))
        val punctuated = serverJson(Cue(1.0, 4.0, " $marked is in that part."))

        val (dir, txSvc) = redecode(tempDir, listOf(flat, punctuated, serverJson(odd)))

        assertEquals(listOf(false, true, false), txSvc.conditioned)
        assertTrue("isn't it? So," in mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText())
    }

    @Test
    fun `an episode VAD hears nothing in, or of no set language, or with chunking off, is decoded whole`(
        @TempDir tempDir: Path,
    ) {
        val whole = listOf(serverJson(begins, odd))

        assertEquals(listOf<TimeWindow?>(null), redecode(tempDir.resolve("silent"), whole, speech = emptyList()).second.windows)
        assertEquals(
            listOf<TimeWindow?>(null),
            redecode(tempDir.resolve("auto"), whole, language = Transcriber.AUTO_LANGUAGE).second.windows,
        )
        assertEquals(listOf<TimeWindow?>(null), redecode(tempDir.resolve("off"), whole, chunkedDecode = false).second.windows)
    }
}
