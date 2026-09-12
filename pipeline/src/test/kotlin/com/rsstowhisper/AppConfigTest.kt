package com.rsstowhisper

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {
    @Test
    fun `loadDotEnv parses key=value pairs`() {
        val result =
            AppConfig.loadDotEnv(
                """
                FOO=bar
                BAZ=qux
                """.trimIndent(),
            )
        assertEquals(mapOf("FOO" to "bar", "BAZ" to "qux"), result)
    }

    @Test
    fun `loadDotEnv ignores blank lines and comments`() {
        val result =
            AppConfig.loadDotEnv(
                """
                # a comment
                FOO=bar

                # another comment
                BAZ=qux
                """.trimIndent(),
            )
        assertEquals(mapOf("FOO" to "bar", "BAZ" to "qux"), result)
    }

    @Test
    fun `loadDotEnv handles empty input`() {
        assertEquals(emptyMap(), AppConfig.loadDotEnv(""))
    }

    @Test
    fun `load overrides dataDirectory and whisperServerUrl from env`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("data_directory: from-yaml\nwhisper_server_url: from-yaml\nverbose: false\n")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/env/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://env-whisper",
                ),
            )

        assertEquals("/env/data", config.dataDirectory)
        assertEquals("http://env-whisper", config.whisperServerUrl)
    }

    /**
     * The shipped example is what people copy, so it has to parse against the
     * real schema -- a key that silently doesn't bind is invisible until someone
     * wonders why their setting does nothing.
     */
    @Test
    fun `load parses the shipped pods yaml example`() {
        val example =
            listOf(File("../pods.yaml.example"), File("pods.yaml.example"))
                .firstOrNull { it.exists() }
                ?: error("pods.yaml.example not found relative to the test working directory")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to example.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                ),
            )

        assertEquals(20, config.skipAfterConsecutive)
        assertEquals(150, config.minEpisodeDurationSeconds)
        assertTrue(config.recoverOrphans)
        assertEquals(0, config.orphanRecoveryLimit)
        assertEquals(AppConfig.DEFAULT_EXCLUDE_TITLE_KEYWORDS, config.excludeTitleKeywords)
        assertEquals("en", config.language)
        assertEquals(3, config.podcasts.size)
        assertEquals("Ask a Spaceman", config.podcasts[0].name)
        assertEquals(listOf("science", "space", "astrophysics"), config.podcasts[0].collections)
        assertEquals(null, config.podcasts[0].minEpisodeDurationSeconds)
        assertEquals(0, config.podcasts[1].minEpisodeDurationSeconds)
        // Null is what makes the podcast inherit the top-level language.
        assertEquals(null, config.podcasts[0].language)
        assertEquals("fr", config.podcasts[2].language)
    }

    @Test
    fun `load sets verbose from env`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("verbose: false\n")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                    "PIPELINE_VERBOSE" to "true",
                ),
            )

        assertEquals(true, config.verbose)
    }

    @Test
    fun `load defaults verbose to false when absent`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("podcasts: []\n")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                ),
            )

        assertEquals(false, config.verbose)
    }

    @Test
    fun `load keeps yaml verbose when env var is absent`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("verbose: true\n")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                ),
            )

        assertEquals(true, config.verbose)
    }

    @Test
    fun `load lets env verbose override yaml verbose`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("verbose: true\n")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                    "PIPELINE_VERBOSE" to "false",
                ),
            )

        assertEquals(false, config.verbose)
    }

    @Test
    fun `load keeps yaml verbose when env var is blank`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("verbose: true\n")

        val config =
            AppConfig.load(
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                    "PIPELINE_VERBOSE" to "",
                ),
            )

        assertEquals(true, config.verbose)
    }

    @Test
    fun `load lets args override env for every value`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("verbose: false\n")
        val argsYaml = tmp.resolve("args.yaml").toFile()
        argsYaml.writeText("verbose: false\nskip_after_consecutive: 7\n")

        val config =
            AppConfig.load(
                Args(
                    configPath = argsYaml.absolutePath,
                    dataDirectory = "/args/data",
                    whisperServerUrl = "http://args-whisper",
                    verbose = true,
                ),
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/env/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://env-whisper",
                    "PIPELINE_VERBOSE" to "false",
                ),
            )

        assertEquals(7, config.skipAfterConsecutive)
        assertEquals("/args/data", config.dataDirectory)
        assertEquals("http://args-whisper", config.whisperServerUrl)
        assertEquals(true, config.verbose)
    }

    @Test
    fun `load works from args alone with no env at all`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("podcasts: []\n")

        val config =
            AppConfig.load(
                Args(
                    configPath = yaml.absolutePath,
                    dataDirectory = "/args/data",
                    whisperServerUrl = "http://args-whisper",
                ),
                emptyMap(),
            )

        assertEquals("/args/data", config.dataDirectory)
        assertEquals("http://args-whisper", config.whisperServerUrl)
        assertEquals(false, config.verbose)
    }

    @Test
    fun `load lets --no-verbose override a true env var`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("verbose: true\n")

        val config =
            AppConfig.load(
                Args(verbose = false),
                mapOf(
                    "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                    "PIPELINE_DATA_DIRECTORY" to "/data",
                    "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                    "PIPELINE_VERBOSE" to "true",
                ),
            )

        assertEquals(false, config.verbose)
    }

    @Test
    fun `load errors when PIPELINE_CONFIG_PATH is missing`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(
                    mapOf(
                        "PIPELINE_DATA_DIRECTORY" to "/data",
                        "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                    ),
                )
            }
        assertEquals("PIPELINE_CONFIG_PATH must be set in .env, or passed as --config", ex.message)
    }

    @Test
    fun `load errors when PIPELINE_DATA_DIRECTORY is missing`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("podcasts: []\n")

        val ex =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(
                    mapOf(
                        "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                        "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
                    ),
                )
            }
        assertEquals("PIPELINE_DATA_DIRECTORY must be set in .env, or passed as --data-dir", ex.message)
    }

    @Test
    fun `load errors when PIPELINE_WHISPER_SERVER_URL is missing`(
        @TempDir tmp: Path,
    ) {
        val yaml = tmp.resolve("pods.yaml").toFile()
        yaml.writeText("podcasts: []\n")

        val ex =
            assertFailsWith<IllegalStateException> {
                AppConfig.load(
                    mapOf(
                        "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                        "PIPELINE_DATA_DIRECTORY" to "/data",
                    ),
                )
            }
        assertEquals("PIPELINE_WHISPER_SERVER_URL must be set in .env, or passed as --whisper-url", ex.message)
    }

    @Test
    fun `orphan recovery is on unless pods yaml or a flag turns it off`(
        @TempDir tempDir: Path,
    ) {
        val yaml = tempDir.resolve("pods.yaml").toFile()
        yaml.writeText("recover_orphans: false\norphan_recovery_limit: 25\n")
        val env =
            mapOf(
                "PIPELINE_CONFIG_PATH" to yaml.absolutePath,
                "PIPELINE_DATA_DIRECTORY" to "/data",
                "PIPELINE_WHISPER_SERVER_URL" to "http://whisper",
            )

        assertFalse(AppConfig.load(env).recoverOrphans)
        assertEquals(25, AppConfig.load(env).orphanRecoveryLimit)

        // The flag wins over pods.yaml, same as every other setting.
        val overridden = AppConfig.load(Args(recoverOrphans = true, orphanRecoveryLimit = 5), env)
        assertTrue(overridden.recoverOrphans)
        assertEquals(5, overridden.orphanRecoveryLimit)
    }
}
