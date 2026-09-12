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
import com.rsstowhisper.createPath
import com.rsstowhisper.escapeFilename
import com.rsstowhisper.external.Transcriber
import com.rsstowhisper.external.WhisperTranscription
import com.rsstowhisper.feed.FeedService
import com.rsstowhisper.timeToSeconds
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class PodcastPipeline(
    private val config: AppConfig,
    httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build(),
    private val feedService: FeedService = FeedService(httpClient),
    private val transcriber: Transcriber = Transcriber(config.whisperServerUrl),
) {
    /** Spent across the whole run, not per podcast, so one show cannot use up the budget. */
    private var orphansRecovered = 0

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

        if (!Files.isWritable(Path.of(dataDir))) {
            logger.error("The data_dir is missing, or not writable. Cannot continue")
            return false
        }

        for (podcast in config.podcasts) {
            processPodcast(podcast, dataDir)
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
        if (!Files.isWritable(dataDir)) {
            logger.error("The data_dir is missing, or not writable. Cannot continue")
            return false
        }

        val targets = RetranscribeTargets.find(dataDir, request)
        if (targets.isEmpty()) {
            logger.error("Nothing matched the re-transcription request")
            return false
        }

        logger.info("Re-transcribing ${targets.size} episodes")
        var done = 0
        for (target in targets) {
            try {
                if (retranscribeEpisode(target)) done++
            } catch (e: Exception) {
                logger.error("Could not re-transcribe ${target.fileName}", e)
            }
        }
        logger.info("Re-transcribed $done of ${targets.size} episodes")
        return true
    }

    private fun retranscribeEpisode(episodeDirPath: Path): Boolean {
        val label = episodeDirPath.parent.fileName.toString() + "/" + episodeDirPath.fileName
        val audioPath = episodeDirPath.resolve(AUDIO_FILENAME)
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

        val previous = QualityReport.fromMap(existing["episode_quality"] as? Map<*, *>)

        val scored = transcribeEpisode(audioPath, episodeDirPath, podcastFor(episodeDirPath), label)
        if (scored.transcription.isEmpty) {
            logger.warn("Re-transcription of $label found no speech; keeping the existing transcript")
            return false
        }

        // Word times are not part of the score -- low-confidence cannot even be
        // raised without them -- so a decode that came back with none reads as
        // an improvement on a transcript flagged for low confidence, and would
        // overwrite it and clear the flag. The server was asked for
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

        // Whisper is not deterministic, so a re-decode can come back worse than
        // the transcript it would overwrite, and this write is the only copy of
        // it. Judged the way transcribeEpisode judges its retry, so redoing an
        // episode can improve it or leave it alone, never cost it a decode.
        if (previous != null && previous.isBetterThan(scored.quality)) {
            logger.warn(
                "Re-transcription of $label scored worse than what is on disk " +
                    "(${scored.quality.summary} against ${previous.summary}); keeping the existing transcript",
            )
            return false
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

        if (!writeTranscriptArtifacts(episodeDirPath, label, scored.transcription, updated, replace = true)) return false
        logger.info("Re-transcribed $label")
        return true
    }

    /** Matches the directory back to its feed so the decode keeps the podcast's language. */
    private fun podcastFor(episodeDirPath: Path): PodcastConfig {
        val podcastDir = episodeDirPath.parent.fileName.toString()
        config.podcasts.firstOrNull { escapeFilename(it.name).equals(podcastDir, ignoreCase = true) }
            ?.let { return it }

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

        val podPath = createPath(Path.of(dataDir), podcast.name)
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
                    continue
                }

                val stablePrefix = episodeStablePrefix(entry)
                logger.debug("Processing $stablePrefix")
                // A feed that publishes one guid twice would otherwise get a directory and a decode per copy.
                if (!examined.add(stablePrefix)) {
                    logger.debug("Skipping ${entry.title}: the feed already listed this episode")
                    continue
                }

                val existingDir = findExistingEpisodeDir(podPath, stablePrefix)
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
                val mp3Info = getMp3Info(entry, episodeDirPath, dataDir)
                if (mp3Info == null) {
                    logger.warn("${entry.title} has no mp3 link. Skipping")
                    continue
                }
                pending += PendingEpisode(entry, episodeDirPath, mp3Info)
            } catch (e: Exception) {
                logger.error("Couldn't process episode entry: ${entry.title}", e)
            }
        }

        transcribeAll(feed, podcast, pending)

        try {
            scanForOrphans(feed, podcast, podPath, dataDir, prefixes, examined, brokeEarly)
        } catch (e: Exception) {
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

        try {
            for ((index, episode) in pending.withIndex()) {
                val entry = episode.entry
                val current = next ?: submitDownload(prefetcher, episode)
                // Queued behind the current download on the single thread, so it runs during the decode.
                next = pending.getOrNull(index + 1)?.let { submitDownload(prefetcher, it) }
                try {
                    if (!current.get()) {
                        logger.warn("Could not download audio for ${entry.title}. Skipping")
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
                    writeEpisodeJson(feed, entry, episode.mp3Info, episode.episodeDirPath, podcast.collections, scored)
                } catch (e: Exception) {
                    // An Error on the prefetch thread arrives wrapped, and must still end the run.
                    val cause = (e as? ExecutionException)?.cause ?: e
                    if (cause is Error) throw cause
                    logger.error("Couldn't process episode entry: ${entry.title}", cause)
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

        val onDisk =
            try {
                Files.newDirectoryStream(podPath).use { stream ->
                    stream.mapNotNull { EpisodeDirName.parse(it.fileName.toString()) }
                }
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
                    Files.newDirectoryStream(episodeDirPath).use { s -> s.mapTo(HashSet()) { it.fileName.toString() } }
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
                        logger.info("${podcast.name}: recovering episodes that are no longer in the feed")
                    }
                    try {
                        if (recoverEpisode(feed, podcast, episodeDirPath, parsed, dataDir)) {
                            recovered++
                            orphansRecovered++
                        }
                    } catch (e: Exception) {
                        logger.error("Couldn't recover ${parsed.dirName}", e)
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
                "($recovered recovered, $alreadyDone already settled, $withoutAudio without audio, " +
                "$pending not examined)",
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

    private fun recoverEpisode(
        feed: SyndFeed,
        podcast: PodcastConfig,
        episodeDirPath: Path,
        parsed: EpisodeDirName,
        dataDir: String,
    ): Boolean {
        val audioPath = episodeDirPath.resolve(AUDIO_FILENAME)
        if (Files.size(audioPath) == 0L) {
            logger.warn("Cannot recover ${parsed.dirName}: its audio file is empty")
            return false
        }

        logger.info("Recovering ${parsed.dirName}")
        val scored =
            try {
                transcribeEpisode(audioPath, episodeDirPath, podcast, parsed.dirName)
            } catch (e: Exception) {
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
                relativeAudioPath = Path.of(dataDir).relativize(audioPath).toString(),
                durationSeconds = transcription.durationSeconds,
                collections = podcast.collections,
                quality = scored.quality,
            ) ?: return false

        writeTranscriptArtifacts(episodeDirPath, parsed.title ?: parsed.dirName, transcription, episodeDict)
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
    ) {
        val transcription = scored.transcription
        if (Files.exists(episodeDirPath.resolve(TRANSCRIPT_FILENAME))) return
        if (transcription.isEmpty) {
            logger.warn("Transcription for ${entry.title} came back empty; not writing transcript.json")
            return
        }

        val episodeDict =
            buildEpisodeDict(feed, entry, transcription.vtt, mp3Info.localFilePath, collections, scored.quality)
                ?: return

        writeTranscriptArtifacts(episodeDirPath, entry.title ?: episodeDirPath.fileName.toString(), transcription, episodeDict)
    }

    /** False when nothing was written, so a caller cannot report an episode it still has to redo. */
    private fun writeTranscriptArtifacts(
        episodeDirPath: Path,
        label: String,
        transcription: WhisperTranscription,
        episodeDict: Map<String, Any?>,
        replace: Boolean = false,
    ): Boolean {
        val jsonPath = episodeDirPath.resolve(TRANSCRIPT_FILENAME)
        val wordsPath = episodeDirPath.resolve(WhisperTranscription.WORDS_FILENAME)

        if (!replace) {
            // Re-checked here rather than only at the callers: transcription takes minutes,
            // and a second instance over the same data directory may have finished this
            // episode while this one was decoding it.
            if (Files.exists(jsonPath)) {
                logger.warn("$label was transcribed by something else while this run was working on it")
                return false
            }

            // Words FIRST. transcript.json existing is what marks an episode
            // done, both here and for anything reading the tree, so writing it
            // last means a crash in between leaves the episode to be redone
            // rather than leaving it permanently without its sidecar.
            // A crash that got as far as the sidecar can leave one behind, so a
            // decode with no word times has to clear it rather than adopt it.
            if (!writeWords(transcription, wordsPath, label)) {
                Files.deleteIfExists(wordsPath)
                // writeWords swallows its exception, so without this the run
                // would walk straight past the ordering above.
                if (transcription.words.isNotEmpty()) {
                    logger.error("Not writing $label: its word timestamps could not be written")
                    return false
                }
            }
            Files.writeString(jsonPath, jsonMapper.writeValueAsString(episodeDict))
            return true
        }

        // The sidecar addresses cues by position, so either file left beside
        // the other's transcript mis-times every word -- silently and for good,
        // since an episode with a transcript.json is one nothing revisits. So
        // it is absent for the whole swap, cleared first and restored last: an
        // interrupted replace leaves it visibly missing rather than wrong.
        val stagedJson = episodeDirPath.resolve("$TRANSCRIPT_FILENAME${stagingSuffix()}")
        val stagedWords = episodeDirPath.resolve("${WhisperTranscription.WORDS_FILENAME}${stagingSuffix()}")
        try {
            Files.writeString(stagedJson, jsonMapper.writeValueAsString(episodeDict))
            val haveWords = writeWords(transcription, stagedWords, label)
            // The clear below would otherwise take the existing word times
            // down with it. A full disk should cost the run this episode.
            if (!haveWords && transcription.words.isNotEmpty()) {
                logger.error("Not replacing $label: its word timestamps could not be written")
                return false
            }

            Files.deleteIfExists(wordsPath)
            replaceWith(stagedJson, jsonPath)
            if (haveWords) replaceWith(stagedWords, wordsPath)
            return true
        } finally {
            runCatching { Files.deleteIfExists(stagedJson) }
            runCatching { Files.deleteIfExists(stagedWords) }
        }
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
        val first = decodeAndScore(audioPath, episodePath, podcast)
        if (!first.quality.isFlagged || !config.qualityRetry) return warnIfFlagged(first, label)

        logger.info("$label scored ${first.quality.flags}; decoding it once more")
        val second =
            try {
                decodeAndScore(audioPath, episodePath, podcast)
            } catch (e: Exception) {
                // The first decode is still a usable transcript; a failed retry
                // must not cost the episode entirely.
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
    ): ScoredTranscription {
        logger.debug("Starting transcription in {}", episodePath)
        val startTime = System.currentTimeMillis()

        val json = transcriber.transcribe(audioPath, podcast.language ?: config.language)

        // The mp3 is now the retained artifact -- the whisper server decodes and
        // resamples it itself, so the old audio.wav is dead weight.
        Files.deleteIfExists(episodePath.resolve("audio.wav"))
        Files.deleteIfExists(episodePath.resolve("transcript.txt"))

        val elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000.0
        logger.debug("Transcribed in: ${"%.2f".format(elapsedMinutes)} Minutes")

        val transcription = WhisperTranscription.parse(json)
        return ScoredTranscription(transcription, TranscriptQuality.score(transcription))
    }

    companion object {
        internal const val TRANSCRIPT_FILENAME = "transcript.json"

        internal const val STAGING_SUFFIX = ".new"
        internal const val AUDIO_FILENAME = "audio.mp3"

        /** Deliberately extension-less: nothing walking the tree for transcripts will pick it up. */
        internal const val RECOVERY_FAILED_FILENAME = "recovery-failed"

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

        /**
         * A directory name with no `-<hex8>-` component cannot be resolved back
         * to its audio: 97 episodes once stored one, and the keys pointed at
         * nothing until a repair pass derived them from disk. Nothing about the
         * construction below can produce that today, which is exactly why it is
         * worth asserting -- a silent recurrence costs a re-ingest to find.
         */
        fun getEpisodeDirName(entry: SyndEntry): String {
            val name = "${episodeStablePrefix(entry)}-${entry.title ?: "unknown"}"
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
            dataDir: String,
        ): Mp3Info? {
            val source = findAudioSource(entry) ?: return null
            val filePath = episodePath.resolve(AUDIO_FILENAME)

            return Mp3Info(
                url = source.url,
                filePath = filePath,
                length = source.length,
                localFilePath = Path.of(dataDir).relativize(filePath).toString(),
            )
        }

        fun buildEpisodeDict(
            feed: SyndFeed,
            entry: SyndEntry,
            transcript: String,
            relativeAudioPath: String,
            collections: List<String>? = null,
            quality: QualityReport? = null,
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
                        "episode_relative_audio_path" to relativeAudioPath,
                        "episode_quality" to quality?.toMap(),
                    )
            } catch (e: Exception) {
                logger.error("Error getting podcast metadata", e)
                null
            }
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
         * supplies everything about the podcast. Nothing else is recoverable, and every
         * unrecoverable field is explicitly null -- an empty string would reach the web
         * module's `!= null` guards and render a dead link.
         */
        internal fun buildRecoveredEpisodeDict(
            feed: SyndFeed,
            parsed: EpisodeDirName,
            transcript: String,
            relativeAudioPath: String,
            durationSeconds: Int?,
            collections: List<String>? = null,
            quality: QualityReport? = null,
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
                        "episode_relative_audio_path" to relativeAudioPath,
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
    val localFilePath: String,
)
