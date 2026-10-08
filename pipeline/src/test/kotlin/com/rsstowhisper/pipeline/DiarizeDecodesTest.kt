package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.escapeFilename
import com.rsstowhisper.external.SpeakerTurn
import com.rsstowhisper.external.WhisperRun
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiarizeDecodesTest {
    private val podcasts = listOf(PodcastConfig(name = "Show", url = "https://feed"))
    private val turns = listOf(SpeakerTurn(0.0, 2.0, 0), SpeakerTurn(2.0, 4.0, 1))
    private val entries = listOf(makeEntry("One"), makeEntry("Two"))

    private fun dirOf(
        dataDir: Path,
        title: String,
    ): Path = dataDir.resolve("Show").resolve(escapeFilename(PodcastPipeline.getEpisodeDirName(entries.single { it.title == title })))

    private fun hasTurns(dir: Path): Boolean = SpeakerTurns.read(dir, WhisperRun.sha256(dir.resolve("audio.mp3"))) != null

    private fun diarizedTotal(pipeline: PodcastPipeline): Any? = (pipeline.report.toMap()["totals"] as Map<*, *>)["diarized"]

    @Test
    fun `a run writes turns for each episode it transcribes`(
        @TempDir tempDir: Path,
    ) {
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, makeFeed(*entries.toTypedArray()), diarizer = diarizer)

        assertTrue(pipeline.run())

        assertEquals(2, diarizer.calls)
        assertTrue(hasTurns(dirOf(tempDir, "One")))
        assertTrue(hasTurns(dirOf(tempDir, "Two")))
        assertEquals(2, diarizedTotal(pipeline))
    }

    @Test
    fun `a recovered orphan gets turns too`(
        @TempDir tempDir: Path,
    ) {
        val orphan = tempDir.resolve("Show").resolve("2019-01-01-deadbeef-An-Old-Episode")
        Files.createDirectories(orphan)
        Files.writeString(orphan.resolve("audio.mp3"), "bytes")
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, makeFeed(entries[0]), diarizer = FakeSpeakerDiarizer(turns))

        assertTrue(pipeline.run())

        assertTrue(Files.exists(orphan.resolve("transcript.json")))
        assertTrue(hasTurns(orphan))
    }

    @Test
    fun `a diarizer that cannot run is a warning, and the decodes are still written`(
        @TempDir tempDir: Path,
    ) {
        val diarizer = FakeSpeakerDiarizer(turns, fails = true)
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, makeFeed(*entries.toTypedArray()), diarizer = diarizer)

        val errors = loggedAtError { assertTrue(pipeline.run()) }

        assertEquals(emptyList(), errors)
        assertEquals(0, diarizer.calls)
        assertTrue(Files.exists(dirOf(tempDir, "One").resolve("transcript.json")))
        assertFalse(hasTurns(dirOf(tempDir, "One")))
    }

    @Test
    fun `an episode that cannot be diarized is a warning, and the next one still is`(
        @TempDir tempDir: Path,
    ) {
        val bad = dirOf(tempDir, "One").fileName.toString()
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                podcasts,
                makeFeed(*entries.toTypedArray()),
                diarizer = FakeSpeakerDiarizer(turns, badFiles = setOf(bad)),
            )

        val messages = logged { assertEquals(emptyList(), loggedAtError { assertTrue(pipeline.run()) }) }

        assertTrue(messages.any { it.startsWith("Could not diarize Show/$bad") }, "logged: $messages")
        assertTrue(Files.exists(dirOf(tempDir, "One").resolve("transcript.json")))
        assertFalse(hasTurns(dirOf(tempDir, "One")))
        assertTrue(hasTurns(dirOf(tempDir, "Two")))
        assertEquals(1, diarizedTotal(pipeline))
    }

    @Test
    fun `a diarizer that breaks mid-run stops diarizing but not decoding`(
        @TempDir tempDir: Path,
    ) {
        val feed = makeFeed(*(1..5).map { makeEntry("Episode $it") }.toTypedArray())
        val everyFile = (1..5).map { escapeFilename(PodcastPipeline.getEpisodeDirName(makeEntry("Episode $it"))) }.toSet()
        val diarizer = FakeSpeakerDiarizer(turns, badFiles = everyFile, breaksAfter = 3)
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, feed, diarizer = diarizer)

        assertTrue(pipeline.run())

        assertEquals(3, diarizer.calls)
        assertEquals(5, Files.list(tempDir.resolve("Show")).use { s -> s.filter { Files.exists(it.resolve("transcript.json")) }.count() })
    }

    @Test
    fun `a dry run says what it would diarize, and runs nothing`(
        @TempDir tempDir: Path,
    ) {
        val diarizer = FakeSpeakerDiarizer(turns, fails = true)
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, makeFeed(*entries.toTypedArray()), dryRun = true, diarizer = diarizer)

        val messages = logged { assertTrue(pipeline.run()) }

        assertEquals(2, messages.count { it.startsWith("Would diarize Show/") })
        assertTrue(messages.none { "will not diarize" in it }, "logged: $messages")
        assertEquals(0, diarizer.calls)
    }

    @Test
    fun `without the diarize settings a run writes no turns`(
        @TempDir tempDir: Path,
    ) {
        val (pipeline, _, _) = buildPipeline(tempDir, podcasts, makeFeed(entries[0]))

        val messages = logged { assertTrue(pipeline.run()) }

        assertFalse(Files.exists(dirOf(tempDir, "One").resolve(SpeakerTurns.FILENAME)))
        assertTrue(messages.none { "diariz" in it }, "logged: $messages")
    }

    @Test
    fun `the next decode does not wait for the last one's diarization`(
        @TempDir tempDir: Path,
    ) {
        val two = dirOf(tempDir, "Two").fileName.toString()
        val secondDecode = CountDownLatch(1)
        var overlapped = false
        val diarizer =
            FakeSpeakerDiarizer(turns, onTurns = { audio ->
                if (audio.parent.fileName.toString() != two) overlapped = secondDecode.await(10, TimeUnit.SECONDS)
            })
        val (pipeline, _, _) =
            buildPipeline(
                tempDir,
                podcasts,
                makeFeed(*entries.toTypedArray()),
                diarizer = diarizer,
                onTranscribe = { audio -> if (audio.parent.fileName.toString() == two) secondDecode.countDown() },
            )

        assertTrue(pipeline.run())

        assertTrue(overlapped)
        assertEquals(2, diarizedTotal(pipeline))
    }

    @Test
    fun `a run with nothing to decode never checks the diarizer`(
        @TempDir tempDir: Path,
    ) {
        val diarizer = FakeSpeakerDiarizer(turns)
        buildPipeline(tempDir, podcasts, makeFeed(entries[0]), diarizer = diarizer).first.run()
        val (again, _, _) = buildPipeline(tempDir, podcasts, makeFeed(entries[0]), diarizer = diarizer)

        assertTrue(again.run())

        assertEquals(1, diarizer.checks)
    }

    @Test
    fun `each diarization gets a timeout scaled to its audio, not to where the decoded speech ends`(
        @TempDir tempDir: Path,
    ) {
        val frame = ByteArray(417).also { f -> intArrayOf(0xFF, 0xFB, 0x90, 0x64).forEachIndexed { i, v -> f[i] = v.toByte() } }
        val fourHundredSeconds = (0..(400 * 44_100 / 1152)).flatMap { frame.asList() }.toByteArray()
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) =
            buildPipeline(tempDir, podcasts, makeFeed(entries[0]), audioBytes = fourHundredSeconds, diarizer = diarizer)

        pipeline.run()

        assertEquals(PodcastPipeline.diarizeTimeoutSeconds(400), diarizer.timeouts.single())
        assertEquals(25 * 60L, PodcastPipeline.diarizeTimeoutSeconds(3600))
        assertEquals(55 * 60L, PodcastPipeline.diarizeTimeoutSeconds(null))
    }
}
