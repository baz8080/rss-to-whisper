package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerPodcastPromptTest {
    private fun run(
        tempDir: Path,
        podcast: PodcastConfig,
    ): FakeTranscriber {
        val (pipeline, txSvc, _) = buildPipeline(tempDir, listOf(podcast), makeFeed(makeEntry("Episode")))
        pipeline.run()
        return txSvc
    }

    @Test
    fun `a podcast's own prompt reaches the decode`(
        @TempDir tempDir: Path,
    ) {
        val txSvc =
            run(
                tempDir,
                PodcastConfig(
                    name = "Une Emission",
                    url = "https://feed",
                    language = "fr",
                    initialPrompt = "Bonjour, et bienvenue dans cette emission.",
                ),
            )

        assertEquals(listOf("fr"), txSvc.languages)
        assertEquals(listOf<String?>("Bonjour, et bienvenue dans cette emission."), txSvc.prompts)
    }

    /** Null means "no override", which is what lets the default prompt's own language matching apply. */
    @Test
    fun `a podcast with no prompt of its own passes none`(
        @TempDir tempDir: Path,
    ) {
        val txSvc = run(tempDir, PodcastConfig(name = "Show", url = "https://feed"))

        assertEquals(listOf<String?>(null), txSvc.prompts)
        assertNull(txSvc.prompts.single())
    }

    /**
     * The gap #67 exists to close: a feed that overrode only the language got no
     * prompt at all, so it was exposed to the unpunctuated decodes the prompt
     * protects English feeds from. It can now opt back in.
     */
    @Test
    fun `overriding the language without a prompt still passes none`(
        @TempDir tempDir: Path,
    ) {
        val txSvc = run(tempDir, PodcastConfig(name = "Une Emission", url = "https://feed", language = "fr"))

        assertEquals(listOf("fr"), txSvc.languages)
        assertNull(txSvc.prompts.single())
    }
}
