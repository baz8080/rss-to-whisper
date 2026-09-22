package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import com.rsstowhisper.escapeFilename
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeDirNameTest {
    @Test
    fun `parses a well formed directory name`() {
        val parsed = assertNotNull(EpisodeDirName.parse("2024-03-14-ab12cd34-Some-Episode-Title"))
        assertEquals("2024-03-14", parsed.publishedOn)
        assertEquals("ab12cd34", parsed.episodeId)
        assertEquals("2024-03-14-ab12cd34", parsed.stablePrefix)
        assertEquals("Some Episode Title", parsed.title)
    }

    @Test
    fun `returns null for a name with no date prefix`() {
        assertNull(EpisodeDirName.parse("unknown-date-ab12cd34-Title"))
        assertNull(EpisodeDirName.parse("random-folder"))
        assertNull(EpisodeDirName.parse("podcasts.db"))
    }

    @Test
    fun `returns null for uppercase hex, which this pipeline never produces`() {
        assertNull(EpisodeDirName.parse("2024-03-14-ABCDEF12-Title"))
    }

    @Test
    fun `returns null when the id is not followed by a title separator`() {
        assertNull(EpisodeDirName.parse("2024-03-14-ab12cd34"))
        assertNull(EpisodeDirName.parse("2024-03-14-ab12cd3-Title"))
    }

    @Test
    fun `title is null when the slug is empty`() {
        assertNull(assertNotNull(EpisodeDirName.parse("2024-03-14-ab12cd34-")).title)
        assertNull(assertNotNull(EpisodeDirName.parse("2024-03-14-ab12cd34---")).title)
    }

    @Test
    fun `matches agrees with parse`() {
        assertTrue(EpisodeDirName.matches("2024-03-14-ab12cd34-Title"))
        assertFalse(EpisodeDirName.matches("unknown-date-ab12cd34-Title"))
    }

    @Test
    fun `a title with no ASCII letters still gets a parseable directory`() {
        for (title in listOf("\u65e5\u672c\u306e\u8a71", "\u041f\u0440\u0438\u0432\u0435\u0442", "\u2014", "")) {
            val entry = makeEntry(title = title, publishedDate = Date(1_700_000_000_000L), guid = "g-$title")
            val parsed = assertNotNull(EpisodeDirName.parse(escapeFilename(PodcastPipeline.getEpisodeDirName(entry))))
            assertEquals("unknown", parsed.titleSlug, title)
        }
    }

    @Test
    fun `podcastForDir matches an accented directory to its ASCII-slugged name`() {
        val show = PodcastConfig(name = "Bl\u00fair\u00edn\u00ed B\u00e9aloidis", url = "u")
        val others = listOf(PodcastConfig(name = "Other", url = "o"), show)
        assertEquals(show, PodcastPipeline.podcastForDir(others, "Blu\u0301iri\u0301ni\u0301-Be\u0301aloidis"))
        assertEquals(show, PodcastPipeline.podcastForDir(others, "bluirini-bealoidis"))
        assertEquals(null, PodcastPipeline.podcastForDir(others, "Missing"))
    }

    /** The load-bearing property: what the pipeline writes is what recovery can read back. */
    @Test
    fun `round trips a directory name the pipeline itself would create`() {
        val entry =
            makeEntry(
                title = "Ep 42: What's *really* going on?",
                publishedDate = Date(1_700_000_000_000L),
                guid = "https://example.com/guid/42",
            )
        val dirName = escapeFilename(PodcastPipeline.getEpisodeDirName(entry))

        val parsed = assertNotNull(EpisodeDirName.parse(dirName))
        assertEquals(PodcastPipeline.episodeStablePrefix(entry), parsed.stablePrefix)
        assertEquals(PodcastPipeline.episodeId(entry), parsed.episodeId)
    }
}
