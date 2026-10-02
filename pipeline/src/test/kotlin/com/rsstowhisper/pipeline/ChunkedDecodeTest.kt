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
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkedDecodeTest {
    private val mapper = ObjectMapper()
    private val podcast = PodcastConfig(name = "Show", url = "https://feed")
    private val begins = Cue(1.0, 10.0, " So that is where the story begins, and it goes on for quite a while after that.")
    private val odd = Cue(40.0, 44.0, " And then we found something odd.")
    private val speech = listOf(TimeWindow(1.0, 10.0), TimeWindow(40.0, 44.0))

    /** 45 s of MPEG-1 Layer III frames at 44.1 kHz, 1152 samples each, so clips can be cut from it. */
    private fun mp3(): ByteArray {
        val frame = ByteArray(417).also { f -> intArrayOf(0xFF, 0xFB, 0x90, 0x64).forEachIndexed { i, v -> f[i] = v.toByte() } }
        return (0 until (45.0 * SAMPLE_RATE / FRAME_SAMPLES).toInt() + 1).flatMap { frame.asList() }.toByteArray()
    }

    /** Where a clip cut for a window starting at [t] starts: whisper answers in times from there. */
    private fun clipStart(t: Double) = floor(t * SAMPLE_RATE / FRAME_SAMPLES) * FRAME_SAMPLES / SAMPLE_RATE

    private fun transcription(cues: List<Cue>): WhisperTranscription {
        val words =
            cues.flatMapIndexed { index, cue ->
                val tokens = cue.text.trim().split(" ").filter { it.isNotEmpty() }
                val step = (cue.end - cue.start) / tokens.size.coerceAtLeast(1)
                tokens.mapIndexed { i, token -> Word(" $token", cue.start + i * step, cue.start + (i + 1) * step, 0.9, index) }
            }
        return WhisperTranscription.of(cues, words)
    }

    /** The server's answer for a clip whose window starts at [window], given cues in the file's time. */
    private fun serverJson(
        window: Double,
        vararg cues: Cue,
        wordsShift: Double = 0.0,
    ): String {
        val at = clipStart(window)
        val words = transcription(cues.toList()).words
        val segments =
            cues.mapIndexed { i, cue ->
                mapOf(
                    "start" to cue.start - at,
                    "end" to cue.end - at,
                    "text" to cue.text,
                    "words" to
                        words.filter { it.segment == i }.map {
                            mapOf("word" to it.text, "start" to it.start - at + wordsShift, "end" to it.end - at + wordsShift, "probability" to 0.9)
                        },
                )
            }
        return mapper.writeValueAsString(mapOf("segments" to segments))
    }

    private fun episode(
        dataDir: Path,
        audio: ByteArray,
    ): Path {
        val dir = dataDir.resolve("Show").resolve("2024-01-02-abcd1234-hello")
        Files.createDirectories(dir)
        Files.write(dir.resolve("audio.mp3"), audio)
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
        vadFails: Boolean = false,
        audio: ByteArray = mp3(),
        language: String = Transcriber.DEFAULT_LANGUAGE,
        chunkedDecode: Boolean = true,
        qualityRetry: Boolean = true,
    ): Pair<Path, FakeTranscriber> {
        val dir = episode(tempDir, audio)
        val (pipeline, txSvc, _) =
            buildPipeline(
                tempDir,
                listOf(podcast),
                feed = null,
                vtts = vtts,
                language = language,
                qualityRetry = qualityRetry,
                speechDetector = FakeSpeechDetector(speech, fails = vadFails),
                chunkedDecode = chunkedDecode,
            )
        pipeline.retranscribe(RetranscribeRequest(paths = listOf("Show/${dir.fileName}"), force = true))
        return dir to txSvc
    }

    private fun vtt(dir: Path) = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("episode_transcript").asText()

    @Test
    fun `with VAD, speech is decoded a chunk at a time, unprompted, joined in the file's time, less what is over non-speech`(
        @TempDir tempDir: Path,
    ) {
        val (dir, txSvc) =
            redecode(
                tempDir,
                listOf(serverJson(0.8, begins), serverJson(39.8, odd, Cue(44.3, 45.0, " Thanks for watching."))),
            )

        assertEquals(listOf(0.8, 39.8), txSvc.windows.map { it?.start })
        assertEquals(listOf(false, false), txSvc.conditioned)
        val vtt = vtt(dir)
        assertTrue("00:00:01.000 --> 00:00:10.000\n So that is where the story begins," in vtt, vtt)
        assertTrue("00:00:40.000 --> 00:00:44.000\n And then we found something odd." in vtt, vtt)
        assertTrue("Thanks for watching" !in vtt, vtt)
        val json = mapper.readTree(Files.readString(dir.resolve("transcript.json")))
        assertEquals(2, json.path("whisper_run").path("chunking").path("chunks").asInt())
        assertEquals(TranscriptPair.Consistent, TranscriptPair.check(dir))
    }

    /** Measured: Blindboy Soda Jerk, 2740–2752 s. */
    @Test
    fun `a chunk that loops is decoded again with other settings, and the attempt without the loop kept`(
        @TempDir tempDir: Path,
    ) {
        val lap = listOf(" We're going to talk about", " the", " whiskey", " sour.")
        val looped = serverJson(0.8, *(0 until 45).map { Cue(1.0 + it * 0.2, 1.2 + it * 0.2, lap[it % 4]) }.toTypedArray())

        val (dir, txSvc) = redecode(tempDir, listOf(looped, serverJson(0.8, begins), serverJson(39.8, odd)))

        assertEquals(listOf(false, true, false), txSvc.conditioned)
        assertTrue("whiskey" !in vtt(dir))
        val retried = mapper.readTree(Files.readString(dir.resolve("transcript.json"))).path("whisper_run").path("chunking").path("retried")
        assertEquals(1, retried.size())
        assertTrue(retried[0].path("conditioned").asBoolean())
    }

    @Test
    fun `with quality retries off, a chunk that loops is kept as it came`(
        @TempDir tempDir: Path,
    ) {
        val lap = listOf(" We're going to talk about", " the", " whiskey", " sour.")
        val looped = serverJson(0.8, *(0 until 45).map { Cue(1.0 + it * 0.2, 1.2 + it * 0.2, lap[it % 4]) }.toTypedArray())

        val (_, txSvc) = redecode(tempDir, listOf(looped, serverJson(39.8, odd)), qualityRetry = false)

        assertEquals(listOf(false, false), txSvc.conditioned)
    }

    /** Measured: We Have Ways 2021-04-27, 10:03: chunks back in lower case with no marks at all. */
    @Test
    fun `a chunk back without punctuation is decoded again, and the punctuated attempt kept`(
        @TempDir tempDir: Path,
    ) {
        val said = "and i think it's fair to say that the um i mean it's a it's a it's the um 28th and 38th army isn't it so the 28th army"
        val marked = "And I think it's fair to say that the, um, I mean, it's the 28th and 38th Army, isn't it? So, the 28th Army,"
        val flat = serverJson(0.8, Cue(1.0, 10.0, " $said is in that part"))
        val punctuated = serverJson(0.8, Cue(1.0, 10.0, " $marked is in that part."))

        val (dir, txSvc) = redecode(tempDir, listOf(flat, punctuated, serverJson(39.8, odd)))

        assertEquals(listOf(false, true, false), txSvc.conditioned)
        assertTrue("isn't it? So," in vtt(dir))
    }

    @Test
    fun `a punctuated attempt that leaves much more speech without words does not replace a full one without marks`(
        @TempDir tempDir: Path,
    ) {
        val said = "and i think it's fair to say that the um i mean it's a it's a it's the um 28th and 38th army isn't it so the 28th army"
        val flat = serverJson(0.8, Cue(1.0, 10.0, " $said is in that part"))
        val sparse = serverJson(0.8, Cue(1.0, 10.0, " Yes, and no."))

        val (dir, _) = redecode(tempDir, listOf(flat, sparse, flat, flat, serverJson(39.8, odd)))

        assertTrue("isn't it so the" in vtt(dir))
    }

    @Test
    fun `a few misplaced words in one chunk do not stop the run, when the episode as a whole has them in place`(
        @TempDir tempDir: Path,
    ) {
        val many = (0 until 120).map { Cue(1.0 + it * 0.075, 1.075 + it * 0.075, " Word$it.") }
        val adrift = serverJson(39.8, odd, wordsShift = 2.5)

        val (dir, _) = redecode(tempDir, listOf(serverJson(0.8, *many.toTypedArray()), adrift))

        assertTrue("And then we found something" in vtt(dir))
    }

    @Test
    fun `an episode VAD fails on, hears almost nothing in, that is no mp3, of no set language, or with chunking off, is decoded whole`(
        @TempDir tempDir: Path,
    ) {
        val whole = listOf(serverJson(0.0, begins, odd))

        assertEquals(listOf<TimeWindow?>(null), redecode(tempDir.resolve("fails"), whole, vadFails = true).second.windows)
        assertEquals(
            listOf<TimeWindow?>(null),
            redecode(tempDir.resolve("quiet"), whole, speech = listOf(TimeWindow(1.0, 2.0))).second.windows,
        )
        assertEquals(
            listOf<TimeWindow?>(null),
            redecode(tempDir.resolve("aac"), whole, audio = "fake-mp3-bytes".toByteArray()).second.windows,
        )
        assertEquals(
            listOf<TimeWindow?>(null),
            redecode(tempDir.resolve("auto"), whole, language = Transcriber.AUTO_LANGUAGE).second.windows,
        )
        assertEquals(listOf<TimeWindow?>(null), redecode(tempDir.resolve("off"), whole, chunkedDecode = false).second.windows)
    }

    private companion object {
        const val SAMPLE_RATE = 44100.0
        const val FRAME_SAMPLES = 1152
    }
}
