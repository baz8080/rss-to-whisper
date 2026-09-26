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
        config.vadBinary?.let { binary -> config.vadModel?.let { SpeechDetector(binary, it) } },
) {
    /** Spent across the whole run, not per podcast, so one show cannot use up the budget. */
    private var orphansRecovered = 0

    private var wouldTranscribe = 0
    private var wouldRecover = 0

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

        try {
            for (podcast in config.podcasts) {
                processPodcast(podcast, dataDir)
            }
        } catch (e: WordTimesMisplaced) {
            logger.error("Stopping: ${e.message}")
        } finally {
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
        if (!Files.isWritable(dataDir)) {
            logger.error("The data_dir is missing, or not writable. Cannot continue")
            return false
        }

        if (!config.dryRun && !transcriber.ping()) {
            logger.error("No answer from the whisper server at ${config.whisperServerUrl}. Cannot continue")
            return false
        }

        val targets = RetranscribeTargets.find(dataDir, request)
        if (targets.isEmpty()) {
            logger.error("Nothing matched the re-transcription request")
            return false
        }

        if (config.dryRun) {
            targets.forEach { logger.info("Would re-transcribe ${it.parent.fileName}/${it.fileName}") }
            logger.info("Dry run: would re-transcribe ${targets.size} episodes")
            return true
        }

        logger.info("Re-transcribing ${targets.size} episodes")
        var done = 0
        var vadFailed = false
        for (target in targets) {
            val attempted =
                try {
                    val written = if (request.repairWindows) repairEpisode(target) else retranscribeEpisode(target, request.force)
                    if (written) done++
                    true
                } catch (e: WordTimesMisplaced) {
                    logger.error("Stopping: ${e.message}")
                    break
                } catch (e: SpeechDetectorFailed) {
                    logger.error("Stopping: ${e.message}. Fix vad_binary/vad_model, or remove them to repair without VAD")
                    vadFailed = true
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
        logger.info("Re-transcribed $done of ${targets.size} episodes")
        val paired = verifyBatch(targets)
        return decodingWorked() && paired && !vadFailed
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
            pending.forEach { logger.info("Would transcribe ${podcast.name}/${it.episodeDirPath.fileName}") }
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
        return writeTranscriptArtifacts(episodeDirPath, parsed.title ?: parsed.dirName, transcription, episodeDict)
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
            logger.warn("No word timestamps for $label; is token_timestamps still set?")
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

    private fun decodeAndScore(
        audioPath: Path,
        episodePath: Path,
        podcast: PodcastConfig,
        audio: AudioIdentity,
        conditioned: Boolean,
    ): ScoredTranscription {
        logger.debug("Starting transcription in {}", episodePath)
        val startTime = System.currentTimeMillis()
        val language = podcast.language ?: config.language
        val decodedAt = WhisperRun.now()

        val parsed = decode(audioPath, podcast, conditioned)

        // The mp3 is now the retained artifact -- the whisper server decodes and
        // resamples it itself, so the old audio.wav is dead weight.
        Files.deleteIfExists(episodePath.resolve("audio.wav"))
        Files.deleteIfExists(episodePath.resolve("transcript.txt"))

        val elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000.0
        logger.debug("Transcribed in: ${"%.2f".format(Locale.ROOT, elapsedMinutes)} Minutes")

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
            )
        val transcription = parsed.copy(run = run)
        return ScoredTranscription(transcription, TranscriptQuality.score(transcription))
    }

    /** One request to whisper, counted for the exit code, and refused if its word times are unusable. */
    private fun decode(
        audioPath: Path,
        podcast: PodcastConfig,
        conditioned: Boolean,
        window: TimeWindow? = null,
    ): WhisperTranscription {
        decodesAttempted++
        val json =
            try {
                transcriber.transcribe(audioPath, podcast.language ?: config.language, podcast.initialPrompt, conditioned, window)
            } catch (e: TranscriberUnavailable) {
                decodesUnreachable++
                throw e
            }
        decodesSucceeded++

        val parsed = WhisperTranscription.parse(json)
        val misplaced = parsed.misplacedWordShare
        if (misplaced > WhisperTranscription.MAX_MISPLACED_WORD_SHARE) {
            decodesSucceeded--
            decodesUnreachable++
            throw WordTimesMisplaced(
                "${"%.0f".format(Locale.ROOT, misplaced * 100)}% of cues from ${config.whisperServerUrl} have their words " +
                    "outside the cue's time, the signature of a server applying VAD whatever the request says. " +
                    "Restart it without --vad",
            )
        }
        return parsed
    }

    /**
     * Re-decodes only the windows around an episode's defects and splices in the
     * attempt that leaves each window with fewer defects than it had and no speech lost.
     * Only a pair already from one decode is repaired: the splice keeps its words.
     */
    private fun repairEpisode(episodeDirPath: Path): Boolean {
        val label = episodeDirPath.parent.fileName.toString() + "/" + episodeDirPath.fileName
        val audioPath = audioFileFor(episodeDirPath)
        if (!Files.exists(audioPath) || Files.size(audioPath) == 0L) {
            logger.error("Cannot repair $label: it has no audio")
            return false
        }
        when (val verdict = TranscriptPair.check(episodeDirPath)) {
            TranscriptPair.Consistent -> Unit
            TranscriptPair.Unpaired -> {
                logger.warn("Cannot repair $label: it has no word timings to splice into; re-transcribe it instead")
                return false
            }
            is TranscriptPair.Diverged -> {
                logger.warn("Cannot repair $label: ${verdict.reason}; re-transcribe it instead")
                return false
            }
            is TranscriptPair.Unreadable -> {
                logger.warn("Cannot repair $label: ${verdict.reason}")
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
            logger.warn("Cannot repair $label: a cue timestamp does not parse; re-transcribe it instead")
            return false
        }
        val cues = parsedCues.map { Cue(it.start!!, it.end!!, it.text.trimEnd('\n')) }

        val podcast = podcastFor(episodeDirPath)
        val prompt = WindowRepair.Prompt(podcast.initialPrompt ?: config.defaultPrompt)
        val defects = WindowRepair.defectCues(cues, prompt)
        if (defects.isEmpty()) {
            logger.info("$label has no loops, stretch-copies or prompt leaks to repair")
            return false
        }
        val wordsPath = episodeDirPath.resolve(WhisperTranscription.WORDS_FILENAME)
        if (!Files.exists(wordsPath)) {
            logger.warn("Cannot repair $label: its decode has no word timings to splice into; re-transcribe it instead")
            return false
        }
        val base = WhisperTranscription.of(cues, readWords(wordsPath))
        val speech =
            speechDetector?.speech(audioPath)?.takeIf { spans ->
                WindowRepair.plausible(base, defects, spans).also {
                    if (!it) logger.warn("VAD hears no speech under most of $label's cues; repairing it without VAD")
                }
            }

        val language = podcast.language ?: config.language
        val replacements = mutableListOf<WindowRepair.Replacement>()
        val repairs = mutableListOf<Map<String, Any?>>()
        val windows = WindowRepair.windows(cues, defects)
        var previousLast = 0
        for ((n, initial) in windows.withIndex()) {
            // Widening may share an anchor with the windows either side, never reach into what they replace.
            val lowest = previousLast
            val highest = windows.getOrNull(n + 1)?.first ?: cues.lastIndex
            var range = initial
            var widened = 0
            var tried = tryWindow(base, audioPath, podcast, range, defects, speech, prompt)
            while (tried.best == null && widened < MAX_WIDENINGS) {
                val first = if (tried.disputedLeft && range.first - 1 >= lowest) range.first - 1 else range.first
                val last = if (tried.disputedRight && range.last + 1 <= highest) range.last + 1 else range.last
                if (first == range.first && last == range.last) break
                range = first..last
                widened++
                tried = tryWindow(base, audioPath, podcast, range, defects, speech, prompt)
            }
            previousLast = range.last
            val best = tried.best
            val window = WindowRepair.window(cues, range)
            val before = defects.count { it in range }
            repairs +=
                mapOf(
                    "start" to window.start,
                    "end" to window.end,
                    "cues" to listOf(range.first, range.last),
                    "widened" to widened,
                    "defects_before" to before,
                    "defects_after" to if (best != null) tried.bestDefects else before,
                    "conditioned" to if (best != null) tried.bestConditioned else null,
                    "applied" to (best != null),
                    "replaced_cues" to best?.let { listOf(it.range.first, it.range.last) },
                    "anchor_left" to best?.anchorLeft,
                    "anchor_right" to best?.anchorRight,
                    "gap_left_s" to best?.let { WindowRepair.gaps(base, range, it).first },
                    "gap_right_s" to best?.let { WindowRepair.gaps(base, range, it).second },
                    "request" to best?.let { transcriber.requestFields(language, podcast.initialPrompt, tried.bestConditioned, window) },
                    "speech_checked" to (speech != null),
                    "unpunctuated" to best?.let { WindowRepair.unpunctuated(it) },
                    "refused_for_lost_speech_s" to tried.lost.map { Math.round(it * 10) / 10.0 },
                )
            best?.let { replacements += it }
        }
        if (replacements.isEmpty()) {
            logger.warn("No window of $label came back better than it was; keeping it")
            return false
        }

        val spliced = WindowRepair.splice(base, replacements)
        val audio = AudioIdentity(WhisperRun.sha256(audioPath), Files.size(audioPath))
        val run =
            WhisperRun(
                runId = WhisperRun.newId(),
                decodedAt = WhisperRun.now(),
                serverUrl = config.whisperServerUrl,
                model = config.whisperModel,
                // Every cue outside the windows is still the base's decode; each window records its own request.
                request = requestOf(existing) ?: transcriber.requestFields(language, podcast.initialPrompt),
                audioSha256 = audio.sha256,
                audioBytes = audio.bytes,
                pipelineVersion = WhisperRun.pipelineVersion,
                words = spliced.words.size,
                base = existing[WhisperRun.FIELD] as? Map<*, *>,
                repairs = repairs,
            )
        val repaired = spliced.copy(run = run)
        val quality = TranscriptQuality.score(repaired)
        val updated = existing.toMutableMap()
        updated["episode_transcript"] = repaired.vtt
        updated["episode_quality"] = quality.toMap()
        if (!writeTranscriptArtifacts(episodeDirPath, label, repaired, updated, replace = true, runIdOf(existing))) return false
        logger.info(
            "Repaired $label: ${replacements.size} of ${repairs.size} windows, " +
                "${defects.size} defective cues before, ${WindowRepair.defectCues(repaired.cues, prompt).size} after",
        )
        return true
    }

    private class Tried(
        val best: WindowRepair.Replacement?,
        val bestDefects: Int,
        val bestConditioned: Boolean,
        val lost: List<Double>,
        val disputedLeft: Boolean,
        val disputedRight: Boolean,
    )

    /**
     * Every attempt at one window, keeping the one that leaves it with fewest defects and no speech lost. An attempt
     * whose new words reach seconds into an anchor says that anchor is not what was said there, and is refused.
     */
    private fun tryWindow(
        base: WhisperTranscription,
        audioPath: Path,
        podcast: PodcastConfig,
        range: IntRange,
        defects: Set<Int>,
        speech: List<TimeWindow>?,
        prompt: WindowRepair.Prompt,
    ): Tried {
        val window = WindowRepair.window(base.cues, range)
        var best: WindowRepair.Replacement? = null
        var bestDefects = defects.count { it in range }
        var bestConditioned = true
        val lost = mutableListOf<Double>()
        var disputedLeft = false
        var disputedRight = false
        for (conditioned in REPAIR_ATTEMPTS) {
            val anchored = WindowRepair.anchor(base, decode(audioPath, podcast, conditioned, window), range, defects)
            if (anchored.intoLeft > WindowRepair.MAX_INTO_ANCHOR_SECONDS || anchored.intoRight > WindowRepair.MAX_INTO_ANCHOR_SECONDS) {
                disputedLeft = disputedLeft || anchored.intoLeft > WindowRepair.MAX_INTO_ANCHOR_SECONDS
                disputedRight = disputedRight || anchored.intoRight > WindowRepair.MAX_INTO_ANCHOR_SECONDS
                continue
            }
            val replacement = speech?.let { WindowRepair.dropNonSpeech(anchored, it) } ?: anchored
            // Nothing said is a repair only where VAD confirms nothing is said.
            if (replacement.words.isEmpty() && (speech == null || !WindowRepair.silent(base, replacement.range, speech))) continue
            val missed = WindowRepair.lostSpeech(base, replacement, speech ?: WindowRepair.spokenIn(base, replacement.range, defects))
            if (missed > WindowRepair.MAX_LOST_SPEECH_SECONDS) {
                lost += missed
                continue
            }
            val after = WindowRepair.defectsAfter(base, replacement, prompt, defects)
            if (after < bestDefects) {
                best = replacement
                bestDefects = after
                bestConditioned = conditioned
            }
            if (bestDefects == 0) break
        }
        return Tried(best, bestDefects, bestConditioned, lost, disputedLeft, disputedRight)
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

        /**
         * Prompted, then without history, twice over. whisper can skip a stretch of
         * speech on one decode and transcribe it on the next, identical request: a
         * phone-quality Irish History window lost 24 s on one no-history decode and
         * none on the other. A window decode takes seconds, so the retries are cheap.
         */
        private val REPAIR_ATTEMPTS = listOf(true, false, true, false)

        /** How many cues a window may grow by, one per side each time, when an attempt disputes its anchors. */
        private const val MAX_WIDENINGS = 2
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
