package com.rsstowhisper

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.rsstowhisper.external.Transcriber
import java.io.File

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppConfig(
    val verbose: Boolean = false,
    val dataDirectory: String = "",
    val whisperServerUrl: String = "",
    val skipAfterConsecutive: Int = 20,
    val excludeTitleKeywords: List<String> = DEFAULT_EXCLUDE_TITLE_KEYWORDS,
    val minEpisodeDurationSeconds: Int = 150,
    val recoverOrphans: Boolean = true,
    /** 0 means no limit. Counted across the whole run, not per podcast. */
    val orphanRecoveryLimit: Int = 0,
    /** Fallback for podcasts that do not set their own. See [PodcastConfig.language]. */
    val language: String = Transcriber.DEFAULT_LANGUAGE,
    /** Decode a flagged transcript once more and keep the better of the two. */
    val qualityRetry: Boolean = true,
    val podcasts: List<PodcastConfig> = emptyList(),
) {
    companion object {
        // Whole-word matches only: a substring match on "repeat" also swallows
        // "Repeating FRB Mystery", and "archives" swallows "Inside the Archives".
        val DEFAULT_EXCLUDE_TITLE_KEYWORDS =
            listOf(
                "trailer",
                "introducing",
                "encore",
                "classic episode",
                "rewind",
                "re-release",
                "re-run",
                "rerun",
                "rebroadcast",
                "best of",
                "repeat",
                "replay",
                "from the archives",
            )

        private val mapper =
            ObjectMapper(YAMLFactory())
                .registerModule(KotlinModule.Builder().build())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

        internal fun load(args: Args): AppConfig = load(args, loadDotEnv())

        internal fun load(env: Map<String, String>): AppConfig = load(Args(), env)

        internal fun load(
            args: Args,
            env: Map<String, String>,
        ): AppConfig {
            val configPath =
                args.configPath
                    ?: env["PIPELINE_CONFIG_PATH"]
                    ?: error("PIPELINE_CONFIG_PATH must be set in .env, or passed as --config")
            val dataDirectory =
                args.dataDirectory
                    ?: env["PIPELINE_DATA_DIRECTORY"]
                    ?: error("PIPELINE_DATA_DIRECTORY must be set in .env, or passed as --data-dir")
            val whisperServerUrl =
                args.whisperServerUrl
                    ?: env["PIPELINE_WHISPER_SERVER_URL"]
                    ?: error("PIPELINE_WHISPER_SERVER_URL must be set in .env, or passed as --whisper-url")
            // Blank means "not set" -- an empty PIPELINE_VERBOSE= line must fall
            // through to pods.yaml rather than forcing it off via toBoolean().
            val envVerbose = env["PIPELINE_VERBOSE"]?.takeIf { it.isNotBlank() }?.toBoolean()

            val raw: AppConfig = mapper.readValue(File(configPath))
            return raw.copy(
                dataDirectory = dataDirectory,
                whisperServerUrl = whisperServerUrl,
                verbose = args.verbose ?: envVerbose ?: raw.verbose,
                recoverOrphans = args.recoverOrphans ?: raw.recoverOrphans,
                orphanRecoveryLimit = args.orphanRecoveryLimit ?: raw.orphanRecoveryLimit,
                qualityRetry = args.qualityRetry ?: raw.qualityRetry,
            )
        }

        internal fun loadDotEnv(): Map<String, String> {
            // Support running from the repo root or from inside pipeline/ (e.g. installDist).
            val file =
                sequenceOf(File("pipeline/.env"), File(".env"))
                    .firstOrNull { it.exists() }
                    ?: return emptyMap()
            return loadDotEnv(file.readText())
        }

        internal fun loadDotEnv(content: String): Map<String, String> =
            content
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq < 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                }.toMap()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PodcastConfig(
    val name: String,
    val url: String,
    val collections: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val minEpisodeDurationSeconds: Int? = null,
    /**
     * ISO 639-1 code for this feed, or "auto" to let whisper detect it.
     * Null falls back to the top-level `language` in pods.yaml.
     */
    val language: String? = null,
)
