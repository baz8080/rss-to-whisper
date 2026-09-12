package com.rsstowhisper

internal val USAGE =
    """
    Usage: pipeline [options]

    Options:
      --config <path>        Path to pods.yaml                  (PIPELINE_CONFIG_PATH)
      --data-dir <path>      Where audio and transcripts go     (PIPELINE_DATA_DIRECTORY)
      --whisper-url <url>    Base URL of the whisper.cpp server (PIPELINE_WHISPER_SERVER_URL)
      --verbose              Enable debug logging               (PIPELINE_VERBOSE)
      --no-verbose           Force debug logging off
      --recover-orphans      Transcribe episodes that aged out of their feed (default)
      --no-recover-orphans   Follow the feed only
      --orphan-limit <n>     Recover at most n orphans this run; 0 means no limit
      --quality-retry        Decode a flagged transcript a second time (default)
      --no-quality-retry     Keep the first decode whatever it scores
      -h, --help             Show this message

    Re-transcription (any of these skips the feeds entirely and redoes episodes
    already on disk, keeping every field of transcript.json but the transcript):

      --retranscribe <dir>       <podcast dir>/<episode dir>; repeatable
      --retranscribe-id <hex8>   The id in an episode directory name; repeatable
      --retranscribe-flagged     Every episode with episode_quality flags. Reads
                                 every transcript.json, which is slow on a
                                 network volume
      --retranscribe-limit <n>   Cap --retranscribe-flagged; 0 means no limit

    Options override .env, which overrides pods.yaml. Give a second instance its
    own --config and --whisper-url to run two feeds against two whisper servers.
    """.trimIndent()

/** Null means "not given", so each field can fall through to .env and then pods.yaml. */
internal data class Args(
    val configPath: String? = null,
    val dataDirectory: String? = null,
    val whisperServerUrl: String? = null,
    val verbose: Boolean? = null,
    val recoverOrphans: Boolean? = null,
    val orphanRecoveryLimit: Int? = null,
    val qualityRetry: Boolean? = null,
    val retranscribePaths: List<String> = emptyList(),
    val retranscribeIds: List<String> = emptyList(),
    val retranscribeFlagged: Boolean = false,
    val retranscribeLimit: Int = 0,
    val help: Boolean = false,
) {
    /** Any target at all switches the run out of following feeds. */
    val isRetranscribe: Boolean
        get() = retranscribePaths.isNotEmpty() || retranscribeIds.isNotEmpty() || retranscribeFlagged
}

internal fun parseArgs(argv: Array<String>): Args {
    var args = Args()
    var i = 0
    while (i < argv.size) {
        val flag = argv[i]
        args =
            when (flag) {
                "--config" -> args.copy(configPath = valueFor(flag, argv, ++i))
                "--data-dir" -> args.copy(dataDirectory = valueFor(flag, argv, ++i))
                "--whisper-url" -> args.copy(whisperServerUrl = valueFor(flag, argv, ++i))
                "--verbose" -> args.copy(verbose = true)
                "--no-verbose" -> args.copy(verbose = false)
                "--recover-orphans" -> args.copy(recoverOrphans = true)
                "--no-recover-orphans" -> args.copy(recoverOrphans = false)
                "--orphan-limit" -> args.copy(orphanRecoveryLimit = intValueFor(flag, argv, ++i))
                "--quality-retry" -> args.copy(qualityRetry = true)
                "--no-quality-retry" -> args.copy(qualityRetry = false)
                "--retranscribe" ->
                    args.copy(retranscribePaths = args.retranscribePaths + valueFor(flag, argv, ++i))
                "--retranscribe-id" ->
                    args.copy(retranscribeIds = args.retranscribeIds + valueFor(flag, argv, ++i))
                "--retranscribe-flagged" -> args.copy(retranscribeFlagged = true)
                "--retranscribe-limit" -> args.copy(retranscribeLimit = intValueFor(flag, argv, ++i))
                "-h", "--help" -> args.copy(help = true)
                else ->
                    if (flag.startsWith("-")) {
                        error("Unknown option: $flag (try --help)")
                    } else {
                        error("Unexpected argument: $flag (try --help)")
                    }
            }
        i++
    }
    return args
}

private fun intValueFor(
    flag: String,
    argv: Array<String>,
    index: Int,
): Int =
    valueFor(flag, argv, index).toIntOrNull()?.takeIf { it >= 0 }
        ?: error("$flag needs a non-negative whole number (try --help)")

private fun valueFor(
    flag: String,
    argv: Array<String>,
    index: Int,
): String =
    argv.getOrNull(index)?.takeUnless { it.startsWith("-") }
        ?: error("$flag needs a value (try --help)")
