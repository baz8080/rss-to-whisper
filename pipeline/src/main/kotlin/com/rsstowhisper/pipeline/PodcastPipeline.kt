package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.rometools.modules.itunes.EntryInformation
import com.rometools.modules.itunes.FeedInformation
import com.rometools.modules.itunes.ITunes
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rsstowhisper.AppConfig
import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.audio.hasUsableTimes
import com.rsstowhisper.audio.readId3ChaptersOrNull
import com.rsstowhisper.audio.toSecondsMap
import com.rsstowhisper.createPath
import com.rsstowhisper.escapeFilename
import com.rsstowhisper.external.Cue
import com.rsstowhisper.external.EpisodeNotDiarized
import com.rsstowhisper.external.Fingerprinter
import com.rsstowhisper.external.Mp3Clip
import com.rsstowhisper.external.Mp3Frames
import com.rsstowhisper.external.SpeakerDiarizer
import com.rsstowhisper.external.SpeakerDiarizerFailed
import com.rsstowhisper.external.SpeechDetector
import com.rsstowhisper.external.SpeechDetectorFailed
import com.rsstowhisper.external.TimeWindow
import com.rsstowhisper.external.Transcriber
import com.rsstowhisper.external.TranscriberUnavailable
import com.rsstowhisper.external.WhisperRun
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.external.Word
import com.rsstowhisper.external.WordTimesMisplaced
import com.rsstowhisper.feed.FeedService
import com.rsstowhisper.feed.libsynAdMarkers
import com.rsstowhisper.resolvePath
import com.rsstowhisper.timeToSeconds
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

