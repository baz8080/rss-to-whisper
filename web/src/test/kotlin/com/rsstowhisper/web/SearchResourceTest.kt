package com.rsstowhisper.web

import com.rsstowhisper.web.db.EpisodeRepository
import com.rsstowhisper.web.models.Episode
import com.rsstowhisper.web.models.FilterOptions
import com.rsstowhisper.web.models.PodcastSummary
import com.rsstowhisper.web.models.SearchFilters
import com.rsstowhisper.web.models.SearchResult
import com.rsstowhisper.web.models.SortOrder
import com.rsstowhisper.web.models.TranscriptLine
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.IContext
import java.time.Instant

class SearchResourceTest {
    private val repository: EpisodeRepository = mockk()

    // TemplateEngine is a concrete class; MockK can subclass it.
    private val templateEngine: TemplateEngine = mockk()

    // Construct the resource manually — all fields are lateinit var so we can
    // set them directly without the CDI container.
    private val resource =
        SearchResource().also {
            it.repository = repository
            it.templateEngine = templateEngine
            it.audioBaseUrl = "http://audio.example.com/" // trailing slash — to verify trimming
        }

    // --- index ---

    @Test
    fun `index redirects to search`() {
        val response = resource.index()
        assertEquals(Response.Status.SEE_OTHER.statusCode, response.status)
        assertEquals("/search", response.location.toString())
    }

    // --- search ---

    @Test
    fun `search renders full template when no HTMX header`() {
        stubSearchDependencies()
        every { templateEngine.process("search", any<IContext>()) } returns "<html>full</html>"

        val result =
            search("")

        assertEquals("<html>full</html>", result)
    }

    @Test
    fun `search renders partial fragment for HTMX requests`() {
        stubSearchDependencies()
        every { templateEngine.process("search", setOf("app"), any<IContext>()) } returns "<div>partial</div>"

        val result =
            search("", htmxRequest = "true")

        assertEquals("<div>partial</div>", result)
    }

    @Test
    fun `search trims whitespace from query before passing to repository`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions()
        every { templateEngine.process("search", any<IContext>()) } returns ""

        search("  kotlin  ")

