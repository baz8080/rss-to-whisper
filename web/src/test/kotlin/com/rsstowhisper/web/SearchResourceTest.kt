package com.rsstowhisper.web

import com.rsstowhisper.web.db.EpisodeRepository
import com.rsstowhisper.web.models.CorpusTotals
import com.rsstowhisper.web.models.Episode
import com.rsstowhisper.web.models.FilterOptions
import com.rsstowhisper.web.models.PodcastSummary
import com.rsstowhisper.web.models.SearchFilters
import com.rsstowhisper.web.models.SearchResult
import com.rsstowhisper.web.models.SortOrder
import com.rsstowhisper.web.models.TranscriptLine
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Request
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.IContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SearchResourceTest {
    private val repository: EpisodeRepository = mockk()

    private val hosted = ConcurrentHashMap<String, ByteArray>()
    private val requested = CopyOnWriteArrayList<String>()
    private val statuses = ConcurrentHashMap<String, Int>()
    private val etags = ConcurrentHashMap<String, String>()
    private val conditionals = CopyOnWriteArrayList<String?>()
    private var dataHost: HttpServer? = null

    // TemplateEngine is a concrete class; MockK can subclass it.
    private val templateEngine: TemplateEngine = mockk()

    // No conditional headers: every words test below wants the full body.
    private val request: Request =
        mockk {
            every { evaluatePreconditions(any<EntityTag>()) } returns null
        }

    // Construct the resource manually — all fields are lateinit var so we can
    // set them directly without the CDI container.
    private val resource =
        SearchResource().also {
            it.repository = repository
            it.templateEngine = templateEngine
            it.dataUrl = "http://audio.example.com/" // trailing slash — to verify trimming
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
    fun `episode trims trailing slash from the data URL`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode()

        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.episode("ep1", "")

        val dataUrl = ctxSlot.captured.getVariable("dataUrl") as String
        assertEquals("http://audio.example.com", dataUrl)
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

    // --- tag filtering ---

    @Test
    fun `search exposes a base url tag pills can append to`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search("climate", page = 3)

        // Page 1, though 3 was asked for.
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
        // Page 1 again, though 2 was asked for.
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

    /**
     * The select is what serialises `sort`, so hiding it while a date sort is
     * applied means the next checkbox click submits without it and the results
     * silently flip back to newest-first.
     */
    @Test
    fun `the sort control is shown whenever a sort is in effect, query or not`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search(query = "", sort = "oldest")
        assertEquals(true, ctxSlot.captured.getVariable("showSort"))

        search(query = "", sort = "relevance")
        assertEquals(false, ctxSlot.captured.getVariable("showSort"))

        search(query = "climate", sort = "relevance")
        assertEquals(true, ctxSlot.captured.getVariable("showSort"))
    }

    /** Without a query every row scores the same, so offering it is offering nothing. */
    @Test
    fun `relevance is not offered without a query`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search(query = "")
        assertEquals(listOf(SortOrder.NEWEST, SortOrder.OLDEST), ctxSlot.captured.getVariable("sortOptions"))

        search(query = "climate")
        assertEquals(SortOrder.entries, ctxSlot.captured.getVariable("sortOptions"))
    }

    /**
     * A query can narrow the corpus to one year while a different year is
     * filtered on, and the options come back narrowed by that query -- leaving
     * no checkbox to untick the filter that is emptying the results.
     */
    @Test
    fun `an active year stays in the options even when the query excludes it`() {
        stubSearchDependencies(years = listOf("2024"))
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search(query = "climate", years = listOf("2019"))

        assertEquals(listOf("2024", "2019"), ctxSlot.captured.getVariable("yearOptions"))
    }

    // --- helpers ---

    // --- sort and year ---

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

    // --- podcasts overview ---

    @Test
    fun `podcasts page passes summaries and totals to the template`() {
        every { repository.getPodcastSummaries() } returns
            listOf(
                PodcastSummary("Podcast A", "https://img/a.png", 2, 2400, "2024-01-01", "2024-01-03"),
                PodcastSummary("Podcast B", null, 3, 3600, "2023-01-02", "2024-01-04"),
            )
        every { repository.getCorpusTotals() } returns CorpusTotals(5, 7200)
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
        every { repository.getCorpusTotals() } returns CorpusTotals(1, 0)
        every { repository.indexBuiltAt() } returns null
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("podcasts", capture(ctxSlot)) } returns ""

        resource.podcasts()

        @Suppress("UNCHECKED_CAST")
        val urls = ctxSlot.captured.getVariable("searchUrls") as Map<String, String>
        assertEquals("/search?podcast=Podcast%20A%20%26%20B", urls["Podcast A & B"])
    }

    /**
     * An episode whose feed gave no podcast title has no card to appear on, but
     * it is still in the corpus: summing the cards would under-report the
     * header against what /search returns.
     */
    @Test
    fun `the corpus header counts episodes the cards leave out`() {
        every { repository.getPodcastSummaries() } returns
            listOf(PodcastSummary("Podcast A", null, 2, 2400, "2024-01-01", "2024-01-03"))
        every { repository.getCorpusTotals() } returns CorpusTotals(3, 7200)
        every { repository.indexBuiltAt() } returns null
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("podcasts", capture(ctxSlot)) } returns ""

        resource.podcasts()

        assertEquals(3, ctxSlot.captured.getVariable("totalEpisodes"))
        assertEquals("2", ctxSlot.captured.getVariable("totalHours"))
    }

    /** Same hole the tags and years already closed: the podcasts page lands straight in it. */
    @Test
    fun `an active podcast stays in the options even when the query excludes it`() {
        stubSearchDependencies(podcasts = listOf("Podcast B"))
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search(query = "climate", podcasts = listOf("Podcast A"))

        assertEquals(listOf("Podcast A", "Podcast B"), ctxSlot.captured.getVariable("podcastOptions"))
    }

    /**
     * The last two facets with the old gating. Collections vanish when the query
     * matches only untagged episodes; episode types when it matches only one
     * kind -- either way the active filter goes with them.
     */
    @Test
    fun `an active collection and episode type stay in their options`() {
        stubSearchDependencies()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("search", capture(ctxSlot)) } returns ""

        search(query = "climate", collections = listOf("science"), episodeTypes = listOf("trailer"))

        assertEquals(listOf("science"), ctxSlot.captured.getVariable("collectionOptions"))
        assertEquals(listOf("trailer"), ctxSlot.captured.getVariable("episodeTypeOptions"))
    }

    /** A database that has never been written has no build time; the page still renders. */
    @Test
    fun `a missing index build time is passed through as null`() {
        every { repository.getPodcastSummaries() } returns emptyList()
        every { repository.getCorpusTotals() } returns CorpusTotals(0, 0)
        every { repository.indexBuiltAt() } returns null
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("podcasts", capture(ctxSlot)) } returns ""

        resource.podcasts()

        assertNull(ctxSlot.captured.getVariable("indexBuiltAt"))
    }

    // --- JSON API ---

    @Test
    fun `api search passes the result through, with the filters it was given`() {
        val captured = slot<SearchFilters>()
        val expected = SearchResult(listOf(minimalEpisode()), 1, 1, 10)
        every { repository.search(capture(captured)) } returns expected

        val result =
            resource.apiSearch("kotlin", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "newest", 1, 10)

        assertEquals(expected.totalCount, result.totalCount)
        assertEquals(expected.episodes.map { it.id }, result.episodes.map { it.id })
        assertEquals("kotlin", captured.captured.query)
        assertEquals(SortOrder.NEWEST, captured.captured.sort)
    }

    /**
     * Every page that renders episode_summary sanitises it first. An API
     * consumer that drops it into the DOM would be running feed-supplied
     * script, and nothing in the payload warns them.
     */
    @Test
    fun `the api sanitises the feed-supplied summary`() {
        val dangerous = minimalEpisode(summary = "<p>Fine</p><script>alert(1)</script>")
        every { repository.search(any()) } returns SearchResult(listOf(dangerous), 1, 1, 10)
        every { repository.getEpisodeById("abc") } returns dangerous

        val searched =
            resource.apiSearch("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "relevance", 1, 10)
                .episodes
                .single()
        val fetched = resource.apiEpisode("abc").entity as Episode

        for (summary in listOf(searched.episodeSummary, fetched.episodeSummary)) {
            assertFalse(summary!!.contains("<script"), summary)
            assertTrue(summary.contains("Fine"), summary)
        }
    }

    /**
     * Plenty of feeds write the summary as prose, and an HTML sanitiser turns
     * its ampersands and quotes into entities. A summary is treated as markup
     * only if it contains a `<`, which is the same rule the episode page uses
     * -- so prose that happens to contain one, an address in angle brackets
     * say, is sanitised and loses it. Feeds do that rarely enough that one rule
     * shared with the page beats two that disagree.
     */
    @Test
    fun `the api leaves a plain-text summary exactly as it is`() {
        val prose = "Ben & Jerry's \"best\" episode: see https://x.test?a=1&b=2"
        every { repository.getEpisodeById("abc") } returns minimalEpisode(summary = prose)

        val fetched = resource.apiEpisode("abc").entity as Episode

        assertEquals(prose, fetched.episodeSummary)
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

    // --- word timings (W6) ---

    @Test
    fun `words route is 404 when the data URL is not absolute`() {
        resource.dataUrl = "/audio"

        assertEquals(404, resource.episodeWords("ep1", null, request).status)
        verify(exactly = 0) { repository.getEpisodeById(any()) }
    }

    @Test
    fun `words route is 404 when the data host has no sidecar`() {
        resource.dataUrl = hostData()
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        assertEquals(404, resource.episodeWords("ep1", null, request).status)
    }

    @Test
    fun `words route serves the sidecar as gzip from beside the audio`() {
        val payload = byteArrayOf(1, 2, 3)
        resource.dataUrl = hostData("/data/Show/ep/words.jsonl.gz" to payload) + "/data/"
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        val response = resource.episodeWords("ep1", null, request)

        assertEquals(200, response.status)
        assertEquals("gzip", response.getHeaderString("Content-Encoding"))
        assertArrayEquals(payload, response.entity as ByteArray)
    }

    @Test
    fun `words route percent-encodes the episode directory`() {
        resource.dataUrl = hostData("/Show%20Name/ep%20%231/words.jsonl.gz" to byteArrayOf(7))
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show Name/ep #1/audio.mp3")

        assertEquals(200, resource.episodeWords("ep1", null, request).status)
        assertEquals(listOf("/Show%20Name/ep%20%231/words.jsonl.gz"), requested)
    }

    @Test
    fun `words route refuses a path that could leave the data URL`() {
        resource.dataUrl = hostData("/words.jsonl.gz" to byteArrayOf(9))

        for (relative in listOf("../outside/audio.mp3", "Show/../../audio.mp3", "", ".", "./", "/Show/audio.mp3", "Show//audio.mp3")) {
            every { repository.getEpisodeById("ep1") } returns minimalEpisode(relativeAudioPath = relative)
            assertEquals(404, resource.episodeWords("ep1", null, request).status, "relative=<$relative>")
        }
        assertEquals(emptyList<String>(), requested)
    }

    @Test
    fun `words route is 502 when the data host is unreachable`() {
        resource.dataUrl = hostData()
        stopHost()
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        assertEquals(502, resource.episodeWords("ep1", null, request).status)
    }

    @Test
    fun `words route is 502 when the data host answers with an error`() {
        resource.dataUrl = hostData()
        statuses["/Show/ep/words.jsonl.gz"] = 500
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        assertEquals(502, resource.episodeWords("ep1", null, request).status)
    }

    @Test
    fun `words are off for a data URL the server cannot fetch from`() {
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        for (url in listOf(
            "http://my_nas:9280",
            "http:///x",
            "http:/nas",
            "http://nas:9280/d?token=abc",
            "http://nas:9280/d#frag",
            "ftp://nas",
        )) {
            resource.dataUrl = url
            assertEquals(404, resource.episodeWords("ep1", null, request).status, url)
            resource.episode("ep1", "")
            assertNull(ctxSlot.captured.getVariable("wordsUrl"), url)
        }
    }

    @Test
    fun `words route revalidates rather than caching`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1, 2, 3))
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        val response = resource.episodeWords("ep1", null, request)

        assertEquals("no-cache, no-transform", response.getHeaderString("Cache-Control"))
        assertNotNull(response.entityTag)
    }

    @Test
    fun `the entity tag follows the content`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1, 2, 3))
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        val first = resource.episodeWords("ep1", null, request).entityTag
        hosted["/Show/ep/words.jsonl.gz"] = byteArrayOf(4, 5, 6)
        val second = resource.episodeWords("ep1", null, request).entityTag

        assertNotEquals(first, second)
    }

    @Test
    fun `words route answers a matching entity tag with 304`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1, 2, 3))
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")
        val unchanged: Request =
            mockk {
                every { evaluatePreconditions(any<EntityTag>()) } returns
                    Response.notModified()
            }

        assertEquals(304, resource.episodeWords("ep1", null, unchanged).status)
    }

    @Test
    fun `words route uses the data host's ETag as its own`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1, 2, 3))
        etags["/Show/ep/words.jsonl.gz"] = "\"v1\""
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        val response = resource.episodeWords("ep1", null, request)

        assertEquals(200, response.status)
        assertEquals(EntityTag("v1"), response.entityTag)
    }

    @Test
    fun `words route forwards If-None-Match and turns an upstream 304 into its own`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1, 2, 3))
        etags["/Show/ep/words.jsonl.gz"] = "\"v1\""
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        val response = resource.episodeWords("ep1", "\"v1\"", request)

        assertEquals(listOf<String?>("\"v1\""), conditionals)
        assertEquals(304, response.status)
        assertNull(response.entity)
        assertEquals(EntityTag("v1"), response.entityTag)
        assertEquals("no-cache, no-transform", response.getHeaderString("Cache-Control"))
    }

    @Test
    fun `words route sends the new body when the browser's tag is stale`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(4, 5, 6))
        etags["/Show/ep/words.jsonl.gz"] = "\"v2\""
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        val response = resource.episodeWords("ep1", "\"v1\"", request)

        assertEquals(200, response.status)
        assertEquals(EntityTag("v2"), response.entityTag)
        assertArrayEquals(byteArrayOf(4, 5, 6), response.entity as ByteArray)
    }

    @Test
    fun `words route sends no conditional header upstream when the browser sent none`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1))
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        resource.episodeWords("ep1", null, request)

        assertEquals(listOf<String?>(null), conditionals)
    }

    @Test
    fun `without an upstream ETag the tag is computed and the browser's tag is checked here`() {
        resource.dataUrl = hostData("/Show/ep/words.jsonl.gz" to byteArrayOf(1, 2, 3))
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")
        val tagSlot = slot<EntityTag>()
        val unchanged: Request =
            mockk {
                every { evaluatePreconditions(capture(tagSlot)) } returns Response.notModified()
            }

        val response = resource.episodeWords("ep1", "\"computed\"", unchanged)

        assertEquals(304, response.status)
        assertEquals(listOf<String?>("\"computed\""), conditionals)
        assertEquals(32, tagSlot.captured.value.length)
        assertEquals(tagSlot.captured, response.entityTag)
    }

    @Test
    fun `words route is 502 when the data host answers 304 to an unconditional request`() {
        resource.dataUrl = hostData()
        statuses["/Show/ep/words.jsonl.gz"] = 304
        every { repository.getEpisodeById("ep1") } returns
            minimalEpisode(relativeAudioPath = "Show/ep/audio.mp3")

        assertEquals(502, resource.episodeWords("ep1", null, request).status)
    }

    @Test
    fun `the episode page offers the words url only when the data URL is absolute`() {
        every { repository.getEpisodeById("ep1") } returns minimalEpisode()
        val ctxSlot = slot<IContext>()
        every { templateEngine.process("episode", capture(ctxSlot)) } returns ""

        resource.dataUrl = "/audio"
        resource.episode("ep1", "")
        assertNull(ctxSlot.captured.getVariable("wordsUrl"))

        resource.dataUrl = "http://nas:9280"
        resource.episode("ep1", "")
        assertEquals("/episode/ep1/words", ctxSlot.captured.getVariable("wordsUrl"))
    }

    private fun hostData(vararg files: Pair<String, ByteArray>): String {
        hosted.putAll(files)
        val host =
            HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).also { server ->
                server.createContext("/") { exchange ->
                    val path = exchange.requestURI.rawPath
                    requested += path
                    val body = hosted[path]
                    val status = statuses[path]
                    val etag = etags[path]
                    val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                    conditionals += ifNoneMatch
                    if (status != null) {
                        exchange.sendResponseHeaders(status, -1)
                    } else if (body == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else if (etag != null && ifNoneMatch == etag) {
                        exchange.responseHeaders.add("ETag", etag)
                        exchange.sendResponseHeaders(304, -1)
                    } else {
                        etag?.let { exchange.responseHeaders.add("ETag", it) }
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    exchange.close()
                }
                server.start()
            }
        dataHost = host
        return "http://127.0.0.1:${host.address.port}"
    }

    private fun stopHost() = dataHost?.stop(0)

    @AfterEach
    fun tearDownHost() {
        stopHost()
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

    private fun stubSearchDependencies(
        years: List<String> = emptyList(),
        podcasts: List<String> = emptyList(),
    ) {
        every { repository.search(any()) } returns emptySearchResult()
        every { repository.getFilterOptions(any()) } returns emptyFilterOptions(years, podcasts)
    }

    private fun emptySearchResult() = SearchResult(emptyList(), 0, 1, 10)

    private fun emptyFilterOptions(
        years: List<String> = emptyList(),
        podcasts: List<String> = emptyList(),
    ) = FilterOptions(podcasts, emptyList(), emptyList(), years)

    private fun minimalEpisode(
        summary: String? = null,
        transcript: String? = null,
        relativeAudioPath: String? = null,
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
        episodeRelativeAudioPath = relativeAudioPath,
        allTags = null,
        transcript = transcript,
    )
}
