package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.external.SpeakerTurn
import com.rsstowhisper.external.Voice
import com.rsstowhisper.external.WhisperRun
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerNamesTest {
    private val mapper = ObjectMapper()
    private val show = PodcastConfig(name = "Show", url = "https://feed")
    private val other = PodcastConfig(name = "Other", url = "https://other")
    private val sha = "recorded-sha"
    private val turns = listOf(SpeakerTurn(0.0, 12.0, 0), SpeakerTurn(12.0, 24.0, 1), SpeakerTurn(24.0, 30.0, 2))

    private fun episode(
        dataDir: Path,
        podcast: String = "Show",
        name: String = "2024-01-02-abcd1234-hello",
        recordedSha: String? = sha,
    ): Path {
        val dir = dataDir.resolve(podcast).resolve(name)
        Files.createDirectories(dir)
        Files.write(dir.resolve("audio.mp3"), FAKE_MP3_BYTES)
        val run =
            recordedSha?.let {
                mapOf(WhisperRun.FIELD to mapOf("audio_sha256" to it, "audio_bytes" to FAKE_MP3_BYTES.size.toLong()))
            }.orEmpty()
        Files.writeString(
            dir.resolve("transcript.json"),
            mapper.writeValueAsString(mapOf("episode_transcript" to MINIMAL_VTT, "_id" to "abcd1234") + run),
        )
        return dir
    }

    private fun target(dir: Path) = "${dir.parent.fileName}/${dir.fileName}"

    private fun diarize(vararg dirs: Path) = RetranscribeRequest(paths = dirs.map(::target), diarize = true)

    private fun name(vararg dirs: Path) = RetranscribeRequest(paths = dirs.map(::target), nameSpeakers = true)

    private fun labels(
        dir: Path,
        vararg rows: Pair<Path, Pair<Int, String>>,
    ): Path {
        val file = dir.resolve("labels.tsv")
        Files.writeString(
            file,
            "episode\tspeaker\tname\n" + rows.joinToString("") { (ep, sn) -> "${target(ep)}\t${sn.first}\t${sn.second}\n" },
        )
        return file
    }

    private fun names(dir: Path): Map<String, String> =
        (mapper.readValue(Files.readString(dir.resolve(SpeakerNames.FILENAME)), Map::class.java)["names"] as Map<*, *>)
            .entries.associate { (k, v) -> k.toString() to (v as Map<*, *>)["name"].toString() }

    @Test
    fun `diarizing writes a voice print for each speaker beside the turns`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = diarizer)

        assertTrue(pipeline.retranscribe(diarize(dir)))

        val prints = VoicePrints.read(dir, sha)!!
        assertEquals(setOf(0, 1, 2), prints.voices.keys)
        assertEquals(12.0, prints.voices.getValue(1).seconds)
        assertTrue(VoicePrints.current(dir, sha, FAKE_MP3_BYTES.size.toLong(), diarizer.models))
    }

    @Test
    fun `turns already current only have their prints made, without diarizing again`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(turns)
        SpeakerTurns.write(dir, sha, FAKE_MP3_BYTES.size.toLong(), diarizer.models, turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = diarizer)

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertEquals(0, diarizer.calls)
        assertEquals(1, diarizer.voiceCalls)
        assertEquals(setOf(0, 1, 2), VoicePrints.read(dir, sha)!!.voices.keys)
    }

    @Test
    fun `an episode with current turns and prints is skipped`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = diarizer)
        pipeline.retranscribe(diarize(dir))

        val messages = logged { assertTrue(pipeline.retranscribe(diarize(dir))) }

        assertEquals(1, diarizer.calls)
        assertEquals(0, diarizer.voiceCalls)
        assertTrue(messages.any { "already has speaker turns" in it })
    }

    @Test
    fun `add-voices stores a labelled speaker's print under its name`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val voices = tempDir.resolve("voices")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        pipeline.retranscribe(diarize(dir))

        assertTrue(pipeline.addVoices(labels(tempDir, dir to (1 to "Robert Evans"))))

        val stored = SpeakerVoices.load(voices)!!.all.single()
        assertEquals("Robert Evans", stored.name)
        assertEquals("Show", stored.show)
        assertEquals(1, stored.speaker)
        assertEquals(FakeSpeakerDiarizer.printOf(1).toList(), stored.voice.print.toList())
    }

    @Test
    fun `naming a speaker again renames it rather than adding a second`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val voices = tempDir.resolve("voices")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        pipeline.retranscribe(diarize(dir))

        pipeline.addVoices(labels(tempDir, dir to (1 to "Dave Ross")))
        pipeline.addVoices(labels(tempDir, dir to (1 to "Robert Evans")))

        assertEquals(listOf("Robert Evans"), SpeakerVoices.load(voices)!!.all.map { it.name })
    }

    @Test
    fun `add-voices refuses a speaker the episode lacks, or an episode with no prints`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val bare = episode(tempDir, name = "2024-02-03-beef0001-bare")
        val voices = tempDir.resolve("voices")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        pipeline.retranscribe(diarize(dir))

        assertFalse(pipeline.addVoices(labels(tempDir, dir to (7 to "Nobody"))))
        assertFalse(pipeline.addVoices(labels(tempDir, bare to (0 to "Nobody"))))
        assertNull(SpeakerVoices.load(voices))
    }

    @Test
    fun `name-speakers names the show's own voices and ads from any show, never another show's people`(
        @TempDir tempDir: Path,
    ) {
        val labelled = episode(tempDir, name = "2024-01-02-abcd1234-hello")
        val elsewhere = episode(tempDir, podcast = "Other", name = "2024-01-05-cafe0001-there")
        val target = episode(tempDir, name = "2024-03-04-feed0001-later")
        val voices = tempDir.resolve("voices")
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show, other), feed = null, diarizer = diarizer, voices = voices)
        pipeline.retranscribe(diarize(labelled, elsewhere, target))
        // In the fake every episode's speaker n has the same print, so Other's people would match Show's speakers.
        assertTrue(
            pipeline.addVoices(
                labels(tempDir, labelled to (0 to "Host"), elsewhere to (1 to "Other Host"), elsewhere to (2 to "ad: Mattress")),
            ),
        )

        assertTrue(pipeline.retranscribe(name(target)))

        assertEquals(mapOf("0" to "Host", "2" to "ad: Mattress"), names(target))
    }

    @Test
    fun `names are current until the voices change`(
        @TempDir tempDir: Path,
    ) {
        val labelled = episode(tempDir)
        val target = episode(tempDir, name = "2024-03-04-feed0001-later")
        val voices = tempDir.resolve("voices")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        pipeline.retranscribe(diarize(labelled, target))
        pipeline.addVoices(labels(tempDir, labelled to (0 to "Host")))
        pipeline.retranscribe(name(target))

        val again = logged { assertTrue(pipeline.retranscribe(name(target))) }
        assertTrue(again.any { "Named the speakers of 0 of 1 episodes" in it })

        pipeline.addVoices(labels(tempDir, labelled to (1 to "Guest Host")))
        val (fresh, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        assertTrue(fresh.retranscribe(name(target)))
        assertEquals(mapOf("0" to "Host", "1" to "Guest Host"), names(target))
    }

    @Test
    fun `a speaker below the cut is left unnamed`() {
        val near =
            FloatArray(8).also {
                it[0] = 0.7f
                it[1] = 0.714f
            }
        val far =
            FloatArray(8).also {
                it[0] = 0.6f
                it[1] = 0.8f
            }
        val host = NamedVoice("Host", "Show", "Show/ep", 0, Voice(60.0, FakeSpeakerDiarizer.printOf(0)))

        val names = SpeakerNames.match(mapOf(1 to Voice(10.0, near), 2 to Voice(10.0, far)), listOf(host))

        assertEquals(setOf(1), names.keys)
        assertEquals("Host", names.getValue(1).name)
    }

    @Test
    fun `diarizing with voices names the speakers as it goes`(
        @TempDir tempDir: Path,
    ) {
        val labelled = episode(tempDir)
        val voices = tempDir.resolve("voices")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        pipeline.retranscribe(diarize(labelled))
        pipeline.addVoices(labels(tempDir, labelled to (0 to "Host")))
        val target = episode(tempDir, name = "2024-03-04-feed0001-later")
        val (fresh, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)

        assertTrue(fresh.retranscribe(diarize(target)))

        assertEquals(mapOf("0" to "Host"), names(target))
    }

    @Test
    fun `an episode without prints is a warning, and the rest are still named`(
        @TempDir tempDir: Path,
    ) {
        val labelled = episode(tempDir)
        val bare = episode(tempDir, name = "2024-02-03-beef0001-bare")
        val voices = tempDir.resolve("voices")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        pipeline.retranscribe(diarize(labelled))
        pipeline.addVoices(labels(tempDir, labelled to (0 to "Host")))

        val messages = logged { assertTrue(pipeline.retranscribe(name(bare, labelled))) }

        assertTrue(messages.any { "no voice prints" in it })
        assertNull(bare.resolve(SpeakerNames.FILENAME).takeIf { Files.exists(it) })
        assertEquals(mapOf("0" to "Host"), names(labelled))
    }

    @Test
    fun `name-speakers without a voices folder is refused`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns))

        assertFalse(pipeline.retranscribe(name(dir)))
    }

    @Test
    fun `name-speakers refuses a voices folder that isn't there, rather than name every episode as nobody`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = tempDir.resolve("no-voices"))
        pipeline.retranscribe(diarize(dir))

        assertFalse(pipeline.retranscribe(name(dir)))
        assertFalse(Files.exists(dir.resolve(SpeakerNames.FILENAME)))
    }

    @Test
    fun `diarizing with no voices folder yet still diarizes, and names nobody`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val (pipeline, _, _) =
            buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = tempDir.resolve("no-voices"))

        assertTrue(pipeline.retranscribe(diarize(dir)))

        assertTrue(Files.exists(dir.resolve(VoicePrints.FILENAME)))
        assertFalse(Files.exists(dir.resolve(SpeakerNames.FILENAME)))
    }

    @Test
    fun `episodes that only had prints made are not counted as diarized`(
        @TempDir tempDir: Path,
    ) {
        val dir = episode(tempDir)
        val diarizer = FakeSpeakerDiarizer(turns)
        SpeakerTurns.write(dir, sha, FAKE_MP3_BYTES.size.toLong(), diarizer.models, turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = diarizer)

        val messages = logged { assertTrue(pipeline.retranscribe(diarize(dir))) }

        assertTrue(messages.any { "Diarized 0 of 1 episodes; made voice prints for 1 more" in it })
    }

    @Test
    fun `a normal run names each episode it diarizes`(
        @TempDir tempDir: Path,
    ) {
        val labelled = episode(tempDir)
        val voices = tempDir.resolve("voices")
        val (setup, _, _) = buildPipeline(tempDir, listOf(show), feed = null, diarizer = FakeSpeakerDiarizer(turns), voices = voices)
        setup.retranscribe(diarize(labelled))
        setup.addVoices(labels(tempDir, labelled to (0 to "Host")))
        val entry = makeEntry("Fresh")
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), makeFeed(entry), diarizer = FakeSpeakerDiarizer(turns), voices = voices)

        assertTrue(pipeline.run())

        val fresh = tempDir.resolve("Show").resolve(com.rsstowhisper.escapeFilename(PodcastPipeline.getEpisodeDirName(entry)))
        assertEquals(mapOf("0" to "Host"), names(fresh))
    }

    @Test
    fun `a voices folder that cannot be read is a warning in a normal run, and the decode and turns still land`(
        @TempDir tempDir: Path,
    ) {
        val voices = tempDir.resolve("voices")
        Files.createDirectories(voices)
        Files.writeString(voices.resolve("Show.json"), "{ not json")
        val entry = makeEntry("Fresh")
        val diarizer = FakeSpeakerDiarizer(turns)
        val (pipeline, _, _) = buildPipeline(tempDir, listOf(show), makeFeed(entry), diarizer = diarizer, voices = voices)

        val errors = loggedAtError { assertTrue(pipeline.run()) }

        val fresh = tempDir.resolve("Show").resolve(com.rsstowhisper.escapeFilename(PodcastPipeline.getEpisodeDirName(entry)))
        assertEquals(emptyList(), errors)
        assertTrue(Files.exists(fresh.resolve(SpeakerTurns.FILENAME)))
        assertEquals(1, (pipeline.report.toMap()["totals"] as Map<*, *>)["diarized"])
    }

    @Test
    fun `a labels file with Windows line endings is read`() {
        val (rows, problems) = SpeakerVoices.parseLabels(listOf("episode\tspeaker\tname\r", "Show/ep\t3\tHost\r"))
        assertEquals(emptyList(), problems)
        assertEquals("Host", rows.single().name)
    }

    @Test
    fun `a labels file without its columns or with an unreadable row is reported`() {
        assertEquals(listOf("no header row"), SpeakerVoices.parseLabels(emptyList()).second)
        assertEquals(listOf("no name column"), SpeakerVoices.parseLabels(listOf("episode\tspeaker")).second)
        val (rows, problems) =
            SpeakerVoices.parseLabels(
                listOf("episode\tspeaker\tname", "Show/ep\tx\tHost", "Show/ep\t3\tHost", "ep\t3\tHost"),
            )
        assertEquals(1, rows.size)
        assertEquals(2, problems.size)
    }
}