        assertEquals("kotlin", captured.captured.query)
    }

    @Test
    fun `search coerces page below 1 up to 1`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions()
        every { templateEngine.process("search", any<IContext>()) } returns ""

        search("", page = -5)

        assertEquals(1, captured.captured.page)
    }

    // --- episode ---

    @Test
    fun `episode returns 404 when episode not found`() {
        every { repository.getEpisodeById("missing") } returns null

        val response = resource.episode("missing", "")

        assertEquals(Response.Status.NOT_FOUND.statusCode, response.status)
    }

    @Test
    fun `episode returns 200 with rendered HTML when found`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode()
        every { templateEngine.process("episode", any<IContext>()) } returns "<html>episode</html>"

        val response = resource.episode("ep1", "")

        assertEquals(Response.Status.OK.statusCode, response.status)
        assertEquals("<html>episode</html>", response.entity)
    }

    @Test
    fun `episode linkifies plain-text summary`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(summary = "Visit https://example.com for more")

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        val linkifiedSummary = ctxSlot.captured.getVariable("linkifiedSummary") as String
        assertTrue(
            linkifiedSummary.contains("""href="https://example.com""""),
            "Expected linkified URL in: $linkifiedSummary",
        )
    }

    @Test
    fun `episode does not linkify summary that already contains HTML`() {
        val htmlSummary = "<p>Already <b>formatted</b></p>"
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(summary = htmlSummary)

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        val linkifiedSummary = ctxSlot.captured.getVariable("linkifiedSummary") as String
        assertEquals(htmlSummary, linkifiedSummary)
    }

    @Test
    fun `episode trims trailing slash from audio base URL`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode()

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        val audioBaseUrl = ctxSlot.captured.getVariable("audioBaseUrl") as String
        assertEquals("http://audio.example.com", audioBaseUrl)
    }

    @Test
    fun `episode provides empty transcript lines when transcript is null`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(transcript = null)

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        @Suppress("UNCHECKED_CAST")
        val transcriptLines = ctxSlot.captured.getVariable("transcriptLines") as List<*>
        assertTrue(transcriptLines.isEmpty())
    }

    @Test
    fun `episode parses transcript lines when transcript is present`() {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHello\n\n00:00:01.000 --> 00:00:02.000\nWorld\n"
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(transcript = vtt)

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        @Suppress("UNCHECKED_CAST")
        val transcriptLines = ctxSlot.captured.getVariable("transcriptLines") as List<*>
        assertEquals(2, transcriptLines.size)
    }

    @Test
    fun `episode highlights the lines a query matches and counts them`() {
        val vtt =
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:01.000\nHello there\n\n" +
                "00:00:01.000 --> 00:00:02.000\nNothing here\n\n" +
                "00:00:02.000 --> 00:00:03.000\nHello again & goodbye\n"
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(transcript = vtt)

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", " hello ")

        @Suppress("UNCHECKED_CAST")
        val lines = ctxSlot.captured.getVariable("transcriptLines") as List<TranscriptLine>
        assertEquals(listOf(true, false, true), lines.map { it.matched })
        assertEquals("<mark>Hello</mark> again &amp; goodbye", lines[2].highlightedHtml)
        assertEquals(2, ctxSlot.captured.getVariable("matchCount"))
        assertEquals("hello", ctxSlot.captured.getVariable("query"))
    }

    @Test
    fun `episode without a query highlights nothing`() {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHello\n"
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(transcript = vtt)

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        @Suppress("UNCHECKED_CAST")
        val lines = ctxSlot.captured.getVariable("transcriptLines") as List<TranscriptLine>
        assertTrue(lines.none { it.matched })
        assertEquals(0, ctxSlot.captured.getVariable("matchCount"))
        assertEquals("", ctxSlot.captured.getVariable("query"))
    }

    @Test
    fun `search passes the query on to episode links`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("climate change")

        assertEquals("?q=climate%20change", ctxSlot.captured.getVariable("episodeQuerySuffix"))
    }

    @Test
    fun `search without a query leaves episode links bare`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("")

        assertEquals("", ctxSlot.captured.getVariable("episodeQuerySuffix"))
    }

    // --- tag filtering (W1) ---

    @Test
    fun `search exposes a base url tag pills can append to`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("climate", page = 3)

        // Page reset to 1, and ending in & so the template can append tag=...
        assertEquals("/search?q=climate&", ctxSlot.captured.getVariable("tagBaseUrl"))
    }

    /** With no filters at all the base ends in ?, so appending still yields a valid URL. */
    @Test
    fun `the tag base url has no stray separator when nothing is filtered`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("")

        assertEquals("/search?", ctxSlot.captured.getVariable("tagBaseUrl"))
    }

    @Test
    fun `search builds a remove link for every active tag`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("", tags = listOf("space", "science"), page = 2)

        @Suppress("UNCHECKED_CAST")
        val removeUrls = ctxSlot.captured.getVariable("tagRemoveUrls") as Map<String, String>
        assertEquals(setOf("space", "science"), removeUrls.keys)
        // Each link drops its own tag and keeps the other, back at page 1.
        assertEquals("/search?tag=science", removeUrls["space"])
        assertEquals("/search?tag=space", removeUrls["science"])
    }

    @Test
    fun `tags reach the repository as filters`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions()
        every { templateEngine.process("search", any<IContext>()) } returns ""

        search("", tags = listOf("space"))

        assertEquals(setOf("space"), captured.captured.tags)
    }

    // --- helpers ---

    // --- sort and year (W2) ---

    @Test
    fun `sort and years reach the repository`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions()
        every { templateEngine.process("search", any<IContext>()) } returns ""

        search("space", years = listOf("2024", "2023"), sort = "oldest")

        assertEquals(setOf("2024", "2023"), captured.captured.years)
        assertEquals(SortOrder.OLDEST, captured.captured.sort)
    }

    /** The parameter is user-typed, so nonsense must not 500 the page. */
    @Test
    fun `an unknown sort falls back to relevance`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions()
        every { templateEngine.process("search", any<IContext>()) } returns ""

        search("space", sort = "sideways")

        assertEquals(SortOrder.RELEVANCE, captured.captured.sort)
    }

    @Test
    fun `search offers every sort option to the template`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("space")

        assertEquals(SortOrder.entries, ctxSlot.captured.getVariable("sortOptions"))
    }

    // --- podcasts overview (W4) ---

    @Test
    fun `podcasts page passes summaries and totals to the template`() {
        every { repository.getPodcastSummaries() } returns
            listOf(
                PodcastSummary("Podcast A", "https://img/a.png", 2, 2400, "2024-01-01", "2024-01-03"),
                PodcastSummary("Podcast B", null, 3, 3600, "2023-01-02", "2024-01-04"),
            )
        every { repository.indexBuiltAt() } returns Instant.parse("2024-06-01T10:30:00Z")
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("podcasts", capture(ctxSlot)) } returns "<html>podcasts</html>"

        val response = resource.podcasts()

        assertEquals(200, response.status)
        assertEquals(5, ctxSlot.captured.getVariable("totalEpisodes"))
        assertEquals("2", ctxSlot.captured.getVariable("totalHours"))
        assertNotNull(ctxSlot.captured.getVariable("indexBuiltAt"))
    }

    @Test
    fun `each podcast links to a search filtered to it`() {
        every { repository.getPodcastSummaries() } returns
            listOf(PodcastSummary("Podcast A & B", null, 1, 0, null, null))
        every { repository.indexBuiltAt() } returns null
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("podcasts", capture(ctxSlot)) } returns ""

        resource.podcasts()

        @Suppress("UNCHECKED_CAST")
        val urls = ctxSlot.captured.getVariable("searchUrls") as Map<String, String>
        assertEquals("/search?podcast=Podcast%20A%20%26%20B", urls["Podcast A & B"])
    }

    /** A database that has never been written has no build time; the page still renders. */
    @Test
    fun `a missing index build time is passed through as null`() {
        every { repository.getPodcastSummaries() } returns emptyList()
        every { repository.indexBuiltAt() } returns null
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("podcasts", capture(ctxSlot)) } returns ""

        resource.podcasts()

        assertNull(ctxSlot.captured.getVariable("indexBuiltAt"))
    }

    // --- JSON API (W7) ---

    @Test
    fun `api search returns the result object itself`() {
        val captured = slot<SearchFilters>()
        val expected = SearchResult(listOf(minimalEpisode()), 1, 1, 10)
        every { repository.search(capture(captured)) } returns expected

        val result =
            resource.apiSearch("kotlin", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "newest", 1, 10)

        assertSame(expected, result)
        assertEquals("kotlin", captured.captured.query)
        assertEquals(SortOrder.NEWEST, captured.captured.sort)
    }

    /** The transcript is not in the search payload, but an unbounded page still reads the corpus. */
    @Test
    fun `api search caps the page size`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()

        resource.apiSearch("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "relevance", 1, 10_000)

        assertEquals(SearchResource.MAX_API_PAGE_SIZE, captured.captured.pageSize)
    }

    @Test
    fun `api search coerces a nonsense page and page size up to one`() {
        val captured = slot<SearchFilters>()
        every { repository.search(capture(captured)) } returns emptySearchResult()

        resource.apiSearch("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "relevance", -3, 0)

        assertEquals(1, captured.captured.page)
        assertEquals(1, captured.captured.pageSize)
    }

    @Test
    fun `api episode returns the episode with its transcript`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode(transcript = "WEBVTT")

        val response = resource.apiEpisode("ep1")

        assertEquals(200, response.status)
        assertEquals("WEBVTT", (response.entity as Episode).transcript)
    }

    @Test
    fun `api episode returns 404 as json for an unknown id`() {
        every { repository.getEpisodeById("nope") } returns null

        val response = resource.apiEpisode("nope")

        assertEquals(404, response.status)
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.mediaType)
    }

    /**
     * Named defaults rather than a positional call in every test: `search()`
     * has grown a parameter with almost every filter added, and each one used
     * to mean editing all ten call sites here.
     */
    private fun search(
        query: String = "",
        durations: List<String> = emptyList(),
        podcasts: List<String> = emptyList(),
        collections: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        episodeTypes: List<String> = emptyList(),
        years: List<String> = emptyList(),
        sort: String = "relevance",
        page: Int = 1,
        htmxRequest: String? = null,
    ): String =
        resource.search(
            query,
            durations,
            podcasts,
            collections,
            tags,
            episodeTypes,
            years,
            sort,
            page,
            htmxRequest,
        )

    private fun stubSearchDependencies() {
        every { repository.search(any()) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions()
    }

    private fun emptySearchResult() = SearchResult(emptyList(), 0, 1, 10)

    private fun emptyFilterOptions() = FilterOptions(emptyList(), emptyList(), emptyList())

    private fun minimalEpisode(
        summary: String? = null,
        transcript: String? = null,
    ) = Episode(
        id = "ep1",
        podcastTitle = null,
        podcastImage = null,
        podcastCollections = null,
        episodeTitle = null,
        episodePublishedOn = null,
        episodeAudioLink = null,
        episodeWebLink = null,
        episodeImage = null,
        episodeSummary = summary,
        episodeNumber = null,
        episodeSeason = null,
        episodeType = null,
        episodeDuration = null,
        episodeRelativeAudioPath = null,
        allTags = null,
        transcript = transcript,
    )
}