class PodcastPipeline(
    private val config: AppConfig,
    httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build(),
    private val feedService: FeedService = FeedService(httpClient),
    private val transcriber: Transcriber =
        Transcriber(
            config.whisperServerUrl,
            initialPrompt = config.defaultPrompt,
            promptLanguage = config.defaultPromptLanguage,
        ),
    private val speechDetector: SpeechDetector? =
        config.vadBinary?.let { binary -> config.vadModel?.let { SpeechDetector(binary, it, config.vadCache?.let { c -> Path.of(c) }) } },
    private val diarizer: SpeakerDiarizer? =
        config.diarizePython?.let { python ->
            SpeakerDiarizer(
                python,
                config.diarizeSegmentationModel.orEmpty(),
                config.diarizeEmbeddingModel.orEmpty(),
                config.diarizeThreads,
            )
        },
) {
    /** Spent across the whole run, not per podcast, so one show cannot use up the budget. */
    private var orphansRecovered = 0

    private var wouldTranscribe = 0
    private var wouldRecover = 0

    /** Diarizes each saved decode on its own thread, so whisper never waits on it; null when the run doesn't diarize. */
    private var diarizeQueue: ExecutorService? = null
    private val diarizesQueued = AtomicInteger()

    // The diarize thread's own: null until its first episode checks the tool, false once the tool stops working.
    private var diarizerUsable: Boolean? = null
    private val decodesUndiarized = UndiarizedStreak()

    internal val report = RunReport()

    private val dataRoot = Path.of(config.dataDirectory).normalize()
    private val audioRoot = Path.of(config.audioRoot).normalize()
    private val separateAudio = audioRoot != dataRoot

    /** What the run's exit code is made of. See [decodingWorked]. */
    private var decodesAttempted = 0
    private var decodesSucceeded = 0
    private var decodesUnreachable = 0

    // Anchored on word boundaries so "repeat" does not also swallow "repeating".
    private val excludeKeywordRegex: Regex? =
        config.excludeTitleKeywords
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("|") { """\b${Regex.escape(it.trim())}\b""" }
            ?.toRegex(RegexOption.IGNORE_CASE)

    private val jsonMapper =
        ObjectMapper().apply {
            enable(SerializationFeature.INDENT_OUTPUT)
            configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        }

    /** False when the run never started, so a supervisor sees a failed launch rather than a quiet no-op. */
    fun run(): Boolean {
        val dataDir = config.dataDirectory
        orphansRecovered = 0
        wouldTranscribe = 0
        wouldRecover = 0
        report.start()
        resetDecodeTally()

        if (!Files.isWritable(Path.of(dataDir))) {
            logger.error("The data_dir is missing, or not writable. Cannot continue")
            return false
        }
        if (!Files.isWritable(audioRoot)) {
            logger.error("The audio directory $audioRoot is missing, or not writable. Cannot continue")
            return false
        }

        // A dry run never decodes, so it must not refuse to run when whisper is off.
        if (!config.dryRun && !transcriber.ping()) {
            logger.error("No answer from the whisper server at ${config.whisperServerUrl}. Cannot continue")
            return false
        }
        diarizerUsable = null
        voicesLoaded = false
        decodesUndiarized.reset()
        if (diarizer != null && !config.dryRun) {
            diarizeQueue = Executors.newSingleThreadExecutor { Thread(it, "diarize").apply { isDaemon = true } }
        }

        try {
            for (podcast in config.podcasts) {
                processPodcast(podcast, dataDir)
            }
        } catch (e: WordTimesMisplaced) {
            logger.error("Stopping: ${e.message}")
        } finally {
            finishDiarizing()
            // A dry run does no work, so a report of it would be a record of
            // none -- and latest-run.json would lose the last real run.
            if (!config.dryRun) report.write(dataDir)
        }
        if (config.dryRun) {
            logger.info("Dry run: would transcribe $wouldTranscribe and recover $wouldRecover episodes")
            return true
        }
        return decodingWorked()
    }

    /** A decode is already saved, so nothing here fails the episode or the run. */
    private fun diarizeDecoded(
        podcast: PodcastConfig,
        episodeDirPath: Path,
    ) {
        val queue = diarizeQueue ?: return
        val counts = report.forPodcast(podcast.name)
        diarizesQueued.incrementAndGet()
        queue.execute {
            try {
                diarizeInBackground(counts, episodeDirPath)
            } finally {
                diarizesQueued.decrementAndGet()
            }
        }
    }

    private fun diarizeInBackground(
        counts: RunReport.Counts,
        episodeDirPath: Path,
    ) {
        if (diarizerUsable == null) {
            diarizerUsable =
                try {
                    requireNotNull(diarizer).check()
                    true
                } catch (e: SpeakerDiarizerFailed) {
                    logger.warn("${e.message}. This run will not diarize; --diarize can add the turns later")
                    false
                }
        }
        if (diarizerUsable != true) return
        try {
            // The decode's own duration is where its speech ends, which can be far short of the file sherpa-onnx reads.
            val seconds = Mp3Frames.of(audioFileFor(episodeDirPath))?.duration?.toInt()
            if (diarizeEpisode(episodeDirPath, diarizeTimeoutSeconds(seconds)) == Diarized.TURNS) counts.diarized++
            decodesUndiarized.reset()
        } catch (e: SpeakerDiarizerFailed) {
            logger.warn("${e.message}. The rest of this run will not diarize")
            diarizerUsable = false
        } catch (e: Exception) {
            logger.warn("Could not diarize ${episodeDirPath.parent.fileName}/${episodeDirPath.fileName}: ${e.message}")
            decodesUndiarized.failed()?.let {
                logger.warn(
                    "$MAX_UNDIARIZED_IN_A_ROW episodes in a row could not be diarized, and ${it.message}. The rest of this run will not",
                )
                diarizerUsable = false
            }
        }
    }

    /** The report counts what was diarized, so it waits for the turns still queued. */
    private fun finishDiarizing() {
        val queue = diarizeQueue ?: return
        diarizeQueue = null
        queue.shutdown()
        diarizesQueued.get().takeIf { it > 0 }?.let { logger.info("Waiting for $it episodes to finish diarizing") }
        queue.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
    }

    /** Bad or long files bunch up by show; only a failed check after a run of them says the tool itself broke. */
    private inner class UndiarizedStreak {
        private var count = 0

        fun reset() {
            count = 0
        }

        /** The check's failure once [MAX_UNDIARIZED_IN_A_ROW] episodes in a row could not be diarized; null to carry on. */
        fun failed(): SpeakerDiarizerFailed? {
            if (++count < MAX_UNDIARIZED_IN_A_ROW) return null
            count = 0
            return try {
                requireNotNull(diarizer).check()
                null
            } catch (e: SpeakerDiarizerFailed) {
                e
            }
        }
    }

    private fun resetDecodeTally() {
        decodesAttempted = 0
        decodesSucceeded = 0
        decodesUnreachable = 0
    }

    /**
     * Whether the run did its job, which is what the exit code reports.
     *
     * Two ways it did not: whisper went away mid-run, or it answered every
     * request and decoded none of them -- a build that 500s everything passes
     * the preflight and fails each episode on its own apparent merits.
     */
    private fun decodingWorked(): Boolean {
        if (decodesUnreachable > 0) {
            logger.error(
                "$decodesUnreachable episodes got no transcript from the whisper server " +
                    "at ${config.whisperServerUrl}",
            )
            return false
        }
        if (decodesAttempted > 0 && decodesSucceeded == 0) {
            logger.error("None of the $decodesAttempted episodes attempted produced a transcript")
            return false
        }
        return true
    }

    /**
     * Decodes episodes that already have a transcript, and replaces it.
     *
     * No feed is fetched. Every target is already on disk, and one that aged
     * out of its feed has nothing left to fetch.
     */
    fun retranscribe(request: RetranscribeRequest): Boolean {
        val dataDir = Path.of(config.dataDirectory)
        resetDecodeTally()
        voicesLoaded = false
        if (!Files.isWritable(dataDir)) {
            logger.error("The data_dir is missing, or not writable. Cannot continue")
            return false
        }

        if (request.diarize && diarizer == null) {
            logger.error("--diarize needs diarize_python, diarize_segmentation_model and diarize_embedding_model in pods.yaml")
            return false
        }
        if (request.retime && speechDetector == null) {
            logger.error("--retime needs vad_binary and vad_model in pods.yaml")
            return false
        }
        if (request.nameSpeakers && config.voices == null) {
            logger.error("--name-speakers needs voices in pods.yaml")
            return false
        }
        if ((request.diarize || request.nameSpeakers) && config.voices != null) {
            val voices =
                try {
                    speakerVoices
                } catch (e: Exception) {
                    logger.error("Cannot read the voices folder ${config.voices}: ${e.message}")
                    return false
                }
            when {
                voices != null -> logger.info("${voices.all.size} named voices in ${config.voices}")
                request.nameSpeakers -> {
                    logger.error("The voices folder ${config.voices} does not exist; --add-voices makes it")
                    return false
                }
                else -> logger.info("No voices folder at ${config.voices} yet, so no speakers are named")
            }
        }
        val decodes = !request.retime && !request.diarize && !request.nameSpeakers
        if (!config.dryRun && decodes && !transcriber.ping()) {
            logger.error("No answer from the whisper server at ${config.whisperServerUrl}. Cannot continue")
            return false
        }

        val targets = RetranscribeTargets.find(dataDir, request)
        if (targets.isEmpty()) {
            logger.error("Nothing matched the re-transcription request")
            return false
        }

        val verb =
            when {
                request.retime -> "re-transcribe"
                request.diarize -> "diarize"
                request.nameSpeakers -> "name the speakers of"
                else -> "re-transcribe"
            }
        if (config.dryRun) {
            targets.forEach { logger.info("Would $verb ${it.parent.fileName}/${it.fileName}") }
            logger.info("Dry run: would $verb ${targets.size} episodes")
            return true
        }

        if (request.diarize) {
            try {
                requireNotNull(diarizer).check()
            } catch (e: SpeakerDiarizerFailed) {
                logger.error("${e.message}. Fix diarize_python and the diarize models. Cannot continue")
                return false
            }
        }

        logger.info(
            when {
                request.retime -> "Re-transcribing ${targets.size} episodes"
                request.diarize -> "Diarizing ${targets.size} episodes"
                request.nameSpeakers -> "Naming the speakers of ${targets.size} episodes"
                else -> "Re-transcribing ${targets.size} episodes"
            },
        )
        var done = 0
        var diarized = 0
        var printed = 0
        var named = 0
        val undiarized = UndiarizedStreak()
        var toolFailed = false
        for (target in targets) {
            if (request.diarize) {
                try {
                    when (diarizeEpisode(target)) {
                        Diarized.TURNS -> diarized++
                        Diarized.PRINTS -> printed++
                        Diarized.ALREADY -> {}
                    }
                    undiarized.reset()
                } catch (e: SpeakerDiarizerFailed) {
                    logger.error("Stopping: ${e.message}. Fix diarize_python and the diarize models")
                    toolFailed = true
                    break
                } catch (e: EpisodeNotDiarized) {
                    logger.error("Could not diarize ${target.parent.fileName}/${target.fileName}: ${e.message}")
                    val broken = undiarized.failed()
                    if (broken != null) {
                        logger.error("Stopping: $MAX_UNDIARIZED_IN_A_ROW episodes in a row could not be diarized, and ${broken.message}")
                        toolFailed = true
                        break
                    }
                } catch (e: Exception) {
                    logger.error("Could not diarize ${target.fileName}", e)
                }
                if (!request.retime) continue
            }
            if (request.nameSpeakers) {
                try {
                    if (nameEpisode(target)) named++
                } catch (e: EpisodeNotDiarized) {
                    logger.warn("Could not name the speakers of ${target.parent.fileName}/${target.fileName}: ${e.message}")
                } catch (e: Exception) {
                    logger.error("Could not name the speakers of ${target.fileName}", e)
                }
                if (!request.retime) continue
            }
            val attempted =
                try {
                    val written =
                        if (request.retime) {
                            retimeEpisode(target)
                        } else {
                            retranscribeEpisode(target, request.force)
                        }
                    if (written) done++
                    true
                } catch (e: WordTimesMisplaced) {
                    logger.error("Stopping: ${e.message}")
                    break
                } catch (e: SpeechDetectorFailed) {
                    logger.error("Stopping: ${e.message}. Fix vad_binary/vad_model")
                    toolFailed = true
                    break
                } catch (e: TranscriberUnavailable) {
                    // Nothing was decoded, so nothing was learned about this
                    // episode. Rotating it to the back would mean a server that
                    // died on the third target sent the rest of the window away
                    // unexamined, for as many runs as it takes to come round.
                    logger.error("Could not re-transcribe ${target.fileName}: ${e.message}")
                    false
                } catch (e: Exception) {
                    logger.error("Could not re-transcribe ${target.fileName}", e)
                    true
                }
            if (attempted) markRetranscribeAttempted(target)
        }
        if (request.diarize) {
            logger.info("Diarized $diarized of ${targets.size} episodes" + if (printed > 0) "; made voice prints for $printed more" else "")
        }
        if (request.nameSpeakers && !request.diarize) logger.info("Named the speakers of $named of ${targets.size} episodes")
        val decoded = !request.diarize && !request.nameSpeakers || request.retime
        if (decoded) logger.info("Re-transcribed $done of ${targets.size} episodes")
        // Diarizing and naming alone write nothing into a pair.
        val paired = !decoded || verifyBatch(targets)
        return decodingWorked() && paired && !toolFailed
    }

    /** Every target, written or not: one this run declined to replace can still be a broken pair. */
    private fun verifyBatch(targets: List<Path>): Boolean {
        val offenders =
            targets.filter { Files.exists(it.resolve(TRANSCRIPT_FILENAME)) }.mapNotNull { target ->
                when (val verdict = TranscriptPair.check(target)) {
                    is TranscriptPair.Diverged -> verdict.reason
                    is TranscriptPair.Unreadable -> "could not be checked: ${verdict.reason}"
                    else -> null
                }?.let { "${target.parent.fileName}/${target.fileName}: $it" }
            }
        if (offenders.isEmpty()) {
            logger.info("All ${targets.size} episodes have a transcript.json and words.jsonl.gz from one decode")
            return true
        }
        logger.error(
            "${offenders.size} of ${targets.size} episodes still have a transcript.json and words.jsonl.gz " +
                "from different decodes:\n  ${offenders.joinToString("\n  ")}",
        )
        return false
    }

    /**
     * Writes the episode's speaker turns and voice prints, unless it has them for this audio and these models already,
     * then names its speakers if there are named voices. Turns already current only have their prints made.
     */
    private enum class Diarized { ALREADY, PRINTS, TURNS }

    private fun diarizeEpisode(
        episodeDirPath: Path,
        timeoutSeconds: Long? = null,
    ): Diarized {
        val label = episodeDirPath.parent.fileName.toString() + "/" + episodeDirPath.fileName
        val audioPath = audioFileFor(episodeDirPath)
        if (!Files.exists(audioPath) || Files.size(audioPath) == 0L) throw EpisodeNotDiarized("it has no audio")
        val sha256 = audioSha256(recordedRun(episodeDirPath), audioPath)
        val bytes = Files.size(audioPath)
        val diarizer = requireNotNull(diarizer)
        val timeout = timeoutSeconds ?: diarizer.timeoutSeconds
        val started = System.nanoTime()
        val turnsCurrent = SpeakerTurns.current(episodeDirPath, sha256, bytes, diarizer.models)
        val wrote =
            when {
                turnsCurrent && VoicePrints.current(episodeDirPath, sha256, bytes, diarizer.models) -> {
                    logger.info("$label already has speaker turns for its audio")
                    Diarized.ALREADY
                }
                turnsCurrent -> {
                    val turns = requireNotNull(SpeakerTurns.read(episodeDirPath, sha256)).turns
                    VoicePrints.write(episodeDirPath, sha256, bytes, diarizer.models, diarizer.voices(audioPath, turns, timeout))
                    logger.info("Made voice prints for $label's turns: ${(System.nanoTime() - started) / 1_000_000_000} s")
                    Diarized.PRINTS
                }
                else -> {
                    val diarization = diarizer.diarize(audioPath, timeout)
                    SpeakerTurns.write(episodeDirPath, sha256, bytes, diarizer.models, diarization.turns)
                    VoicePrints.write(episodeDirPath, sha256, bytes, diarizer.models, diarization.voices)
                    logger.info(
                        "Diarized $label: ${diarization.turns.size} turns, ${diarization.voices.size} voices, " +
                            "${(System.nanoTime() - started) / 1_000_000_000} s",
                    )
                    Diarized.TURNS
                }
            }
        if (config.voices != null) {
            // The turns are written either way: a voices folder that cannot be read is no reason to diarize again.
            try {
                nameEpisode(episodeDirPath, sha256)
            } catch (e: Exception) {
                logger.warn("Could not name the speakers of $label: ${e.message}")
            }
        }
        return wrote
    }

    // Loaded once a run, so every episode named in it is matched against the same voices; each run reads them afresh.
    private var loadedVoices: SpeakerVoices? = null
    private var voicesLoaded = false

    /** Null when there is no voices folder: no episode is named, rather than each named as nobody. */
    private val speakerVoices: SpeakerVoices?
        get() {
            if (!voicesLoaded) {
                loadedVoices = config.voices?.let { SpeakerVoices.load(Path.of(it)) }
                voicesLoaded = true
            }
            return loadedVoices
        }

    /**
     * Writes who each of the episode's speakers is, from its voice prints and the named voices, unless that is current.
     * [audioSha256] is the audio's when known; without it the prints are taken for audio of the mp3's size, so naming
     * a whole corpus never reads its transcripts.
     */
    private fun nameEpisode(
        episodeDirPath: Path,
        audioSha256: String? = null,
    ): Boolean {
        val label = episodeDirPath.parent.fileName.toString() + "/" + episodeDirPath.fileName
        val voices = speakerVoices ?: return false
        val bytes = if (audioSha256 == null) audioFileFor(episodeDirPath).takeIf { Files.exists(it) }?.let { Files.size(it) } else null
        val prints =
            VoicePrints.read(episodeDirPath, audioSha256, bytes) ?: throw EpisodeNotDiarized("it has no voice prints for its audio")
        if (SpeakerNames.current(episodeDirPath, prints.audioSha256, prints.fileSha256, voices.sha256)) return false
        val names = SpeakerNames.match(prints.voices, voices.candidates(episodeDirPath.parent.fileName.toString()))
        SpeakerNames.write(episodeDirPath, prints.audioSha256, prints.fileSha256, voices.sha256, names)
        logger.info(
            "Named $label: ${names.size} of ${prints.voices.size} voices (${names.values.map { it.name }.toSet().sorted().joinToString(
                ", ",
            )})",
        )
        return true
    }

    /** The decode's record of the audio when its size still matches, so a network volume is not read twice over. */
    private fun audioSha256(
        run: Map<*, *>?,
        audioPath: Path,
    ): String {
        val recorded = run?.get("audio_sha256") as? String
        val bytes = (run?.get("audio_bytes") as? Number)?.toLong()
        return if (recorded != null && bytes == Files.size(audioPath)) recorded else WhisperRun.sha256(audioPath)
    }

    private fun recordedRun(episodeDirPath: Path): Map<*, *>? {
        val jsonPath = episodeDirPath.resolve(TRANSCRIPT_FILENAME)
        if (!Files.exists(jsonPath)) return null
        return jsonMapper.readValue(Files.readString(jsonPath), Map::class.java)[WhisperRun.FIELD] as? Map<*, *>
    }

    /** The turns for the audio the pair was decoded from, if the episode has them. */
    private fun speakersFor(
        episodeDirPath: Path,
        transcript: Map<String, Any?>,
    ): SpeakerTurns? {
        if (!Files.exists(episodeDirPath.resolve(SpeakerTurns.FILENAME))) return null
        val sha256 = (transcript[WhisperRun.FIELD] as? Map<*, *>)?.get("audio_sha256") as? String
        val bytes = if (sha256 == null) audioFileFor(episodeDirPath).takeIf { Files.exists(it) }?.let { Files.size(it) } else null
        return SpeakerTurns.read(episodeDirPath, sha256, bytes)
    }

    /** With the pair's words, or none for a transcript without them or with unreadable ones: then no repeat counts as traded. */
    private fun voicesFor(
        episodeDirPath: Path,
        speakers: SpeakerTurns,
    ): WindowRepair.Voices {
        val wordsPath = episodeDirPath.resolve(WhisperTranscription.WORDS_FILENAME)
        val words =
            try {
                if (Files.exists(wordsPath)) readWords(wordsPath) else emptyList()
            } catch (e: Exception) {
                val label = "${episodeDirPath.parent.fileName}/${episodeDirPath.fileName}"
                logger.warn("Cannot read the words of $label (${e.message}); judging it without its turns")
                emptyList()
            }
        return WindowRepair.Voices(speakers, words)
    }

    /**
     * Recorded for every attempt, not every success: the episodes that starve
     * the selection are exactly the ones `retranscribeEpisode` keeps refusing.
     */
    private fun markRetranscribeAttempted(episodeDirPath: Path) {
        try {
            Files.writeString(
                episodeDirPath.resolve(RETRANSCRIBE_ATTEMPTED_FILENAME),
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            )
        } catch (e: Exception) {
            logger.warn("Could not record the re-transcription attempt for ${episodeDirPath.fileName}", e)
        }
    }

    private fun retranscribeEpisode(
        episodeDirPath: Path,
        force: Boolean = false,
    ): Boolean {
        val label = episodeDirPath.parent.fileName.toString() + "/" + episodeDirPath.fileName
        val audioPath = audioFileFor(episodeDirPath)
        if (!Files.exists(audioPath) || Files.size(audioPath) == 0L) {
            logger.error("Cannot re-transcribe $label: it has no audio")
            return false
        }

        val jsonPath = episodeDirPath.resolve(TRANSCRIPT_FILENAME)
        val existing =
            if (Files.exists(jsonPath)) {
                @Suppress("UNCHECKED_CAST")
                jsonMapper.readValue(Files.readString(jsonPath), Map::class.java) as Map<String, Any?>
            } else {
                // Nothing to preserve, and the feed path is what should pick it
                // up -- redoing it here would write a transcript with no metadata.
                logger.error("Cannot re-transcribe $label: it has no transcript.json")
                return false
            }

        val previous = QualityReport.fromMap(existing["episode_quality"] as? Map<*, *>)?.let { measureStretchCopies(it, existing) }
        if (!force && vadConfirmedSilent(existing)) {
            logger.info("$label was emptied by a repair VAD confirmed was over silence; keeping it")
            return false
        }
        // Read before the decode, not after: a pair that cannot be read now is left for a run that can.
        val verdict = TranscriptPair.check(episodeDirPath)
        if (verdict is TranscriptPair.Unreadable) {
            logger.warn("Cannot re-transcribe $label: ${verdict.reason}")
            return false
        }

        val scored = transcribeEpisode(audioPath, episodeDirPath, podcastFor(episodeDirPath), label)
        if (scored.transcription.isEmpty) {
            logger.warn("Re-transcription of $label found no speech; keeping the existing transcript")
            return false
        }

        // The comparison reaches word times only once flags and punctuation are
        // level, so a decode that came back with none can still win outright on
        // a flag the other tripped -- overwriting a transcript flagged for low
        // confidence and taking its sidecar down too. The server was asked for
        // token_timestamps, so this is a server that ignored it.
        //
        // The stored score decides this, not the sidecar file: writing the
        // sidecar is allowed to fail, which leaves an episode whose decode had
        // word times and whose flags say so, with no file to find them in.
        val hadWordTimes =
            previous?.meanWordProbability != null ||
                Files.exists(episodeDirPath.resolve(WhisperTranscription.WORDS_FILENAME))
        if (scored.transcription.words.isEmpty() && hadWordTimes) {
            logger.warn(
                "Re-transcription of $label came back with no word timestamps and would drop the ones it has; " +
                    "keeping the existing transcript. Is token_timestamps still set on the server?",
            )
            return false
        }

        // Outside the force branch: a decode with no words is not a worse redo,
        // it is no redo at all, and transcription.isEmpty does not catch it --
        // segments carrying timestamps and no text render a non-blank VTT.
        if (previous != null && previous.hasSpeech && !scored.quality.hasSpeech) {
            logger.warn("Re-transcription of $label found no speech; keeping the existing transcript")
            return false
        }

        // Whisper is not deterministic, so a re-decode can come back worse than
        // the transcript it would overwrite, and this write is the only copy of
        // it. Judged the way transcribeEpisode judges its retry, so redoing an
        // episode can improve it or leave it alone, never cost it a decode.
        // A pair from two decodes is not kept on its score: neither half of it is usable.
        val diverged = verdict as? TranscriptPair.Diverged
        if (diverged != null) {
            logger.info("$label is being replaced whatever it scores: ${diverged.reason}")
        } else if (previous != null && previous.isBetterThan(scored.quality)) {
            val outcome = if (force) "keeping it anyway, as asked" else "keeping the existing transcript"
            logger.warn(
                "Re-transcription of $label scored worse than what is on disk " +
                    "(${scored.quality.summary} against ${previous.summary}); $outcome",
            )
            if (!force) return false
        }

        // Every other field was derived from a feed entry that may no longer
        // exist, so the existing values are the best there are. The duration is
        // the exception, and only for a recovered episode: that number came
        // from the previous decode, so this decode supersedes it -- but only if
        // it produced one, since null drops the episode out of the web module's
        // duration filters.
        val updated = existing.toMutableMap()
        updated["episode_transcript"] = scored.transcription.vtt
        updated["episode_quality"] = scored.quality.toMap()
        if (existing["episode_metadata_recovered"] == true) {
            scored.transcription.durationSeconds?.let { updated["episode_duration"] = it }
        }

        if (!writeTranscriptArtifacts(episodeDirPath, label, scored.transcription, updated, replace = true, runIdOf(existing))) {
            return false
        }
        logger.info("Re-transcribed $label")
        return true
    }

    /**
     * Lists every episode under the data directory whose `transcript.json` and
     * `words.jsonl.gz` are not from one decode, one `<podcast>/<episode>\t<reason>`
     * line each on stdout, which `--retranscribe-list` reads back. False if any.
     */
    fun verifyPairs(out: Appendable = System.out): Boolean {
        val dataDir = Path.of(config.dataDirectory)
        if (!Files.isDirectory(dataDir)) {
            logger.error("The data_dir $dataDir is missing. Cannot check it")
            return false
        }
        val dirs =
            try {
                RetranscribeTargets.episodeDirs(dataDir, strict = true).filter { Files.exists(it.resolve(TRANSCRIPT_FILENAME)) }
            } catch (e: Exception) {
                logger.error("Cannot list every episode under $dataDir, so cannot check them all", e)
                return false
            }
        logger.info("Checking ${dirs.size} episodes")
        // Over a network volume the reads dominate, so they overlap.
        val pool = Executors.newFixedThreadPool(VERIFY_THREADS)
        val verdicts =
            try {
                dirs.map { dir -> pool.submit(Callable { TranscriptPair.check(dir) }) }.map { it.get() }
            } finally {
                pool.shutdownNow()
            }

        var diverged = 0
        var unreadable = 0
        var unpaired = 0
        for ((dir, verdict) in dirs.zip(verdicts)) {
            when (verdict) {
                is TranscriptPair.Diverged -> {
                    diverged++
                    out.append("${dir.parent.fileName}/${dir.fileName}\t${verdict.reason}\n")
                }
                // Listed too, so a re-run over the list checks them again once they can be read.
                is TranscriptPair.Unreadable -> {
                    unreadable++
                    out.append("${dir.parent.fileName}/${dir.fileName}\tcould not be checked: ${verdict.reason}\n")
                }
                TranscriptPair.Unpaired -> unpaired++
                TranscriptPair.Consistent -> Unit
            }
        }
        val summary =
            "${dirs.size} episodes: $diverged with a transcript and words from different decodes, " +
                "$unreadable that could not be read, $unpaired with no words"
        if (diverged + unreadable > 0) logger.error(summary) else logger.info(summary)
        return diverged + unreadable == 0
    }

    /**
     * Every episode with a defect: one <podcast>/<episode> per line, then its defect cues, the windows around them and
     * each kind, so the output works as a --retranscribe-list. Reads only.
     */
    fun listDefects(out: Appendable = System.out): Boolean {
        val dataDir = Path.of(config.dataDirectory)
        if (!Files.isDirectory(dataDir)) {
            logger.error("The data_dir $dataDir is missing. Cannot list its defects")
            return false
        }
        val dirs =
            try {
                RetranscribeTargets.episodeDirs(dataDir, strict = true).filter { Files.exists(it.resolve(TRANSCRIPT_FILENAME)) }
            } catch (e: Exception) {
                logger.error("Cannot list every episode under $dataDir, so cannot check them all", e)
                return false
            }
        logger.info("Listing defects in ${dirs.size} episodes")
        val pool = Executors.newFixedThreadPool(VERIFY_THREADS)
        val lines =
            try {
                dirs.map { dir -> pool.submit(Callable { defectLine(dir) }) }.map { it.get() }
            } finally {
                pool.shutdownNow()
            }
        var found = 0
        var unreadable = 0
        var diarized = 0
        var traded = 0
        var cleared = 0
        for (line in lines) {
            line.fold(
                onSuccess = { defect ->
                    if (defect.text != null) {
                        out.append(defect.text).append('\n')
                        found++
                    }
                    if (defect.diarized) diarized++
                    if (defect.traded > 0) traded++
                    if (defect.traded > 0 && defect.text == null) cleared++
                },
                onFailure = {
                    logger.warn(it.message)
                    unreadable++
                },
            )
        }
        logger.info(
            "$found of ${dirs.size} episodes have defects" + if (unreadable > 0) "; $unreadable could not be read" else "",
        )
        logger.info("$diarized episodes have speaker turns; $traded hold repeats traded between voices, which cleared $cleared")
        return unreadable == 0
    }

    /** The sounds heard in several of a podcast's episodes, as candidates for Barry to name. Reads only. */
    fun findRecurringAudio(
        podcastName: String,
        out: Path,
    ): Boolean {
        val binary = config.fingerprintBinary
        if (binary == null) {
            logger.error("--find-recurring-audio needs fingerprint_binary in pods.yaml")
            return false
        }
        val podcast = podcastForDir(config.podcasts, podcastName)
        if (podcast == null) {
            logger.error("No podcast in pods.yaml is called $podcastName")
            return false
        }
        val showDir = dataRoot.resolve(escapeFilename(podcast.name))
        if (!Files.isDirectory(showDir)) {
            logger.error("$showDir is missing")
            return false
        }
        val episodes =
            Files.list(showDir).use { it.toList() }
                .filter { Files.exists(it.resolve(TRANSCRIPT_FILENAME)) && Files.exists(audioFileFor(it)) }
                .sortedBy { it.fileName.toString() }
                .map { RecurringAudioSurvey.Episode(it.fileName.toString(), it, audioFileFor(it)) }
        logger.info("Fingerprinting ${episodes.size} episodes of ${podcast.name}")
        val fingerprinter = Fingerprinter(binary, config.fingerprintCache?.let { Path.of(it) })
        return RecurringAudioSurvey(fingerprinter, VERIFY_THREADS) { readWords(it.resolve(WhisperTranscription.WORDS_FILENAME)) }
            .write(episodes, out.resolve(escapeFilename(podcast.name)))
    }

    /** Stores each labelled speaker's print under its name in the voices folder, from the episode's voice-prints.json. */
    fun addVoices(labels: Path): Boolean {
        val dir = config.voices?.let { Path.of(it) }
        if (dir == null) {
            logger.error("--add-voices needs voices in pods.yaml")
            return false
        }
        val lines =
            try {
                Files.readAllLines(labels)
            } catch (e: IOException) {
                logger.error("Cannot read $labels (${e.message})")
                return false
            }
        val (rows, problems) = SpeakerVoices.parseLabels(lines)
        problems.forEach { logger.error("$labels: $it; not added") }
        var ok = problems.isEmpty()
        val dataDir = Path.of(config.dataDirectory)
        for ((show, showRows) in rows.groupBy { it.episode.substringBefore('/') }) {
            val podcast = podcastForDir(config.podcasts, show)
            if (podcast == null) {
                logger.error("No podcast in pods.yaml has the directory $show")
                ok = false
                continue
            }
            val added = mutableListOf<NamedVoice>()
            for ((episode, episodeRows) in showRows.groupBy { it.episode }) {
                val episodeDir = dataDir.resolve(episode)
                val sha256 = if (Files.isDirectory(episodeDir)) recordedRun(episodeDir)?.get("audio_sha256") as? String else null
                val bytes = if (sha256 == null) audioFileFor(episodeDir).takeIf { Files.exists(it) }?.let { Files.size(it) } else null
                val prints = if (Files.isDirectory(episodeDir)) VoicePrints.read(episodeDir, sha256, bytes) else null
                if (prints == null) {
                    logger.error("$episode has no voice prints for its audio; run --diarize on it first. Not added")
                    ok = false
                    continue
                }
                for (row in episodeRows) {
                    val voice = prints.voices[row.speaker]
                    if (voice == null) {
                        logger.error("$episode has no speaker ${row.speaker}; not added")
                        ok = false
                        continue
                    }
                    added += NamedVoice(row.name, show, episode, row.speaker, voice)
                }
            }
            if (added.isEmpty()) continue
            try {
                SpeakerVoices.add(dir, show, podcast.name, added)
            } catch (e: Exception) {
                logger.error("Cannot store $show's voices in $dir: ${e.message}")
                ok = false
                continue
            }
            logger.info("Stored ${added.size} voices for $show: ${added.map { it.name }.toSet().sorted().joinToString(", ")}")
        }
        return ok
    }

    /** Stores each labelled sound in [labels] in its show's recurring_audio file; a row already stored is skipped. */
    fun addRecurringAudio(labels: Path): Boolean {
        val dir = config.recurringAudio?.let { Path.of(it) }
        val binary = config.fingerprintBinary
        if (dir == null || binary == null) {
            logger.error("--add-recurring-audio needs recurring_audio and fingerprint_binary in pods.yaml")
            return false
        }
        val stored =
            try {
                RecurringReferences.load(dir)
            } catch (e: Exception) {
                logger.error("Cannot read the recurring_audio folder $dir: ${e.message}")
                return false
            }
        val fingerprinter = Fingerprinter(binary, config.fingerprintCache?.let { Path.of(it) })
        val lines =
            try {
                Files.readAllLines(labels)
            } catch (e: IOException) {
                logger.error("Cannot read $labels (${e.message})")
                return false
            }
        val (rows, problems) = RecurringReferences.parseLabels(lines)
        problems.forEach { logger.error("$labels: $it; not added") }
        var ok = problems.isEmpty()
        for ((show, showRows) in rows.groupBy { it.show }) {
            val podcast = podcastForDir(config.podcasts, show)
            if (podcast == null) {
                logger.error("No podcast in pods.yaml has the directory $show")
                ok = false
                continue
            }
            val existing = stored.of(podcast).map { it.id }.toSet()
            val added = mutableListOf<Reference>()
            for (row in showRows) {
                val kind = LABEL_KINDS[row.label] ?: continue
                val id = "${kind.label}-${row.candidate}"
                if (id in existing) {
                    logger.info("$show already has $id; skipped")
                    continue
                }
                if (added.any { it.id == id }) {
                    logger.error("$show $id is labelled twice in $labels; only the first is added")
                    ok = false
                    continue
                }
                val (episode, from, to) = Triple(row.episode, row.from, row.to)
                val values =
                    try {
                        fingerprinter.fingerprint(audioFileFor(dataRoot.resolve(show).resolve(episode))).values
                    } catch (e: IOException) {
                        logger.error("$show $id: cannot fingerprint $episode (${e.message}); not added")
                        ok = false
                        continue
                    }
                val cut = RecurringReferences.cut(values, from, to)
                val found = cut?.let { RecurringAudio.matches(it, values).filter { w -> w.start < to && from < w.end } }.orEmpty()
                if (cut == null || found.sumOf { it.end - it.start } < 0.75 * (to - from)) {
                    logger.error("$show $id, cut from $episode $from-$to s, does not find itself there again; not added")
                    ok = false
                    continue
                }
                added += Reference(id, kind, episode, from, to, cut, row.note)
            }
            try {
                RecurringReferences.add(dir, escapeFilename(podcast.name), podcast.name, added)
                logger.info("${podcast.name}: ${added.size} references added")
            } catch (e: Exception) {
                logger.error("Cannot add ${podcast.name}'s references: ${e.message}")
                ok = false
            }
        }
        return ok
    }

    /** An episode's --list-defects line, null if it has none, and how many cues speaker turns cleared. */
    private class DefectLine(val text: String?, val diarized: Boolean, val traded: Int)

    private fun defectLine(dir: Path): Result<DefectLine> {
        val label = "${dir.parent.fileName}/${dir.fileName}"
        return try {
            @Suppress("UNCHECKED_CAST")
            val existing = jsonMapper.readValue(Files.readString(dir.resolve(TRANSCRIPT_FILENAME)), Map::class.java) as Map<String, Any?>
            val parsed = TranscriptPair.parseCues(existing["episode_transcript"]?.toString().orEmpty())
            if (parsed.any { it.start == null || it.end == null }) {
                return Result.failure(IllegalStateException("$label: a cue timestamp does not parse"))
            }
            val cues = parsed.map { Cue(it.start!!, it.end!!, it.text.trimEnd('\n')) }
            val podcast = podcastForDir(config.podcasts, dir.parent.fileName.toString())
            val prompt = WindowRepair.Prompt(podcast?.initialPrompt ?: config.defaultPrompt)
            val speakers = speakersFor(dir, existing)
            val unheard = WindowRepair.defectsByKind(cues, prompt)
            // Turns clear only loops and echoes, so the words are read only for an episode with one.
            val repeats = unheard.getValue("loop").isNotEmpty() || unheard.getValue("echo").isNotEmpty()
            val byKind = if (speakers != null && repeats) WindowRepair.defectsByKind(cues, prompt, voicesFor(dir, speakers)) else unheard
            val defects = WindowRepair.defectCues(cues, byKind)
            val tradedCues = WindowRepair.defectCues(cues, unheard) - defects
            val traded = tradedCues.size
            if (traded > 0) {
                val at = tradedCues.sorted().joinToString(", ") { "%.2f".format(Locale.ROOT, cues[it].start) }
                logger.debug("$label: traded between voices at $at s")
            }
            if (defects.isEmpty()) return Result.success(DefectLine(null, speakers != null, traded))
            val kinds = byKind.entries.joinToString("\t") { "${it.key}=${it.value.size}" }
            val text = "$label\tdefects=${defects.size}\twindows=${WindowRepair.windows(cues, defects).size}\t$kinds"
            Result.success(DefectLine(if (speakers == null) text else "$text\ttraded=$traded", speakers != null, traded))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("$label could not be read: ${e.message}", e))
        }
    }

    /** Matches the directory back to its feed so the decode keeps the podcast's language. */
    private fun podcastFor(episodeDirPath: Path): PodcastConfig {
        val podcastDir = episodeDirPath.parent.fileName.toString()
        podcastForDir(config.podcasts, podcastDir)?.let { return it }

        // Renamed or dropped from pods.yaml, but its episodes are still on
        // disk. The language then falls through to the top-level one, which is
        // the wrong language for a feed that opted out of it, so say so rather
        // than quietly decoding the episode as English.
        logger.warn("No podcast in pods.yaml matches $podcastDir; re-transcribing it in the default language")
        return PodcastConfig(name = podcastDir, url = "")
    }

    private fun processPodcast(
        podcast: PodcastConfig,
        dataDir: String,
    ) {
        logger.info("Processing ${podcast.name}")
        val feed = feedService.fetchFeed(podcast.url)

        if (feed == null) {
            logger.error("Could not fetch feed for ${podcast.name}")
            return
        }

        logger.debug("Downloaded ${podcast.url}")

        val podPath =
            if (config.dryRun) resolvePath(Path.of(dataDir), podcast.name) else createPath(Path.of(dataDir), podcast.name)
        val skipThreshold = config.skipAfterConsecutive
        val minDuration = podcast.minEpisodeDurationSeconds ?: config.minEpisodeDurationSeconds
        val prefixes = feedPrefixes(feed, podcast, minDuration)
        val examined = mutableSetOf<String>()
        var consecutiveTranscribed = 0
        var brokeEarly = false

        // Decide first, then do: the doing pass needs the next item known before the
        // current one is decoded, so it can download it in the meantime.
        val pending = mutableListOf<PendingEpisode>()
        for (entry in feed.entries) {
            try {
                val skip = skipReason(entry, podcast, minDuration)
                if (skip != null) {
                    logSkip(skip, entry, minDuration)
                    report.countSkip(podcast.name, skip)
                    continue
                }

                val stablePrefix = episodeStablePrefix(entry)
                logger.debug("Processing $stablePrefix")
                // A feed that publishes one guid twice would otherwise get a directory and a decode per copy.
                if (!examined.add(stablePrefix)) {
                    logger.debug("Skipping ${entry.title}: the feed already listed this episode")
                    continue
                }

                val existingDir =
                    findExistingEpisodeDir(podPath, stablePrefix)
                        ?: audioDirFor(podPath).takeIf { separateAudio }
                            ?.let { findExistingEpisodeDir(it, stablePrefix) }
                            ?.let { podPath.resolve(it.fileName) }
                if (existingDir != null && Files.exists(existingDir.resolve(TRANSCRIPT_FILENAME))) {
                    consecutiveTranscribed++
                    if (consecutiveTranscribed >= skipThreshold) {
                        logger.debug(
                            "Found $consecutiveTranscribed consecutive transcribed episodes. Skipping to next podcast.",
                        )
                        brokeEarly = true
                        break
                    }
                    continue
                }
                consecutiveTranscribed = 0

                // Not created yet: a run killed mid-way should leave one empty directory, not a feed's worth.
                val episodeDirPath = existingDir ?: podPath.resolve(escapeFilename(getEpisodeDirName(entry)))
                val mp3Info = getMp3Info(entry, audioDirFor(episodeDirPath))
                if (mp3Info == null) {
                    logger.warn("${entry.title} has no mp3 link. Skipping")
                    report.forPodcast(podcast.name).failed++
                    continue
                }
                pending += PendingEpisode(entry, episodeDirPath, mp3Info)
            } catch (e: Exception) {
                logger.error("Couldn't process episode entry: ${entry.title}", e)
                report.forPodcast(podcast.name).failed++
            }
        }

        if (config.dryRun) {
            pending.forEach {
                logger.info("Would transcribe ${podcast.name}/${it.episodeDirPath.fileName}")
                if (diarizer != null) logger.info("Would diarize ${podcast.name}/${it.episodeDirPath.fileName}")
            }
            logger.info("${podcast.name}: would transcribe ${pending.size} episodes")
            wouldTranscribe += pending.size
        } else {
            transcribeAll(feed, podcast, pending)
        }

        try {
            scanForOrphans(feed, podcast, podPath, dataDir, prefixes, examined, brokeEarly)
        } catch (e: Exception) {
            if (e is WordTimesMisplaced) throw e
            logger.error("Orphan recovery failed for ${podcast.name}", e)
        }
    }

    /** A feed entry that passed every filter and has no transcript yet. */
    private class PendingEpisode(
        val entry: SyndEntry,
        val episodeDirPath: Path,
        val mp3Info: Mp3Info,
    )

    /**
     * Downloads one episode ahead so the GPU does not idle between downloads. Only one
     * ahead, so a failed run leaves at most one extra audio file on disk.
     */
    private fun transcribeAll(
        feed: SyndFeed,
        podcast: PodcastConfig,
        pending: List<PendingEpisode>,
    ) {
        val prefetcher = Executors.newSingleThreadExecutor { Thread(it, "prefetch").apply { isDaemon = true } }
        var next: Future<Boolean>? = null
        val counts = report.forPodcast(podcast.name)

        try {
            for ((index, episode) in pending.withIndex()) {
                val entry = episode.entry
                val current = next ?: submitDownload(prefetcher, episode)
                // Queued behind the current download on the single thread, so it runs during the decode.
                next = pending.getOrNull(index + 1)?.let { submitDownload(prefetcher, it) }
                try {
                    if (!current.get()) {
                        logger.warn("Could not download audio for ${entry.title}. Skipping")
                        counts.failed++
                        continue
                    }
                    if (Files.exists(episode.episodeDirPath.resolve(TRANSCRIPT_FILENAME))) {
                        logger.debug("${entry.title} was transcribed while this run was busy; skipping")
                        continue
                    }
                    val scored =
                        transcribeEpisode(
                            episode.mp3Info.filePath,
                            episode.episodeDirPath,
                            podcast,
                            entry.title ?: episode.episodeDirPath.fileName.toString(),
                        )
                    if (writeEpisodeJson(feed, entry, episode.mp3Info, episode.episodeDirPath, podcast.collections, scored)) {
                        counts.transcribed++
                        diarizeDecoded(podcast, episode.episodeDirPath)
                    } else {
                        counts.failed++
                    }
                } catch (e: Exception) {
                    // An Error on the prefetch thread arrives wrapped, and must still end the run.
                    val cause = (e as? ExecutionException)?.cause ?: e
                    if (cause is Error) throw cause
                    if (cause is WordTimesMisplaced) {
                        counts.failed++
                        throw cause
                    }
                    logger.error("Couldn't process episode entry: ${entry.title}", cause)
                    counts.failed++
                }
            }
        } finally {
            prefetcher.shutdownNow()
        }
    }

    private fun submitDownload(
        prefetcher: ExecutorService,
        episode: PendingEpisode,
    ): Future<Boolean> = prefetcher.submit(Callable { feedService.downloadAudio(episode.mp3Info.url, episode.mp3Info.filePath) })

    /** The first filter an entry trips, in the order the pipeline has always applied them. */
    private fun skipReason(
        entry: SyndEntry,
        podcast: PodcastConfig,
        minDuration: Int,
    ): SkipReason? {
        val title = entry.title ?: return SkipReason.NO_TITLE

        if (podcast.excludes.any { exclude -> exclude.lowercase() in title.lowercase() }) {
            return SkipReason.PODCAST_EXCLUDE
        }
        if (excludeKeywordRegex?.containsMatchIn(title) == true) return SkipReason.GLOBAL_KEYWORD

        // A feed that omits itunes:duration must not be filtered out on a guess.
        val duration = parseDuration(entry.getModule(ITunes.URI) as? EntryInformation)
        if (duration != null && duration < minDuration) return SkipReason.TOO_SHORT

        if (entry.uri.isNullOrBlank()) return SkipReason.NO_GUID
        if (findAudioLink(entry) == null) return SkipReason.NO_AUDIO
        return null
    }

    private fun logSkip(
        reason: SkipReason,
        entry: SyndEntry,
        minDuration: Int,
    ) {
        val title = entry.title
        when (reason) {
            SkipReason.NO_TITLE -> Unit
            SkipReason.PODCAST_EXCLUDE -> logger.debug("Skipping podcast entry because of excludes match")
            SkipReason.GLOBAL_KEYWORD -> logger.debug("Skipping $title because of a global keyword match")
            SkipReason.TOO_SHORT -> {
                val duration = parseDuration(entry.getModule(ITunes.URI) as? EntryInformation)
                logger.debug("Skipping $title because it is ${duration}s, under the ${minDuration}s minimum")
            }
            SkipReason.NO_GUID -> logger.warn("$title has no GUID. Skipping")
            SkipReason.NO_AUDIO -> logger.warn("$title has no mp3 link. Skipping")
        }
    }

    /** Everything the orphan scan needs to know about a feed, derived without touching the disk. */
    private fun feedPrefixes(
        feed: SyndFeed,
        podcast: PodcastConfig,
        minDuration: Int,
    ): FeedPrefixes {
        val all = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val eligible = mutableSetOf<String>()

        for (entry in feed.entries) {
            if (entry.uri.isNullOrBlank()) continue
            val prefix = episodeStablePrefix(entry)
            all += prefix
            ids += episodeId(entry)
            val evaluated =
                try {
                    skipReason(entry, podcast, minDuration)
                } catch (e: Exception) {
                    logger.debug("Could not evaluate ${entry.title} for eligibility", e)
                    SkipReason.NO_AUDIO
                }
            if (evaluated == null) eligible += prefix
        }
        return FeedPrefixes(all, ids, eligible)
    }

    /**
     * Transcribe episodes that were downloaded but never processed because their feed
     * entry aged out.
     *
     * Only directories whose slug prefix is absent from the feed are opened. Reading all
     * 17,500 of them costs minutes on a network volume; the ~650 that are absent cost
     * seconds, and everything else is classified from names the podcast directory listing
     * already returned.
     */
    private fun scanForOrphans(
        feed: SyndFeed,
        podcast: PodcastConfig,
        podPath: Path,
        dataDir: String,
        prefixes: FeedPrefixes,
        examined: Set<String>,
        brokeEarly: Boolean,
    ) {
        // A feed that parses but carries nothing would make every directory look orphaned.
        if (prefixes.all.isEmpty()) {
            logger.warn("${podcast.name}: feed returned no usable entries; skipping orphan recovery")
            return
        }

        // A dry run does not create it, and a podcast with no directory has nothing
        // orphaned -- listing it would only raise an error about its own absence.
        val podDirs = episodeDirsFor(podPath).filter { Files.isDirectory(it) }
        if (podDirs.isEmpty()) return

        val onDisk =
            try {
                podDirs.flatMap { dir ->
                    Files.newDirectoryStream(dir).use { stream ->
                        stream.mapNotNull { EpisodeDirName.parse(it.fileName.toString()) }
                    }
                }.distinctBy { it.dirName }
            } catch (e: Exception) {
                logger.error("${podcast.name}: could not list $podPath", e)
                return
            }

        val byPrefix = onDisk.associateBy { it.stablePrefix }
        reportMissingFromDisk(podcast, prefixes, examined, byPrefix.keys)
        if (brokeEarly) reportShadowedByThreshold(podcast, podPath, prefixes, examined, byPrefix)

        val candidates = mutableListOf<EpisodeDirName>()
        for (parsed in onDisk) {
            if (parsed.stablePrefix in prefixes.all) continue
            if (parsed.episodeId in prefixes.ids) {
                logger.error(
                    "${podcast.name}: ${parsed.dirName} holds an episode the feed now publishes under a " +
                        "different date. Leaving it alone; the feed entry owns it now.",
                )
                continue
            }
            candidates += parsed
        }

        if (candidates.isEmpty()) return
        if (!config.recoverOrphans) {
            logger.info("${podcast.name}: ${candidates.size} directories are absent from the feed; recovery is off")
            return
        }

        recoverAll(feed, podcast, podPath, dataDir, candidates)
    }

    private fun recoverAll(
        feed: SyndFeed,
        podcast: PodcastConfig,
        podPath: Path,
        dataDir: String,
        candidates: List<EpisodeDirName>,
    ) {
        var recovered = 0
        var withoutAudio = 0
        var alreadyDone = 0
        var seen = 0

        // Newest first: the directory name leads with the publication date.
        val ordered = candidates.sortedByDescending { it.dirName }
        for (parsed in ordered) {
            // Tested before the readdir. Opening the rest of the backlog just to count it
            // costs seconds of network I/O per podcast, and the count is arithmetic.
            if (budgetSpent()) break
            seen++

            val episodeDirPath = podPath.resolve(parsed.dirName)
            val contents =
                try {
                    episodeDirsFor(episodeDirPath).flatMapTo(HashSet()) { dir ->
                        try {
                            Files.newDirectoryStream(dir).use { s -> s.map { it.fileName.toString() } }
                        } catch (_: NoSuchFileException) {
                            // Audio downloaded but never transcribed has no data-side directory.
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Skipping ${parsed.dirName}: could not read it (${e.message})")
                    continue
                }

            when {
                TRANSCRIPT_FILENAME in contents -> alreadyDone++
                RECOVERY_FAILED_FILENAME in contents -> {
                    alreadyDone++
                    logger.debug("Skipping ${parsed.dirName}: a previous recovery found no speech in its audio")
                }
                AUDIO_FILENAME !in contents -> {
                    withoutAudio++
                    logger.debug("Cannot recover ${parsed.dirName}: no audio, and it is no longer in the feed")
                }
                else -> {
                    if (recovered == 0) {
                        val what = if (config.dryRun) "listing" else "recovering"
                        logger.info("${podcast.name}: $what episodes that are no longer in the feed")
                    }
                    try {
                        val done =
                            if (config.dryRun) {
                                reportWouldRecover(podcast, episodeDirPath, parsed)
                            } else {
                                recoverEpisode(feed, podcast, episodeDirPath, parsed)
                            }
                        if (done) {
                            recovered++
                            orphansRecovered++
                            report.forPodcast(podcast.name).recovered++
                        } else {
                            report.forPodcast(podcast.name).failed++
                        }
                    } catch (e: Exception) {
                        if (e is WordTimesMisplaced) throw e
                        logger.error("Couldn't recover ${parsed.dirName}", e)
                        report.forPodcast(podcast.name).failed++
                    }
                }
            }
        }

        val pending = ordered.size - seen
        if (withoutAudio > 0) {
            logger.warn("${podcast.name}: $withoutAudio directories have neither audio nor a transcript")
        }
        if (pending > 0) {
            logger.info("${podcast.name}: $pending left for a later run; --orphan-limit reached")
        }
        logger.info(
            "${podcast.name}: ${candidates.size} directories absent from the feed " +
                "($recovered ${if (config.dryRun) "to recover" else "recovered"}, $alreadyDone already settled, " +
                "$withoutAudio without audio, $pending not examined)",
        )
    }

    private fun budgetSpent(): Boolean = config.orphanRecoveryLimit > 0 && orphansRecovered >= config.orphanRecoveryLimit

    /** An eligible entry with no directory at all, in the tail the skip threshold never reached. */
    private fun reportMissingFromDisk(
        podcast: PodcastConfig,
        prefixes: FeedPrefixes,
        examined: Set<String>,
        diskPrefixes: Set<String>,
    ) {
        val missing = prefixes.eligible - examined - diskPrefixes
        if (missing.isEmpty()) return
        logger.error(
            "${podcast.name}: ${missing.size} eligible feed entries have no directory and were never " +
                "reached, e.g. ${missing.sorted().take(5)}. Either the feed was rewritten or " +
                "skip_after_consecutive is hiding real work.",
        )
    }

    /**
     * Episodes still in the feed, still untranscribed, that the skip threshold walked past.
     * Not recovered -- the feed entry has the full metadata, so a run that reaches them
     * does a better job than recovery could.
     */
    private fun reportShadowedByThreshold(
        podcast: PodcastConfig,
        podPath: Path,
        prefixes: FeedPrefixes,
        examined: Set<String>,
        byPrefix: Map<String, EpisodeDirName>,
    ) {
        // Resolved through the listing the scan already has. findExistingEpisodeDir would
        // re-list the whole podcast directory for every prefix.
        val unprocessed =
            (prefixes.eligible - examined).filter { prefix ->
                val dirName = byPrefix[prefix]?.dirName ?: return@filter false
                !Files.exists(podPath.resolve(dirName).resolve(TRANSCRIPT_FILENAME))
            }
        if (unprocessed.isEmpty()) return
        logger.error(
            "${podcast.name}: ${unprocessed.size} episodes are in the feed and untranscribed but the skip " +
                "threshold stopped before them, e.g. ${unprocessed.sorted().take(5)}. " +
                "Raise skip_after_consecutive to reach them.",
        )
    }

    private fun reportWouldRecover(
        podcast: PodcastConfig,
        episodeDirPath: Path,
        parsed: EpisodeDirName,
    ): Boolean {
        if (!hasUsableAudio(episodeDirPath, parsed)) return false
        logger.info("Would recover ${podcast.name}/${parsed.dirName}")
        if (diarizer != null) logger.info("Would diarize ${podcast.name}/${parsed.dirName}")
        wouldRecover++
        return true
    }

    /** A zero-byte mp3 is a download that died. Recovery refuses it, so a dry run must not offer it. */
    private fun hasUsableAudio(
        episodeDirPath: Path,
        parsed: EpisodeDirName,
    ): Boolean {
        if (Files.size(audioFileFor(episodeDirPath)) == 0L) {
            logger.warn("Cannot recover ${parsed.dirName}: its audio file is empty")
            return false
        }
        return true
    }

    private fun recoverEpisode(
        feed: SyndFeed,
        podcast: PodcastConfig,
        episodeDirPath: Path,
        parsed: EpisodeDirName,
    ): Boolean {
        val audioPath = audioFileFor(episodeDirPath)
        if (!hasUsableAudio(episodeDirPath, parsed)) return false

        logger.info("Recovering ${parsed.dirName}")
        val scored =
            try {
                transcribeEpisode(audioPath, episodeDirPath, podcast, parsed.dirName)
            } catch (e: Exception) {
                if (e is WordTimesMisplaced) throw e
                // No marker: a server that is down now may transcribe this fine tomorrow.
                logger.error("Could not transcribe ${parsed.dirName}", e)
                return false
            }
        val transcription = scored.transcription

        if (transcription.isEmpty) {
            markRecoveryFailed(episodeDirPath, "whisper returned no speech")
            logger.warn("Recovery of ${parsed.dirName} found no speech; it will not be retried")
            return false
        }

        val episodeDict =
            buildRecoveredEpisodeDict(
                feed = feed,
                parsed = parsed,
                transcript = transcription.vtt,
                durationSeconds = transcription.durationSeconds,
                collections = podcast.collections,
                quality = scored.quality,
                audioPath = audioPath,
            ) ?: return false

        // Counting an orphan recovered when nothing was written both misreports
        // the run and spends a slot of orphan_recovery_limit on it.
        if (!writeTranscriptArtifacts(episodeDirPath, parsed.title ?: parsed.dirName, transcription, episodeDict)) return false
        diarizeDecoded(podcast, episodeDirPath)
        return true
    }

    /**
     * Audio that decodes to nothing would otherwise be re-uploaded to whisper on every run
     * forever -- an orphan has no feed entry left whose download could fail and stop it.
     */
    private fun markRecoveryFailed(
        episodeDirPath: Path,
        reason: String,
    ) {
        try {
            Files.createDirectories(episodeDirPath)
            Files.writeString(
                episodeDirPath.resolve(RECOVERY_FAILED_FILENAME),
                "${Instant.now()} $reason\n",
            )
        } catch (e: Exception) {
            logger.error("Could not write the recovery marker in $episodeDirPath", e)
        }
    }

    private fun writeEpisodeJson(
        feed: SyndFeed,
        entry: SyndEntry,
        mp3Info: Mp3Info,
        episodeDirPath: Path,
        collections: List<String>,
        scored: ScoredTranscription,
    ): Boolean {
        val transcription = scored.transcription
        if (Files.exists(episodeDirPath.resolve(TRANSCRIPT_FILENAME))) return true
        if (transcription.isEmpty) {
            logger.warn("Transcription for ${entry.title} came back empty; not writing transcript.json")
            return true
        }

        val episodeDict =
            buildEpisodeDict(
                feed,
                entry,
                transcription.vtt,
                collections,
                scored.quality,
                mp3Info.filePath,
            ) ?: return true

        return writeTranscriptArtifacts(episodeDirPath, entry.title ?: episodeDirPath.fileName.toString(), transcription, episodeDict)
    }

    /**
     * The only writer of `transcript.json` and `words.jsonl.gz`, staged and checked before either lands. A replace
     * names the run it read in [replacing] and is refused if that changed. False when the pair on disk is not this one.
     */
    private fun writeTranscriptArtifacts(
        episodeDirPath: Path,
        label: String,
        transcription: WhisperTranscription,
        episodeDict: Map<String, Any?>,
        replace: Boolean = false,
        replacing: String? = null,
    ): Boolean {
        val jsonPath = episodeDirPath.resolve(TRANSCRIPT_FILENAME)
        // Only the download creates it when audio lives in its own tree.
        Files.createDirectories(episodeDirPath)

        // Re-checked here rather than only at the callers: transcription takes minutes,
        // and a second instance over the same data directory may have finished this
        // episode while this one was decoding it.
        if (!replace && Files.exists(jsonPath)) {
            logger.warn("$label was transcribed by something else while this run was working on it")
            return false
        }

        val record = transcription.run?.let { episodeDict + (WhisperRun.FIELD to it.toMap()) } ?: episodeDict
        val stagedJson = episodeDirPath.resolve("$TRANSCRIPT_FILENAME${stagingSuffix()}")
        val stagedWords = episodeDirPath.resolve("${WhisperTranscription.WORDS_FILENAME}${stagingSuffix()}")
        try {
            Files.writeString(stagedJson, jsonMapper.writeValueAsString(record))
            val haveWords = writeWords(transcription, stagedWords, label)
            // A replace would otherwise take the old sidecar's word times down with it.
            if (!haveWords && transcription.words.isNotEmpty()) {
                logger.error("Not writing $label: its word timestamps could not be written")
                return false
            }
            when (val staged = TranscriptPair.check(jsonMapper.valueToTree(record), stagedWords)) {
                is TranscriptPair.Diverged -> {
                    logger.error("Not writing $label: the pair it would write disagrees: ${staged.reason}")
                    return false
                }
                is TranscriptPair.Unreadable -> {
                    logger.error("Not writing $label: the pair it would write could not be read back: ${staged.reason}")
                    return false
                }
                else -> Unit
            }
            val swapped =
                withPairLock(episodeDirPath, label) {
                    when {
                        !replace && Files.exists(jsonPath) -> {
                            logger.warn("$label was transcribed by something else while this run was working on it")
                            false
                        }
                        replace && runIdOnDisk(jsonPath) != replacing -> {
                            logger.warn("$label was rewritten by something else while this run was working on it; leaving it")
                            false
                        }
                        else -> {
                            swapPair(episodeDirPath, stagedJson, stagedWords.takeIf { haveWords }, replace)
                            true
                        }
                    }
                }
            if (swapped != true) return false
        } catch (e: Exception) {
            logger.error("Could not write the transcript of $label", e)
            return false
        } finally {
            runCatching { Files.deleteIfExists(stagedJson) }
            runCatching { Files.deleteIfExists(stagedWords) }
        }
        return verifyPair(episodeDirPath, label)
    }

    /**
     * Words first, then the transcript: interrupted between them, the old transcript sits beside another run's
     * words, which every check calls diverged. A failure puts the old words back.
     */
    private fun swapPair(
        episodeDirPath: Path,
        stagedJson: Path,
        stagedWords: Path?,
        replace: Boolean,
    ) {
        val jsonPath = episodeDirPath.resolve(TRANSCRIPT_FILENAME)
        val wordsPath = episodeDirPath.resolve(WhisperTranscription.WORDS_FILENAME)
        val backup =
            episodeDirPath.resolve("${WhisperTranscription.WORDS_FILENAME}.${ProcessHandle.current().pid()}.old").takeIf {
                Files.exists(wordsPath)
            }
        backup?.let { Files.copy(wordsPath, it, StandardCopyOption.REPLACE_EXISTING) }
        var landed = false
        try {
            stagedWords?.let { replaceWith(it, wordsPath) }
            // transcript.json is what marks an episode done, so a new one goes last.
            if (replace) replaceWith(stagedJson, jsonPath) else Files.move(stagedJson, jsonPath)
            landed = true
            if (stagedWords == null) Files.deleteIfExists(wordsPath)
        } finally {
            if (!landed) {
                if (backup != null) {
                    runCatching { replaceWith(backup, wordsPath) }
                } else if (stagedWords != null) {
                    runCatching { Files.deleteIfExists(wordsPath) }
                }
            }
            backup?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** One writer swaps an episode's pair at a time. Null when another writer kept the lock throughout. */
    private fun <T> withPairLock(
        episodeDirPath: Path,
        label: String,
        block: () -> T,
    ): T? {
        val lock = episodeDirPath.resolve(PAIR_LOCK_FILENAME)
        val token = "${Instant.now().toEpochMilli()} ${ProcessHandle.current().pid()} ${UUID.randomUUID()}"
        repeat(LOCK_TRIES) {
            try {
                Files.writeString(lock, token, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (_: FileAlreadyExistsException) {
                if (!clearIfStale(lock)) Thread.sleep(LOCK_WAIT_MILLIS)
                return@repeat
            }
            try {
                return block()
            } finally {
                runCatching { if (Files.readString(lock) == token) Files.delete(lock) }
            }
        }
        logger.warn("$label is being written by another run; leaving it to that one")
        return null
    }

    /**
     * A swap takes milliseconds, so a lock older than [STALE_LOCK_SECONDS] by its writer's own clock was left by a
     * run that died. Moved aside rather than deleted, so of two waiters only one takes it, and only the lock it judged.
     */
    private fun clearIfStale(lock: Path): Boolean {
        val held = runCatching { Files.readString(lock) }.getOrNull() ?: return false
        val since =
            held.substringBefore(' ').toLongOrNull()
                // Empty: its writer died between creating it and writing to it.
                ?: runCatching { Files.getLastModifiedTime(lock).toMillis() }.getOrNull()
                ?: return false
        if (Instant.now().toEpochMilli() - since < STALE_LOCK_SECONDS * 1000) return false
        val aside = lock.resolveSibling("$PAIR_LOCK_FILENAME.${ProcessHandle.current().pid()}.stale")
        try {
            Files.move(lock, aside, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            return false
        }
        if (runCatching { Files.readString(aside) }.getOrNull() != held) {
            runCatching { Files.move(aside, lock, StandardCopyOption.ATOMIC_MOVE) }
        }
        runCatching { Files.deleteIfExists(aside) }
        return true
    }

    private fun runIdOf(transcript: Map<String, Any?>): String? = (transcript[WhisperRun.FIELD] as? Map<*, *>)?.get("run_id") as? String

    private fun requestOf(transcript: Map<String, Any?>): Map<String, String>? =
        ((transcript[WhisperRun.FIELD] as? Map<*, *>)?.get("request") as? Map<*, *>)?.entries?.associate { (k, v) -> "$k" to "$v" }

    /** Emptied by a repair whose VAD heard nothing there: whisper would only invent something again. */
    private fun vadConfirmedSilent(transcript: Map<String, Any?>): Boolean {
        val run = transcript[WhisperRun.FIELD] as? Map<*, *> ?: return false
        val checked = (run["repairs"] as? List<*>).orEmpty().any { (it as? Map<*, *>)?.get("speech_checked") == true }
        return checked && (run["words"] as? Number)?.toInt() == 0 &&
            TranscriptPair.cueTexts(transcript["episode_transcript"]?.toString().orEmpty()).all { it.isBlank() }
    }

    /** A score stored before stretch-copies were counted, counted from its own transcript so the comparison sees them. */
    private fun measureStretchCopies(
        report: QualityReport,
        transcript: Map<String, Any?>,
    ): QualityReport {
        if (report.stretchCopies != null) return report
        val parsed = TranscriptPair.parseCues(transcript["episode_transcript"]?.toString().orEmpty())
        if (parsed.any { it.start == null || it.end == null }) return report
        val copies = TranscriptQuality.stretchCopyCues(parsed.map { Cue(it.start!!, it.end!!, it.text.trimEnd('\n')) }).size
        val flags = if (copies > 0) (report.flags + TranscriptQuality.FLAG_STRETCH_COPY).distinct() else report.flags
        return report.copy(stretchCopies = copies, flags = flags)
    }

    private fun runIdOnDisk(jsonPath: Path): String? =
        if (Files.exists(
                jsonPath,
            )
        ) {
            jsonMapper.readTree(Files.readString(jsonPath)).path(WhisperRun.FIELD).path("run_id").textValue()
        } else {
            null
        }

    private fun verifyPair(
        episodeDirPath: Path,
        label: String,
    ): Boolean {
        when (val verdict = TranscriptPair.check(episodeDirPath)) {
            is TranscriptPair.Diverged ->
                logger.error(
                    "$label: transcript.json and words.jsonl.gz disagree after writing them: ${verdict.reason}",
                )
            is TranscriptPair.Unreadable -> logger.error("$label: the pair just written could not be read back: ${verdict.reason}")
            else -> return true
        }
        return false
    }

    /**
     * Two instances over one data directory select the same episodes --
     * `--retranscribe-flagged` scans the whole tree, whatever podcasts the
     * config names -- so a shared staging name would let one move the other's
     * half-written file over a transcript.
     *
     * It stops that, and nothing more: the swap is two moves, not one, so two
     * instances re-transcribing the same episode can still interleave into a
     * transcript and a sidecar from different decodes. Nothing here locks, so
     * do not point two runs at the same episode.
     */
    private fun stagingSuffix(): String = ".${ProcessHandle.current().pid()}$STAGING_SUFFIX"

    /** A move rather than a write, so an interrupted replace cannot truncate the target. */
    private fun replaceWith(
        staged: Path,
        target: Path,
    ) {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Some network volumes refuse an atomic move across the same
            // directory. A plain replace is still better than a partial write.
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** False when no sidecar was written, which is what tells a replace to clear the stale one. */
    private fun writeWords(
        transcription: WhisperTranscription,
        path: Path,
        label: String,
    ): Boolean {
        if (transcription.words.isEmpty()) {
            if (transcription.cues.isNotEmpty()) logger.warn("No word timestamps for $label; is token_timestamps still set?")
            return false
        }
        return try {
            transcription.writeWords(path)
            true
        } catch (e: Exception) {
            // Not fatal. The transcript is the artifact the pipeline exists to
            // produce; the sidecar is an enrichment and can be rebuilt.
            logger.error("Could not write word timestamps for $label", e)
            // A half-written file would otherwise be moved into place as if it were whole.
            runCatching { Files.deleteIfExists(path) }
            false
        }
    }

    /** The same `<podcast>/<episode>` path, under the audio directory. */
    private fun audioDirFor(dataPath: Path): Path =
        if (separateAudio) audioRoot.resolve(dataRoot.relativize(dataPath.normalize())) else dataPath

    /** Both trees' copies of a path, or the one when audio shares the data directory. */
    private fun episodeDirsFor(dataPath: Path): List<Path> =
        if (separateAudio) listOf(dataPath, audioDirFor(dataPath)) else listOf(dataPath)

    private fun audioFileFor(episodeDirPath: Path): Path = audioDirFor(episodeDirPath).resolve(AUDIO_FILENAME)

    /**
     * The single place a decode happens, so both the feed path and the recovery
     * path get the same scoring and the same retry.
     *
     * Whisper is not deterministic, and the README's external repair passes
     * cleared most loops on their first re-decode (57 of 57 repetition cases,
     * most on the first attempt), so one retry is worth the doubled decode time
     * for the 1-5% of episodes that trip a flag.
     */
    private fun transcribeEpisode(
        audioPath: Path,
        episodePath: Path,
        podcast: PodcastConfig,
        label: String,
    ): ScoredTranscription {
        val audio = AudioIdentity(WhisperRun.sha256(audioPath), Files.size(audioPath))
        // A chunk with a defect is decoded again on its own, so the whole episode never is.
        chunkPlan(audioPath, podcast, label)?.let { plan ->
            return warnIfFlagged(decodeAndScore(audioPath, episodePath, podcast, audio, conditioned = false, plan), label)
        }
        val first = decodeAndScore(audioPath, episodePath, podcast, audio, conditioned = !config.decodeWithoutHistory)
        if (!first.quality.isFlagged || !config.qualityRetry) return warnIfFlagged(first, label)

        logger.info("$label scored ${first.quality.flags}; decoding it once more")
        val second =
            try {
                decodeAndScore(audioPath, episodePath, podcast, audio, conditioned = true)
            } catch (e: Exception) {
                // A server that misplaces word times will do it to every decode, so it ends the run.
                if (e is WordTimesMisplaced) throw e
                // The first decode is still a usable transcript; a failed retry
                // must not cost the episode entirely, nor fail the run.
                if (e is TranscriberUnavailable) decodesUnreachable--
                logger.warn("Retry of $label failed; keeping the first decode", e)
                return warnIfFlagged(first, label)
            }

        val best = if (second.quality.isBetterThan(first.quality)) second else first
        return warnIfFlagged(best, label)
    }

    /** WARN so it reaches the error log and the run's warning tally rather than only scrollback. */
    private fun warnIfFlagged(
        scored: ScoredTranscription,
        label: String,
    ): ScoredTranscription {
        if (scored.quality.isFlagged) {
            logger.warn("$label is still flagged after scoring: ${scored.quality.flags.joinToString(", ")}")
        }
        return scored
    }

    private class ChunkPlan(
        val speech: List<TimeWindow>,
        val frames: Mp3Frames,
    )

    /** What a decode in chunks needs; null decodes the episode whole. */
    private fun chunkPlan(
        audioPath: Path,
        podcast: PodcastConfig,
        label: String,
    ): ChunkPlan? {
        val detector = speechDetector?.takeIf { config.chunkedDecode } ?: return null
        // Each chunk would detect its language alone, and a few seconds of speech can be taken for another.
        if ((podcast.language ?: config.language).lowercase() == Transcriber.AUTO_LANGUAGE) return null
        val frames = Mp3Frames.of(audioPath)
        if (frames == null) {
            logger.warn("$label cannot be cut into mp3 clips; decoding it whole")
            return null
        }
        val speech =
            try {
                detector.speech(audioPath)
            } catch (e: SpeechDetectorFailed) {
                logger.warn("VAD failed on $label (${e.message}); decoding it whole")
                return null
            }
        // Far less speech than any podcast has is VAD failing quietly, and a chunked decode would hear only that.
        if (speech.sumOf { it.end - it.start } < MIN_SPEECH_SHARE * frames.duration) {
            logger.warn("VAD hears almost no speech in $label; decoding it whole")
            return null
        }
        return ChunkPlan(speech, frames)
    }

    private fun decodeAndScore(
        audioPath: Path,
        episodePath: Path,
        podcast: PodcastConfig,
        audio: AudioIdentity,
        conditioned: Boolean,
        /** Decode in chunks rather than whole. */
        plan: ChunkPlan? = null,
    ): ScoredTranscription {
        logger.debug("Starting transcription in {}", episodePath)
        val startTime = System.currentTimeMillis()
        val language = podcast.language ?: config.language
        val decodedAt = WhisperRun.now()

        val (parsed, chunking) = plan?.let { decodeChunked(audioPath, podcast, it) } ?: (decode(audioPath, podcast, conditioned) to null)

        // The mp3 is now the retained artifact -- the whisper server decodes and
        // resamples it itself, so the old audio.wav is dead weight.
        Files.deleteIfExists(episodePath.resolve("audio.wav"))
        Files.deleteIfExists(episodePath.resolve("transcript.txt"))

        val elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000.0
        val chunks = chunking?.let { ": ${it["chunks"]} chunks, ${(it["retried"] as List<*>).size} decoded again" } ?: ""
        logger.debug("Transcribed in ${"%.1f".format(Locale.ROOT, elapsedMinutes)} minutes$chunks")

        val run =
            WhisperRun(
                runId = WhisperRun.newId(),
                decodedAt = decodedAt,
                serverUrl = config.whisperServerUrl,
                model = config.whisperModel,
                request = transcriber.requestFields(language, podcast.initialPrompt, conditioned),
                audioSha256 = audio.sha256,
                audioBytes = audio.bytes,
                pipelineVersion = WhisperRun.pipelineVersion,
                words = parsed.words.size,
                chunking = chunking,
            )
        val transcription = parsed.copy(run = run)
        return ScoredTranscription(transcription, TranscriptQuality.score(transcription))
    }

    /**
     * VAD's speech in chunks of one window each, decoded alone and unprompted: no text carried over to loop on, no window
     * skipped for silence. A chunk with a defect, or without punctuation, is decoded again with the other settings.
     */
    private fun decodeChunked(
        audioPath: Path,
        podcast: PodcastConfig,
        plan: ChunkPlan,
    ): Pair<WhisperTranscription, Map<String, Any?>> {
        val (speech, frames) = plan.speech to plan.frames
        val prompt = WindowRepair.Prompt(podcast.initialPrompt ?: config.defaultPrompt)
        val chunks = WindowRepair.chunks(speech, 0.0, frames.duration)
        val attempts = if (config.qualityRetry) CHUNK_ATTEMPTS else CHUNK_ATTEMPTS.take(1)
        val pieces = mutableListOf<Pair<WindowRepair.Chunk, WhisperTranscription>>()
        val retried = mutableListOf<Map<String, Any?>>()
        // Every decode as whisper returned it: stitching re-times a trimmed cue to its words, hiding where they were.
        val returned = mutableListOf<WhisperTranscription>()
        for (chunk in chunks) {
            val clip = frames.clip(chunk.window)
            var best: ChunkAttempt? = null
            var tried = 0
            for ((conditioned, retry) in attempts) {
                // A few cues are too few to judge where words sit; all of the episode's decodes are judged together.
                val decoded = decodeChunk(audioPath, podcast, conditioned, retry, chunk, clip, frames, speech)
                returned += decoded.map { it.second }
                tried++
                val joined = WindowRepair.stitched(decoded.map { it.first }, decoded.map { it.second })
                val tries =
                    ChunkAttempt(
                        decoded,
                        conditioned,
                        retry,
                        WindowRepair.defectCues(joined.cues, prompt).size,
                        WindowRepair.unpunctuated(joined),
                        WindowRepair.uncovered(joined.words, speech, chunk.window.start, chunk.window.end, SCORE_COVER_SECONDS),
                    )
                if (best == null || tries.beats(best)) best = tries
                if (best.defects == 0 && !best.unpunctuated) break
            }
            val kept = best!!
            if (tried > 1) {
                logger.debug(
                    "Chunk {}-{} s decoded {} times; kept {} {}",
                    chunk.window.start,
                    chunk.window.end,
                    tried,
                    kept.conditioned,
                    kept.retry,
                )
                val (start, end) = chunk.window
                retried +=
                    mapOf(
                        "start" to start,
                        "end" to end,
                        "attempts" to tried,
                        "conditioned" to kept.conditioned,
                        "retry" to kept.retry.takeIf { it.isNotEmpty() },
                        "defects" to kept.defects,
                        "unpunctuated" to kept.unpunctuated,
                    )
            }
            pieces += kept.pieces
        }
        misplaced(WindowRepair.joined(returned))?.let {
            decodesUnreachable++
            throw it
        }
        val stitched = WindowRepair.stitched(pieces.map { it.first }, pieces.map { it.second })
        return WindowRepair.dropNonSpeech(stitched, speech) to mapOf("chunks" to chunks.size, "retried" to retried)
    }

    /** One request to whisper, counted for the exit code, and refused if its word times are unusable. */
    private fun decode(
        audioPath: Path,
        podcast: PodcastConfig,
        conditioned: Boolean,
        window: TimeWindow? = null,
        retry: Map<String, String> = emptyMap(),
        clip: Mp3Clip? = null,
        checkPlacement: Boolean = true,
    ): WhisperTranscription {
        decodesAttempted++
        val json =
            try {
                transcriber.transcribe(
                    audioPath,
                    podcast.language ?: config.language,
                    podcast.initialPrompt,
                    conditioned,
                    window,
                    retry,
                    clip,
                )
            } catch (e: TranscriberUnavailable) {
                decodesUnreachable++
                throw e
            }
        decodesSucceeded++

        val parsed = WhisperTranscription.parse(json).let { if (clip != null) it.shifted(clip.start) else it }
        if (checkPlacement) {
            misplaced(parsed)?.let {
                decodesSucceeded--
                decodesUnreachable++
                throw it
            }
        }
        return parsed
    }

    private fun misplaced(decoded: WhisperTranscription): WordTimesMisplaced? {
        val share = decoded.misplacedWordShare
        if (share <= WhisperTranscription.MAX_MISPLACED_WORD_SHARE) return null
        return WordTimesMisplaced(
            "${"%.0f".format(Locale.ROOT, share * 100)}% of cues from ${config.whisperServerUrl} have their words " +
                "outside the cue's time, the signature of a server applying VAD whatever the request says. " +
                "Restart it without --vad",
        )
    }

    /** Moves the words and cues whisper timed before their speech to where VAD hears it start, with no decode. */
    private fun retimeEpisode(episodeDirPath: Path): Boolean {
        val label = episodeDirPath.parent.fileName.toString() + "/" + episodeDirPath.fileName
        val audioPath = audioFileFor(episodeDirPath)
        if (!Files.exists(audioPath) || Files.size(audioPath) == 0L) {
            logger.error("Cannot retime $label: it has no audio")
            return false
        }
        when (val verdict = TranscriptPair.check(episodeDirPath)) {
            TranscriptPair.Consistent -> Unit
            TranscriptPair.Unpaired -> {
                logger.warn("Cannot retime $label: it has no word timings to move; re-transcribe it instead")
                return false
            }
            is TranscriptPair.Diverged -> {
                logger.warn("Cannot retime $label: ${verdict.reason}; re-transcribe it instead")
                return false
            }
            is TranscriptPair.Unreadable -> {
                logger.warn("Cannot retime $label: ${verdict.reason}")
                return false
            }
        }

        @Suppress("UNCHECKED_CAST")
        val existing =
            jsonMapper.readValue(
                Files.readString(episodeDirPath.resolve(TRANSCRIPT_FILENAME)),
                Map::class.java,
            ) as Map<String, Any?>
        val parsedCues = TranscriptPair.parseCues(existing["episode_transcript"]?.toString().orEmpty())
        if (parsedCues.any { it.start == null || it.end == null }) {
            logger.warn("Cannot retime $label: a cue timestamp does not parse; re-transcribe it instead")
            return false
        }
        val cues = parsedCues.map { Cue(it.start!!, it.end!!, it.text.trimEnd('\n')) }

        val podcast = podcastFor(episodeDirPath)
        val prompt = WindowRepair.Prompt(podcast.initialPrompt ?: config.defaultPrompt)
        val wordsPath = episodeDirPath.resolve(WhisperTranscription.WORDS_FILENAME)
        if (!Files.exists(wordsPath)) {
            logger.warn("Cannot retime $label: its decode has no word timings to move; re-transcribe it instead")
            return false
        }
        val base = WhisperTranscription.of(cues, readWords(wordsPath))
        val voices = speakersFor(episodeDirPath, existing)?.let { WindowRepair.Voices(it, base.words) }
        val defects = WindowRepair.defectCues(cues, prompt, voices)
        val detector = speechDetector
        if (detector == null) {
            logger.warn("Cannot retime $label: it needs vad_binary and vad_model")
            return false
        }
        val speech = detector.speech(audioPath)
        if (!WindowRepair.plausible(base, defects, speech)) {
            logger.warn("VAD hears no speech under most of $label's cues; not retiming it")
            return false
        }

        val filler = cues.indices.filter { WindowRepair.filler(cues[it], prompt) }
        val retimed = Retiming.retime(base, speech, defects + filler)
        if (retimed.words + retimed.cues == 0) {
            logger.info("$label has no early cues to retime")
            return false
        }

        val language = podcast.language ?: config.language
        val audio = AudioIdentity(audioSha256(existing[WhisperRun.FIELD] as? Map<*, *>, audioPath), Files.size(audioPath))
        val run =
            WhisperRun(
                runId = WhisperRun.newId(),
                decodedAt = WhisperRun.now(),
                serverUrl = config.whisperServerUrl,
                model = config.whisperModel,
                request = requestOf(existing) ?: transcriber.requestFields(language, podcast.initialPrompt),
                audioSha256 = audio.sha256,
                audioBytes = audio.bytes,
                pipelineVersion = WhisperRun.pipelineVersion,
                words = retimed.transcription.words.size,
                base = existing[WhisperRun.FIELD] as? Map<*, *>,
                repairs =
                    listOf(
                        mapOf(
                            "kind" to "retime",
                            "words_moved" to retimed.words,
                            "cues_moved" to retimed.cues,
                            "seconds_moved" to Math.round(retimed.seconds * 10) / 10.0,
                        ),
                    ),
            )
        val written = retimed.transcription.copy(run = run)
        val updated = existing.toMutableMap()
        updated["episode_transcript"] = written.vtt
        updated["episode_quality"] = TranscriptQuality.score(written).toMap()
        if (!writeTranscriptArtifacts(episodeDirPath, label, written, updated, replace = true, runIdOf(existing))) return false
        logger.info("Retimed $label: ${retimed.words} held words and ${retimed.cues} early cues retimed")
        return true
    }

    /**
     * One chunk, decoded again from where it stopped while VAD still hears speech after: whisper skips the rest of a
     * window it takes for silence inside a clip too. The pieces meet at the resumption, with no overlap to stitch.
     */
    private fun decodeChunk(
        audioPath: Path,
        podcast: PodcastConfig,
        conditioned: Boolean,
        retry: Map<String, String>,
        chunk: WindowRepair.Chunk,
        clip: Mp3Clip?,
        frames: Mp3Frames,
        speech: List<TimeWindow>,
    ): List<Pair<WindowRepair.Chunk, WhisperTranscription>> {
        val pieces = mutableListOf<Pair<WindowRepair.Chunk, WhisperTranscription>>()
        var window = chunk.window
        var sent = clip
        for (resumed in 0..MAX_CHUNK_RESUMES) {
            val decoded = decode(audioPath, podcast, conditioned, window, retry, sent, checkPlacement = false)
            val tail = WindowRepair.unheardTail(decoded, speech, window).takeIf { resumed < MAX_CHUNK_RESUMES }
            if (tail == null) {
                pieces += chunk.copy(window = window) to decoded
                break
            }
            logger.debug("Chunk {}-{} s stopped short; decoding again from {} s", window.start, window.end, tail.start)
            val stopped = WindowRepair.lastSpokenEnd(decoded) ?: tail.start
            val kept = chunk.keep?.takeIf { resumed == 0 }?.let { TimeWindow(it.start, Double.MAX_VALUE) }
            pieces += WindowRepair.Chunk(TimeWindow(window.start, stopped), cutInSpeech = true, keep = kept) to decoded
            window = tail
            sent = if (clip != null) frames.clip(tail) else null
        }
        return pieces
    }

    private class ChunkAttempt(
        val pieces: List<Pair<WindowRepair.Chunk, WhisperTranscription>>,
        val conditioned: Boolean,
        val retry: Map<String, String>,
        val defects: Int,
        val unpunctuated: Boolean,
        val uncovered: Double,
    ) {
        /** Fewer defects first; then a punctuated attempt nearly as full beats one without marks. */
        fun beats(other: ChunkAttempt): Boolean =
            when {
                defects != other.defects -> defects < other.defects
                other.unpunctuated && !unpunctuated -> uncovered <= other.uncovered + PUNCTUATION_SLACK_SECONDS
                !other.unpunctuated && unpunctuated -> uncovered < other.uncovered - PUNCTUATION_SLACK_SECONDS
                else -> uncovered < other.uncovered
            }
    }

    private fun readWords(path: Path): List<Word> =
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val node = jsonMapper.readTree(line)
                Word(
                    text = node.path("w").asText(""),
                    start = node.path("s").asDouble(),
                    end = node.path("e").asDouble(),
                    probability = node.path("p").asDouble(),
                    segment = node.path("seg").asInt(),
                )
            }.toList()
        }

    private class AudioIdentity(val sha256: String, val bytes: Long)

    companion object {
        internal const val TRANSCRIPT_FILENAME = "transcript.json"

        internal const val STAGING_SUFFIX = ".new"

        /** Held while a pair is swapped; hidden and extension-less, so nothing walking the tree reads it. */
        internal const val PAIR_LOCK_FILENAME = ".pair-lock"
        private const val STALE_LOCK_SECONDS = 60
        private const val LOCK_TRIES = 50
        private const val LOCK_WAIT_MILLIS = 100L

        private const val VERIFY_THREADS = 8
        private const val MAX_UNDIARIZED_IN_A_ROW = 3

        /** The listening page's letters. X (not one sound) and ? (unsure) are not stored. */
        private val LABEL_KINDS =
            mapOf(
                "I" to Reference.Kind.INTRO,
                "O" to Reference.Kind.OUTRO,
                "M" to Reference.Kind.MUSIC,
                "A" to Reference.Kind.AD,
                "S" to Reference.Kind.SPEECH,
            )

        /** Several times the ~2 min per hour of audio measured at 4 threads, so only a stuck file runs out; 3 h when not an mp3 we can read. */
        internal fun diarizeTimeoutSeconds(durationSeconds: Int?): Long = 10 * 60L + (durationSeconds ?: (3 * 3600)) / 4

        private const val MAX_CHUNK_RESUMES = 2

        /** Tighter than a cue's check: a sentence whisper stretched over speech it skipped, a word a second, covers little of it. */
        private const val SCORE_COVER_SECONDS = 0.3

        /** Unprompted first, as each chunk is decoded alone; then prompted, warmer, and with a wider beam. */
        private val CHUNK_ATTEMPTS =
            listOf(false to emptyMap(), true to emptyMap(), false to Transcriber.RETRY_WARMER, false to Transcriber.RETRY_WIDER_BEAM)
        private const val MIN_SPEECH_SHARE = 0.2

        private const val PUNCTUATION_SLACK_SECONDS = 1.0
        internal const val AUDIO_FILENAME = "audio.mp3"

        /** Deliberately extension-less: nothing walking the tree for transcripts will pick it up. */
        internal const val RECOVERY_FAILED_FILENAME = "recovery-failed"

        /** When `--retranscribe-flagged` last decoded this episode, whatever came of it. */
        internal const val RETRANSCRIBE_ATTEMPTED_FILENAME = "retranscribe-attempted"

        private val logger = LoggerFactory.getLogger(PodcastPipeline::class.java)
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val AUDIO_MP3_TYPES = setOf("audio/mpeg", "audio/mp3")

        private fun formatDate(date: Date): String = date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().format(dateFormat)

        fun md5Hash8(value: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(8)
        }

        fun episodeId(entry: SyndEntry): String = md5Hash8(entry.uri!!)

        fun episodeStablePrefix(entry: SyndEntry): String {
            val date = if (entry.publishedDate != null) formatDate(entry.publishedDate) else "unknown-date"
            return "$date-${episodeId(entry)}"
        }

        /** The pods.yaml entry whose slugged name is this directory's, accents and case aside. */
        internal fun podcastForDir(
            podcasts: List<PodcastConfig>,
            podcastDir: String,
        ): PodcastConfig? = podcasts.firstOrNull { escapeFilename(it.name).equals(escapeFilename(podcastDir), ignoreCase = true) }

        /**
         * A directory name with no `-<hex8>-` component cannot be resolved back
         * to its audio: 97 episodes once stored one, and the keys pointed at
         * nothing until a repair pass derived them from disk. Nothing about the
         * construction below can produce that today, which is exactly why it is
         * worth asserting -- a silent recurrence costs a re-ingest to find.
         */
        fun getEpisodeDirName(entry: SyndEntry): String {
            // A title with no ASCII letters slugs to nothing and would lose the prefix's separator.
            val title = entry.title?.takeIf { escapeFilename(it).isNotEmpty() } ?: "unknown"
            val name = "${episodeStablePrefix(entry)}-$title"
            require(EpisodeDirName.matches(escapeFilename(name))) {
                "Episode directory name is missing its date-id prefix: $name"
            }
            return name
        }

        fun findExistingEpisodeDir(
            podPath: Path,
            stablePrefix: String,
        ): Path? =
            podPath.toFile()
                .listFiles()
                ?.firstOrNull { it.isDirectory && it.name.startsWith("$stablePrefix-") }
                ?.toPath()

        fun getMp3Info(
            entry: SyndEntry,
            episodePath: Path,
        ): Mp3Info? {
            val source = findAudioSource(entry) ?: return null
            val filePath = episodePath.resolve(AUDIO_FILENAME)

            return Mp3Info(
                url = source.url,
                filePath = filePath,
                length = source.length,
            )
        }

        fun buildEpisodeDict(
            feed: SyndFeed,
            entry: SyndEntry,
            transcript: String,
            collections: List<String>? = null,
            quality: QualityReport? = null,
            audioPath: Path? = null,
        ): Map<String, Any?>? {
            if (transcript.isEmpty()) return null

            val guid = entry.uri?.takeIf { it.isNotBlank() }
            if (guid == null) {
                logger.error("Skipping episode because it has no GUID")
                return null
            }

            val audioLink = findAudioLink(entry)
            if (audioLink == null) {
                logger.error("Skipping episode because it has no MP3")
                return null
            }

            return try {
                val entryItunes = entry.getModule(ITunes.URI) as? EntryInformation

                podcastFields(feed, collections) +
                    mapOf<String, Any?>(
                        "_id" to episodeId(entry),
                        "episode_title" to entry.title,
                        "all_tags" to collectTags(feed, entry, entryItunes),
                        "episode_published_on" to entry.publishedDate?.let { formatDate(it) },
                        "episode_audio_link" to audioLink,
                        "episode_web_link" to entry.link,
                        "episode_image" to getEpisodeImage(entry, entryItunes),
                        "episode_summary" to (entry.description?.value ?: entryItunes?.summary),
                        "episode_subtitle" to entryItunes?.subtitle,
                        "episode_authors" to
                            entry.authors?.map {
                                it?.name
                            },
                        "episode_number" to entryItunes?.episode,
                        "episode_season" to entryItunes?.season,
                        "episode_type" to entryItunes?.episodeType,
                        "episode_duration" to parseDuration(entryItunes),
                        "episode_transcript" to transcript,
                        "episode_chapters" to chapterMaps(audioPath),
                        "episode_ad_markers" to adMarkerMaps(entry),
                        "episode_quality" to quality?.toMap(),
                    )
            } catch (e: Exception) {
                logger.error("Error getting podcast metadata", e)
                null
            }
        }

        /** Null, not empty, when the audio could not be read: an empty list claims it carries none. */
        private fun chapterMaps(audioPath: Path?): List<Map<String, Any?>>? =
            audioPath?.let(::readId3ChaptersOrNull)
                ?.filter { it.hasUsableTimes() }
                ?.map { it.toSecondsMap() }

        /**
         * Only the feed carries these, so only a live entry can supply them. `timestamp_publisher_s`
         * names its clock: it is the feed master's, not the downloaded file's.
         */
        private fun adMarkerMaps(entry: SyndEntry): List<Map<String, Any?>> =
            libsynAdMarkers(entry).map {
                mapOf(
                    "type" to it.type,
                    "count" to it.count,
                    "timestamp_publisher_s" to it.timestampSeconds,
                )
            }

        /** The eight fields that are the same for every episode of one feed. */
        internal fun podcastFields(
            feed: SyndFeed,
            collections: List<String>?,
        ): Map<String, Any?> {
            val feedItunes = feed.getModule(ITunes.URI) as? FeedInformation
            return mapOf(
                "podcast_collections" to (collections ?: emptyList<String>()),
                "podcast_title" to feed.title,
                "podcast_link" to feed.link,
                "podcast_language" to feed.language,
                "podcast_copyright" to feed.copyright,
                "podcast_author" to (feed.author ?: feedItunes?.author),
                "podcast_image" to (feed.image?.url ?: feedItunes?.imageUri),
                "podcast_type" to feedItunes?.type,
            )
        }

        /**
         * The same shape as [buildEpisodeDict], for an episode whose feed entry is gone.
         *
         * The directory name carries the date, the id and a lossy title; the feed still
         * supplies everything about the podcast, and the audio file its own chapters.
         * Nothing else is recoverable, and every unrecoverable field is explicitly null --
         * an empty string would reach the web module's `!= null` guards and render a dead
         * link, and an empty list would claim the feed said there were no ad breaks.
         */
        internal fun buildRecoveredEpisodeDict(
            feed: SyndFeed,
            parsed: EpisodeDirName,
            transcript: String,
            durationSeconds: Int?,
            collections: List<String>? = null,
            quality: QualityReport? = null,
            audioPath: Path? = null,
        ): Map<String, Any?>? {
            if (transcript.isEmpty()) return null

            return try {
                podcastFields(feed, collections) +
                    mapOf(
                        "_id" to parsed.episodeId,
                        "episode_title" to parsed.title,
                        "all_tags" to normaliseTags(feed.categories.orEmpty().map { it.name }),
                        "episode_published_on" to parsed.publishedOn,
                        "episode_audio_link" to null,
                        "episode_web_link" to null,
                        "episode_image" to null,
                        "episode_summary" to null,
                        "episode_subtitle" to null,
                        "episode_authors" to null,
                        "episode_number" to null,
                        "episode_season" to null,
                        "episode_type" to null,
                        "episode_duration" to durationSeconds,
                        "episode_transcript" to transcript,
                        "episode_chapters" to chapterMaps(audioPath),
                        "episode_ad_markers" to null,
                        "episode_metadata_recovered" to true,
                        "episode_quality" to quality?.toMap(),
                    )
            } catch (e: Exception) {
                logger.error("Error recovering metadata for ${parsed.dirName}", e)
                null
            }
        }

        private data class AudioSource(val url: String, val length: Long)

        // Single source of truth for locating an episode's audio, in strict
        // preference order: mp3 enclosure, then mp3-typed link, then a link that
        // declares itself an enclosure without saying what it is. Keeping one
        // predicate means findAudioLink and getMp3Info can never disagree about
        // whether an episode has audio.
        //
        // The last fallback is deliberately limited to *untyped* links. A link
        // that says rel="enclosure" type="video/mp4" is telling us it is not
        // audio; downloading it as audio.mp3 would leave a file whisper cannot
        // decode on disk, and since the file's presence is what marks a download
        // as done, every later run would re-upload and re-transcribe it forever.
        private fun findAudioSource(entry: SyndEntry): AudioSource? {
            entry.enclosures
                .firstOrNull { it.type in AUDIO_MP3_TYPES }
                ?.let { return AudioSource(it.url, it.length) }

            val links = entry.links.orEmpty()
            val link =
                links.firstOrNull { it.type in AUDIO_MP3_TYPES }
                    ?: links.firstOrNull { it.rel == "enclosure" && it.type.isNullOrBlank() }

            return link?.let { AudioSource(it.href, it.length) }
        }

        private fun findAudioLink(entry: SyndEntry): String? = findAudioSource(entry)?.url

        private fun collectTags(
            feed: SyndFeed,
            entry: SyndEntry,
            entryItunes: EntryInformation?,
        ): List<String> {
            val tags = mutableListOf<String>()
            feed.categories?.forEach { tags.add(it.name) }
            entryItunes?.keywords?.forEach { tags.add(it) }
            entry.categories?.forEach { tags.add(it.name) }
            return normaliseTags(tags)
        }

        private fun normaliseTags(raw: List<String>): List<String> = raw.map { it.trim().lowercase() }.distinct().filter { it.length > 2 }

        private fun getEpisodeImage(
            entry: SyndEntry,
            entryItunes: EntryInformation?,
        ): String? =
            entryItunes?.imageUri
                ?: entry.foreignMarkup?.find { it.name == "image" }?.getAttributeValue("href")

        internal fun parseDuration(entryItunes: EntryInformation?): Int? =
            entryItunes?.duration?.let { duration ->
                val ms = duration.milliseconds
                if (ms > 0) {
                    (ms / 1000).toInt()
                } else {
                    val durationStr = duration.toString()
                    if (":" in durationStr) timeToSeconds(durationStr) else null
                }
            }
    }
}

internal enum class SkipReason { NO_TITLE, PODCAST_EXCLUDE, GLOBAL_KEYWORD, TOO_SHORT, NO_GUID, NO_AUDIO }

/**
 * Feed membership, by slug prefix and by id. Derived without touching the disk, so the
 * orphan scan can classify most directories from their names alone.
 */
internal data class FeedPrefixes(
    /** Every entry with a GUID, filtered-out ones included -- an excluded episode is still live. */
    val all: Set<String>,
    /** Ids without their dates, so a re-dated episode is not mistaken for an orphan. */
    val ids: Set<String>,
    val eligible: Set<String>,
)

data class Mp3Info(
    val url: String,
    val filePath: Path,
    val length: Long,
)
