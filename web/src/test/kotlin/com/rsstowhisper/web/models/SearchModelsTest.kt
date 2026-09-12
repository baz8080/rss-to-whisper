package com.rsstowhisper.web.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SearchModelsTest {
    @Nested
    inner class FormatDuration {
        @Test
        fun `zero seconds`() = assertEquals("0m", formatDuration(0))

        @Test
        fun `less than one minute`() = assertEquals("0m", formatDuration(59))

        @Test
        fun `exactly one minute`() = assertEquals("1m", formatDuration(60))

        @Test
        fun `fifty-nine minutes`() = assertEquals("59m", formatDuration(3599))

        @Test
        fun `exactly one hour`() = assertEquals("1h 0m", formatDuration(3600))

        @Test
        fun `one hour and one minute`() = assertEquals("1h 1m", formatDuration(3661))

        @Test
        fun `two hours three minutes`() = assertEquals("2h 3m", formatDuration(7384))
    }

    @Nested
    inner class FormatTimestamp {
        @Test
        fun `zero milliseconds`() = assertEquals("0:00", formatTimestamp(0))

        @Test
        fun `one second`() = assertEquals("0:01", formatTimestamp(1_000))

        @Test
        fun `one minute`() = assertEquals("1:00", formatTimestamp(60_000))

        @Test
        fun `one minute thirty seconds`() = assertEquals("1:30", formatTimestamp(90_000))

        @Test
        fun `fifty-nine minutes fifty-nine seconds`() = assertEquals("59:59", formatTimestamp(3_599_000))

        @Test
        fun `exactly one hour`() = assertEquals("1:00:00", formatTimestamp(3_600_000))

        @Test
        fun `one hour twenty-three minutes forty-five seconds`() = assertEquals("1:23:45", formatTimestamp(5_025_000))
    }

    @Nested
    inner class ParseTranscript {
        @Test
        fun `empty string returns empty list`() = assertTrue(parseTranscript("").isEmpty())

        @Test
        fun `blank string returns empty list`() = assertTrue(parseTranscript("   \n  ").isEmpty())

        @Test
        fun `single cue`() {
            val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello world\n"
            val lines = parseTranscript(vtt)
            assertEquals(1, lines.size)
            assertEquals(TranscriptLine(1_000L, "Hello world"), lines[0])
        }

        @Test
        fun `multiple cues`() {
            val vtt =
                "WEBVTT\n\n" +
                    "00:00:00.000 --> 00:00:01.000\nFirst\n\n" +
                    "00:00:01.000 --> 00:00:02.000\nSecond\n\n" +
                    "00:00:02.000 --> 00:00:03.000\nThird\n"
            val lines = parseTranscript(vtt)
            assertEquals(3, lines.size)
            assertEquals("First", lines[0].text)
            assertEquals("Second", lines[1].text)
            assertEquals("Third", lines[2].text)
        }

        @Test
        fun `timestamp converts hours minutes seconds and millis to milliseconds`() {
            val vtt = "WEBVTT\n\n01:02:03.456 --> 01:02:04.000\nSome text\n"
            val lines = parseTranscript(vtt)
            assertEquals(1, lines.size)
            // 1h=3600000 + 2m=120000 + 3s=3000 + 456ms = 3723456
            assertEquals(3_723_456L, lines[0].millis)
        }

        @Test
        fun `WEBVTT header line is not included as text`() {
            val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHello\n"
            val lines = parseTranscript(vtt)
            assertEquals(1, lines.size)
            assertEquals("Hello", lines[0].text)
        }

        @Test
        fun `multi-line cue text is joined with a space`() {
            val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nLine one\nLine two\n"
            val lines = parseTranscript(vtt)
            assertEquals(1, lines.size)
            assertEquals("Line one Line two", lines[0].text)
        }

        @Test
        fun `leading and trailing whitespace in cue text is trimmed`() {
            val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n  Hello  \n"
            val lines = parseTranscript(vtt)
            assertEquals("Hello", lines[0].text)
        }

        @Test
        fun `cues without text are skipped`() {
            val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
            val lines = parseTranscript(vtt)
            assertEquals(1, lines.size)
            assertEquals("Hello", lines[0].text)
        }
    }

    @Nested
    inner class Linkify {
        @Test
        fun `plain text is HTML escaped`() = assertEquals("Hello &amp; World", linkify("Hello & World"))

        @Test
        fun `angle brackets are escaped`() = assertEquals("a &lt;b&gt; c", linkify("a <b> c"))

        @Test
        fun `text with no URLs is returned escaped`() = assertEquals("No links here", linkify("No links here"))

        @Test
        fun `URL is wrapped in anchor tag`() {
            val result = linkify("Visit https://example.com today")
            assertEquals(
                """Visit <a href="https://example.com" target="_blank">https://example.com</a> today""",
                result,
            )
        }

        @Test
        fun `multiple URLs are all linkified`() {
            val result = linkify("https://a.com and https://b.com")
            assertTrue(result.contains("""href="https://a.com""""))
            assertTrue(result.contains("""href="https://b.com""""))
        }

        @Test
        fun `surrounding text with special chars is escaped`() {
            val result = linkify("Tom & Jerry at https://example.com")
            assertTrue(result.startsWith("Tom &amp; Jerry at "))
        }
    }

    @Nested
    inner class HasActiveFilters {
        @Test
        fun `empty filters returns false`() = assertFalse(SearchFilters().hasActiveFilters())

        @Test
        fun `blank query returns false`() = assertFalse(SearchFilters(query = "   ").hasActiveFilters())

        @Test
        fun `non-blank query returns true`() = assertTrue(SearchFilters(query = "test").hasActiveFilters())

        @Test
        fun `non-empty durations returns true`() = assertTrue(SearchFilters(durations = setOf("SHORT")).hasActiveFilters())

        @Test
        fun `non-empty podcasts returns true`() = assertTrue(SearchFilters(podcasts = setOf("My Podcast")).hasActiveFilters())

        @Test
        fun `non-empty collections returns true`() = assertTrue(SearchFilters(collections = setOf("Tech")).hasActiveFilters())

        @Test
        fun `non-empty tags returns true`() = assertTrue(SearchFilters(tags = setOf("kotlin")).hasActiveFilters())

        @Test
        fun `non-empty episodeTypes returns true`() = assertTrue(SearchFilters(episodeTypes = setOf("full")).hasActiveFilters())
    }

    @Nested
    inner class HtmlSafety {
        @Test
        fun `sanitizeHtml strips script tags and event handlers`() {
            val dirty = "<p>Hi</p><script>alert(1)</script><img src=x onerror=alert(2)>"
            val clean = sanitizeHtml(dirty)
            assertFalse(clean.contains("script", ignoreCase = true))
            assertFalse(clean.contains("onerror", ignoreCase = true))
            assertTrue(clean.contains("Hi"))
        }

        @Test
        fun `sanitizeHtml drops javascript URLs but keeps safe links`() {
            assertFalse(sanitizeHtml("""<a href="javascript:alert(1)">x</a>""").contains("javascript"))
            assertTrue(sanitizeHtml("""<a href="https://ok.example/">x</a>""").contains("https://ok.example/"))
        }

        @Test
        fun `sanitizeHtml keeps ordinary summary formatting`() {
            val clean = sanitizeHtml("<p>Episode <b>notes</b> and <em>more</em></p>")
            assertTrue(clean.contains("<b>notes</b>"))
            assertTrue(clean.contains("<em>more</em>"))
        }

        @Test
        fun `sanitizeHtml keeps http images but not their handlers`() {
            val clean = sanitizeHtml("""<p><img src="https://cdn.example/art.jpg" onerror="alert(1)"/>Notes</p>""")
            assertTrue(clean.contains("https://cdn.example/art.jpg"))
            assertFalse(clean.contains("onerror", ignoreCase = true))
        }

        @Test
        fun `renderSnippet escapes markup and converts sentinels to mark`() {
            val raw = "before ${SNIPPET_MARK_START}hit${SNIPPET_MARK_END} <script>alert(1)</script>"
            val html = renderSnippet(raw)
            assertTrue(html.contains("<mark>hit</mark>"))
            assertFalse(html.contains("<script>"))
            assertTrue(html.contains("&lt;script&gt;"))
        }

        @Test
        fun `renderSnippet does not honour a literal mark tag from feed text`() {
            // A feed that puts "<mark>" in its title must not get real markup out.
            val html = renderSnippet("a <mark>fake</mark> highlight")
            assertFalse(html.contains("<mark>"))
            assertTrue(html.contains("&lt;mark&gt;"))
        }
    }

    @Nested
    inner class BuildSearchUrl {
        @Test
        fun `empty filters produces base URL with no params`() = assertEquals("/search?", buildSearchUrl(SearchFilters()))

        @Test
        fun `query is included`() = assertTrue(buildSearchUrl(SearchFilters(query = "kotlin")).contains("q=kotlin"))

        @Test
        fun `page 1 is omitted`() = assertFalse(buildSearchUrl(SearchFilters(query = "test", page = 1)).contains("page="))

        @Test
        fun `page greater than 1 is included`() = assertTrue(buildSearchUrl(SearchFilters(page = 3)).contains("page=3"))

        @Test
        fun `multiple podcasts are all included`() {
            val url = buildSearchUrl(SearchFilters(podcasts = setOf("PodA", "PodB")))
            assertTrue(url.contains("podcast=PodA"))
            assertTrue(url.contains("podcast=PodB"))
        }

        @Test
        fun `multiple filter types are combined`() {
            val url = buildSearchUrl(SearchFilters(query = "ai", durations = setOf("SHORT"), page = 2))
            assertTrue(url.contains("q=ai"))
            assertTrue(url.contains("duration=SHORT"))
            assertTrue(url.contains("page=2"))
        }

        @Test
        fun `values are URL-encoded`() {
            val url = buildSearchUrl(SearchFilters(query = "c++ & more", podcasts = setOf("My Podcast")))
            assertTrue(url.contains("q=c%2B%2B%20%26%20more"))
            assertTrue(url.contains("podcast=My%20Podcast"))
            assertFalse(url.contains("My Podcast"))
            // Spaces must not round-trip as '+', whose meaning in a query
            // string is server-dependent.
            assertFalse(url.contains("+"))
        }

        @Test
        fun `ampersand in a filter value cannot split parameters`() {
            val url = buildSearchUrl(SearchFilters(tags = setOf("rock&roll")))
            assertTrue(url.contains("tag=rock%26roll"))
        }
    }

    @Nested
    inner class SearchResultPagination {
        @Test
        fun `zero results gives one total page`() {
            val result = SearchResult(emptyList(), totalCount = 0, page = 1, pageSize = 10)
            assertEquals(1, result.totalPages)
            assertFalse(result.hasPrevious)
            assertFalse(result.hasNext)
        }

        @Test
        fun `results fitting exactly one page`() {
            val result = SearchResult(emptyList(), totalCount = 10, page = 1, pageSize = 10)
            assertEquals(1, result.totalPages)
            assertFalse(result.hasPrevious)
            assertFalse(result.hasNext)
        }

        @Test
        fun `results spanning two pages - first page`() {
            val result = SearchResult(emptyList(), totalCount = 11, page = 1, pageSize = 10)
            assertEquals(2, result.totalPages)
            assertFalse(result.hasPrevious)
            assertTrue(result.hasNext)
        }

        @Test
        fun `results spanning two pages - second page`() {
            val result = SearchResult(emptyList(), totalCount = 11, page = 2, pageSize = 10)
            assertEquals(2, result.totalPages)
            assertTrue(result.hasPrevious)
            assertFalse(result.hasNext)
        }

        @Test
        fun `partial last page is counted as a full page`() {
            val result = SearchResult(emptyList(), totalCount = 21, page = 1, pageSize = 10)
            assertEquals(3, result.totalPages)
        }
    }

    @Nested
    inner class EpisodeComputedProperties {
        private fun episode(
            duration: Int? = null,
            snippet: String? = null,
            audioPath: String? = null,
            tags: String? = null,
        ) = Episode(
            id = "1",
            podcastTitle = null,
            podcastImage = null,
            podcastCollections = null,
            episodeTitle = null,
            episodePublishedOn = null,
            episodeAudioLink = null,
            episodeWebLink = null,
            episodeImage = null,
            episodeSummary = null,
            episodeNumber = null,
            episodeSeason = null,
            episodeType = null,
            episodeDuration = duration,
            episodeRelativeAudioPath = audioPath,
            allTags = tags,
            snippet = snippet,
        )

        @Test
        fun `formattedDuration is null when episodeDuration is null`() = assertNull(episode().formattedDuration)

        @Test
        fun `formattedDuration formats duration correctly`() = assertEquals("1h 0m", episode(duration = 3600).formattedDuration)

        @Test
        fun `snippetHtml is null when there is no snippet`() = assertNull(episode().snippetHtml)

        @Test
        fun `snippetHtml renders sentinels as mark tags`() =
            assertEquals(
                "a <mark>b</mark> c",
                episode(snippet = "a ${SNIPPET_MARK_START}b${SNIPPET_MARK_END} c").snippetHtml,
            )

        @Test
        fun `tagList is empty when allTags is null`() = assertTrue(episode().tagList.isEmpty())

        @Test
        fun `tagList splits on comma`() = assertEquals(listOf("a", "b"), episode(tags = "a,b").tagList)

        @Test
        fun `tagList trims whitespace from each tag`() = assertEquals(listOf("a", "b"), episode(tags = " a , b ").tagList)

        @Test
        fun `tagList filters out blank entries`() = assertTrue(episode(tags = ",").tagList.isEmpty())

        @Test
        fun `audioPath is null when episodeRelativeAudioPath is null`() = assertNull(episode().audioPath)

        @Test
        fun `audioPath returns episodeRelativeAudioPath directly`() =
            assertEquals("audio/ep.mp3", episode(audioPath = "audio/ep.mp3").audioPath)
    }

    @Nested
    inner class SearchTerms {
        private fun term(
            vararg words: String,
            prefix: Boolean = false,
        ) = SearchTerm(words.toList(), prefix)

        @Test
        fun `blank query has no terms`() = assertTrue(searchTerms("   ").isEmpty())

        @Test
        fun `bare words are separate terms`() = assertEquals(listOf(term("climate"), term("change")), searchTerms("climate change"))

        @Test
        fun `words are lower-cased`() = assertEquals(listOf(term("kotlin")), searchTerms("Kotlin"))

        @Test
        fun `quoted phrase is one term`() = assertEquals(listOf(term("climate", "change")), searchTerms("\"climate change\""))

        @Test
        fun `phrase and bare words mix`() =
            assertEquals(listOf(term("hot"), term("climate", "change"), term("now")), searchTerms("hot \"climate change\" now"))

        @Test
        fun `unterminated phrase runs to the end`() = assertEquals(listOf(term("a", "b")), searchTerms("\"a b"))

        @Test
        fun `doubled quote inside a phrase is literal and separates nothing`() =
            assertEquals(listOf(term("say", "hi")), searchTerms("\"say \"\"hi\"\"\""))

        @Test
        fun `upper-case operators are dropped`() =
            assertEquals(listOf(term("cats"), term("dogs")), searchTerms("cats AND dogs OR NOT NEAR"))

        @Test
        fun `lower-case operator words are ordinary terms`() =
            assertEquals(listOf(term("cats"), term("and"), term("dogs")), searchTerms("cats and dogs"))

        @Test
        fun `trailing star marks a prefix term`() = assertEquals(listOf(term("quant", prefix = true)), searchTerms("quant*"))

        @Test
        fun `star after a phrase marks the phrase as prefix`() =
            assertEquals(listOf(term("quantum", "comp", prefix = true)), searchTerms("\"quantum comp\"*"))

        @Test
        fun `column filter and initial-token caret are stripped`() =
            assertEquals(listOf(term("kotlin"), term("first")), searchTerms("episode_title:kotlin ^first"))

        @Test
        fun `parentheses are separators`() = assertEquals(listOf(term("a"), term("b")), searchTerms("(a) (b)"))

        @Test
        fun `plus joins words into a phrase`() {
            assertEquals(listOf(term("climate", "change")), searchTerms("climate + change"))
            assertEquals(listOf(term("climate", "change"), term("now")), searchTerms("climate+change now"))
        }

        @Test
        fun `NEAR distance is not a term`() {
            assertEquals(listOf(term("climate"), term("change")), searchTerms("NEAR(climate change, 10)"))
            assertEquals(listOf(term("climate", "change"), term("now")), searchTerms("NEAR(\"climate change\" now, 5)"))
        }

        @Test
        fun `punctuation splits a word the way the tokenizer does`() = assertEquals(listOf(term("don", "t")), searchTerms("don't"))

        @Test
        fun `symbol-only tokens produce no term`() = assertEquals(listOf(term("c")), searchTerms("c++ --"))

        @Test
        fun `duplicate terms collapse`() = assertEquals(listOf(term("a")), searchTerms("a a \"a\""))
    }

    @Nested
    inner class HighlightMatches {
        private val climate = listOf(SearchTerm(listOf("climate")))

        @Test
        fun `no terms gives null`() = assertNull(highlightMatches("anything", emptyList()))

        @Test
        fun `no occurrence gives null`() = assertNull(highlightMatches("nothing to see", climate))

        @Test
        fun `single word is marked case-insensitively`() =
            assertEquals("The <mark>Climate</mark> is changing", highlightMatches("The Climate is changing", climate))

        @Test
        fun `every occurrence is marked`() =
            assertEquals("<mark>climate</mark>, <mark>climate</mark>!", highlightMatches("climate, climate!", climate))

        @Test
        fun `a word is not matched inside a longer word`() = assertNull(highlightMatches("climates", climate))

        @Test
        fun `prefix term matches a longer word`() =
            assertEquals("<mark>climates</mark> vary", highlightMatches("climates vary", listOf(SearchTerm(listOf("clim"), prefix = true))))

        @Test
        fun `phrase must be consecutive`() {
            val phrase = listOf(SearchTerm(listOf("climate", "change")))
            assertEquals("on <mark>climate change</mark> now", highlightMatches("on climate change now", phrase))
            assertNull(highlightMatches("climate and change", phrase))
        }

        @Test
        fun `phrase spans punctuation between its words`() =
            assertEquals(
                "<mark>don't</mark> stop",
                highlightMatches("don't stop", listOf(SearchTerm(listOf("don", "t")))),
            )

        @Test
        fun `overlapping hits merge into one mark`() {
            val terms = listOf(SearchTerm(listOf("climate", "change")), SearchTerm(listOf("change")))
            assertEquals("<mark>climate change</mark>", highlightMatches("climate change", terms))
        }

        @Test
        fun `diacritics are ignored like the index does`() =
            assertEquals("<mark>café</mark> au lait", highlightMatches("café au lait", listOf(SearchTerm(listOf("cafe")))))

        @Test
        fun `text is HTML-escaped around and inside the marks`() =
            assertEquals(
                "a &lt;b&gt; &amp; <mark>climate</mark>",
                highlightMatches("a <b> & climate", climate),
            )

        @Test
        fun `a literal mark tag in the transcript is not honoured`() =
            assertEquals("&lt;mark&gt;x&lt;/mark&gt; <mark>climate</mark>", highlightMatches("<mark>x</mark> climate", climate))
    }

    @Nested
    inner class EpisodeQuerySuffix {
        @Test
        fun `blank query gives nothing`() = assertEquals("", episodeQuerySuffix("  "))

        @Test
        fun `query is trimmed and encoded`() = assertEquals("?q=c%2B%2B%20%26%20more", episodeQuerySuffix(" c++ & more "))
    }

    @Nested
    inner class TranscriptLineProperties {
        @Test
        fun `matched is false without highlighted html`() = assertFalse(TranscriptLine(0L, "text").matched)

        @Test
        fun `matched is true with highlighted html`() = assertTrue(TranscriptLine(0L, "text", "<mark>text</mark>").matched)

        @Test
        fun `seconds converts milliseconds correctly`() {
            assertEquals(1.5, TranscriptLine(1500L, "text").seconds, 0.001)
        }

        @Test
        fun `display formats timestamp without hours`() = assertEquals("1:00", TranscriptLine(60_000L, "").display)

        @Test
        fun `display formats timestamp with hours`() = assertEquals("1:00:00", TranscriptLine(3_600_000L, "").display)
    }

    // --- sort and year in URLs (W2) ---

    @Test
    fun `buildSearchUrl carries years and a non-default sort`() {
        val url =
            buildSearchUrl(
                SearchFilters(query = "space", years = setOf("2024"), sort = SortOrder.OLDEST),
            )
        assertEquals("/search?q=space&year=2024&sort=oldest", url)
    }

    /** The default is left out so the everyday URL stays short. */
    @Test
    fun `buildSearchUrl omits relevance`() {
        assertEquals("/search?q=space", buildSearchUrl(SearchFilters(query = "space", sort = SortOrder.RELEVANCE)))
    }

    @Test
    fun `SortOrder parses its parameter and falls back on anything else`() {
        assertEquals(SortOrder.NEWEST, SortOrder.parse("newest"))
        assertEquals(SortOrder.OLDEST, SortOrder.parse("OLDEST"))
        assertEquals(SortOrder.RELEVANCE, SortOrder.parse("relevance"))
        // The parameter is user-typed, so nonsense falls back rather than failing.
        assertEquals(SortOrder.RELEVANCE, SortOrder.parse("sideways"))
        assertEquals(SortOrder.RELEVANCE, SortOrder.parse(null))
    }

    @Test
    fun `relevance means newest when there is no query to rank by`() {
        assertEquals(SortOrder.NEWEST, SearchFilters(query = "").effectiveSort)
        assertEquals(SortOrder.RELEVANCE, SearchFilters(query = "space").effectiveSort)
        // An explicit choice is never overridden.
        assertEquals(SortOrder.OLDEST, SearchFilters(query = "", sort = SortOrder.OLDEST).effectiveSort)
    }

    @Test
    fun `a year filter counts as an active filter`() {
        assertTrue(SearchFilters(years = setOf("2024")).hasActiveFilters())
        assertFalse(SearchFilters().hasActiveFilters())
    }

    // --- cue ordinals (W6) ---

    @Test
    fun `cueIndex counts cues in order`() {
        val vtt =
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:01.000\nFirst\n\n" +
                "00:00:01.000 --> 00:00:02.000\nSecond\n\n" +
                "00:00:02.000 --> 00:00:03.000\nThird\n"
        assertEquals(listOf(0, 1, 2), parseTranscript(vtt).map { it.cueIndex })
    }

    /**
     * The reason cueIndex exists: a cue with no text is dropped from the list
     * but still counts as a whisper segment, so line index and cue ordinal
     * diverge from that point on. The word sidecar keys on the ordinal.
     */
    @Test
    fun `a blank cue still advances the ordinal`() {
        val vtt =
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:01.000\nFirst\n\n" +
                "00:00:01.000 --> 00:00:02.000\n\n" +
                "00:00:02.000 --> 00:00:03.000\nThird\n"
        val lines = parseTranscript(vtt)
        assertEquals(listOf("First", "Third"), lines.map { it.text })
        assertEquals(listOf(0, 2), lines.map { it.cueIndex })
    }
}
