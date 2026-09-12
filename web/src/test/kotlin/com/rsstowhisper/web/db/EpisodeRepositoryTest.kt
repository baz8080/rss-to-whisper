package com.rsstowhisper.web.db

import com.rsstowhisper.web.models.SNIPPET_MARK_END
import com.rsstowhisper.web.models.SNIPPET_MARK_START
import com.rsstowhisper.web.models.SearchFilters
import com.rsstowhisper.web.models.SortOrder
import com.rsstowhisper.web.models.renderSnippet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class EpisodeRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var repo: EpisodeRepository

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("test.db").toAbsolutePath().toString()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            createSchema(conn)
            insertFixtures(conn)
            conn.createStatement().execute("INSERT INTO episodes_fts(episodes_fts) VALUES('rebuild')")
        }
        repo = EpisodeRepository()
        repo.dbPath = dbPath
        repo.init()
    }

    @AfterEach
    fun tearDown() = repo.cleanup()

    // --- fixtures ---

    private fun createSchema(conn: Connection) {
        conn.createStatement().execute(
            """CREATE TABLE episodes (
                id TEXT PRIMARY KEY,
                podcast_title TEXT, podcast_link TEXT, podcast_language TEXT,
                podcast_copyright TEXT, podcast_author TEXT, podcast_image TEXT,
                podcast_type TEXT, podcast_collections TEXT,
                episode_title TEXT, episode_published_on TEXT,
                episode_audio_link TEXT, episode_web_link TEXT,
                episode_image TEXT, episode_summary TEXT, episode_subtitle TEXT,
                episode_authors TEXT, episode_number INTEGER, episode_season INTEGER,
                episode_type TEXT, episode_duration INTEGER,
                episode_transcript TEXT, episode_transcript_plain TEXT,
                episode_relative_audio_path TEXT, all_tags TEXT
            )""",
        )
        conn.createStatement().execute(
            """CREATE VIRTUAL TABLE episodes_fts USING fts5(
                episode_title, episode_transcript_plain, podcast_title, all_tags,
                content='episodes', content_rowid='rowid'
            )""",
        )
    }

    private fun insertFixtures(conn: Connection) {
        // ep1 — Podcast A, SHORT duration, tech+science collections, kotlin+jvm tags
        insert(
            conn,
            id = "ep1",
            podcastTitle = "Podcast A",
            episodeTitle = "Kotlin Episode",
            publishedOn = "2024-01-01",
            duration = 600,
            collections = "tech, science",
            tags = "kotlin, jvm",
            type = "full",
            transcriptPlain = "kotlin programming language",
        )
        // ep2 — Podcast A, MEDIUM duration, tech collection, java tag
        insert(
            conn,
            id = "ep2",
            podcastTitle = "Podcast A",
            episodeTitle = "Java Episode",
            publishedOn = "2024-01-03",
            duration = 1800,
            collections = "tech",
            tags = "java",
            type = "full",
            transcriptPlain = "java programming",
        )
        // ep3 — Podcast B, LONG duration, science collection, physics tag, trailer type
        insert(
            conn,
            id = "ep3",
            podcastTitle = "Podcast B",
            episodeTitle = "Physics Trailer",
            // A different year from the rest, so the year filter and the date
            // sorts have something to distinguish.
            publishedOn = "2023-01-02",
            duration = 3600,
            collections = "science",
            tags = "physics",
            type = "trailer",
            transcriptPlain = "physics and quantum mechanics",
        )
        // ep4 — Podcast B, no duration/collections/tags/type — tests null handling
        insert(
            conn,
            id = "ep4",
            podcastTitle = "Podcast B",
            episodeTitle = "No Duration Episode",
            publishedOn = "2024-01-04",
            duration = null,
            collections = null,
            tags = null,
            type = null,
            transcriptPlain = "some content here",
        )
    }

    private fun insert(
        conn: Connection,
        id: String,
        podcastTitle: String,
        episodeTitle: String,
        publishedOn: String,
        duration: Int?,
        collections: String?,
        tags: String?,
        type: String?,
        transcriptPlain: String,
    ) {
        conn.prepareStatement(
            """INSERT INTO episodes
               (id, podcast_title, episode_title, episode_published_on, episode_duration,
                podcast_collections, all_tags, episode_type,
                episode_transcript, episode_transcript_plain, episode_relative_audio_path)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.setString(2, podcastTitle)
            stmt.setString(3, episodeTitle)
            stmt.setString(4, publishedOn)
            stmt.setObject(5, duration)
            stmt.setObject(6, collections)
            stmt.setObject(7, tags)
            stmt.setObject(8, type)
            stmt.setString(9, "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n$transcriptPlain\n")
            stmt.setString(10, transcriptPlain)
            stmt.setString(11, "$id/audio.mp3")
            stmt.execute()
        }
    }

    // --- tests ---

    @Nested
    inner class Search {
        @Test
        fun `no query returns all episodes ordered by published date descending`() {
            val result = repo.search(SearchFilters())
            assertEquals(4, result.totalCount)
            assertEquals(listOf("ep4", "ep2", "ep1", "ep3"), result.episodes.map { it.id })
        }

        @Test
        fun `query filters by FTS and returns snippet`() {
            val result = repo.search(SearchFilters(query = "kotlin"))
            assertEquals(1, result.totalCount)
            assertEquals("ep1", result.episodes.single().id)
            assertNotNull(result.episodes.single().snippet)
        }

        @Test
        fun `snippet highlights with sentinels, not literal mark tags`() {
            // Pins the contract with Episode.snippetHtml: the repository emits
            // char(1)/char(2) so the snippet can be HTML-escaped before render.
            val snippet = repo.search(SearchFilters(query = "kotlin")).episodes.single().snippet!!
            assertTrue(snippet.contains(SNIPPET_MARK_START), "expected start sentinel in: $snippet")
            assertTrue(snippet.contains(SNIPPET_MARK_END), "expected end sentinel in: $snippet")
            assertFalse(snippet.contains("<mark>"))
            assertTrue(renderSnippet(snippet).contains("<mark>"))
        }

        @Test
        fun `query matching multiple episodes returns all matches`() {
            val result = repo.search(SearchFilters(query = "programming"))
            assertEquals(2, result.totalCount)
            val ids = result.episodes.map { it.id }.toSet()
            assertTrue("ep1" in ids)
            assertTrue("ep2" in ids)
        }

        @Test
        fun `no-query results have null snippet`() {
            assertTrue(repo.search(SearchFilters()).episodes.all { it.snippet == null })
        }

        @Test
        fun `query with FTS syntax characters does not throw`() {
            // Each of these is invalid FTS5 syntax as-is; raw MATCH would raise
            // a SQLException that surfaced to the user as a 500.
            for (q in listOf("c++", "\"unbalanced", "AND", "kotlin AND", "(open", "*star")) {
                val result = repo.search(SearchFilters(query = q))
                assertTrue(result.totalCount >= 0, "query '$q' should not throw")
            }
        }

        @Test
        fun `quoted fallback still finds matching terms`() {
            // 'kotlin++' is invalid FTS5; the fallback quotes it as a literal
            // token, which FTS tokenises back to 'kotlin' and matches ep1.
            val result = repo.search(SearchFilters(query = "kotlin++"))
            assertEquals(1, result.totalCount)
            assertEquals("ep1", result.episodes.single().id)
        }

        @Test
        fun `valid boolean queries keep working`() {
            val result = repo.search(SearchFilters(query = "kotlin OR java"))
            assertEquals(2, result.totalCount)
        }

        @Test
        fun `an unmatchable query returns no results rather than the whole corpus`() {
            // safeFtsQuery must never degrade a non-blank query to "no query":
            // that path silently hands back every episode as if it had matched.
            val total = repo.search(SearchFilters()).totalCount
            assertTrue(total > 0, "fixture should have episodes")
            for (q in listOf("c++", "\"unbalanced", "AND", "kotlin AND", "(open", "*star", "-", "^")) {
                assertTrue(
                    repo.search(SearchFilters(query = q)).totalCount < total,
                    "query '$q' fell back to returning the entire corpus",
                )
            }
        }

        @Test
        fun `getFilterOptions with FTS syntax characters does not throw`() {
            // Falls back to a quoted literal, which matches nothing in the fixtures.
            val options = repo.getFilterOptions("\"unbalanced")
            assertTrue(options.podcasts.isEmpty())
        }

        @Nested
        inner class DurationFilter {
            @Test
            fun `SHORT returns episodes under 900 seconds`() {
                val result = repo.search(SearchFilters(durations = setOf("SHORT")))
                assertEquals(1, result.totalCount)
                assertEquals("ep1", result.episodes.single().id)
            }

            @Test
            fun `MEDIUM returns episodes between 900 and 2700 seconds`() {
                val result = repo.search(SearchFilters(durations = setOf("MEDIUM")))
                assertEquals(1, result.totalCount)
                assertEquals("ep2", result.episodes.single().id)
            }

            @Test
            fun `LONG returns episodes over 2700 seconds`() {
                val result = repo.search(SearchFilters(durations = setOf("LONG")))
                assertEquals(1, result.totalCount)
                assertEquals("ep3", result.episodes.single().id)
            }

            @Test
            fun `multiple duration categories are ORed`() {
                val result = repo.search(SearchFilters(durations = setOf("SHORT", "LONG")))
                assertEquals(2, result.totalCount)
                val ids = result.episodes.map { it.id }.toSet()
                assertTrue("ep1" in ids)
                assertTrue("ep3" in ids)
            }

            @Test
            fun `null duration is excluded by all duration filters`() {
                for (cat in listOf("SHORT", "MEDIUM", "LONG")) {
                    val result = repo.search(SearchFilters(durations = setOf(cat)))
                    assertFalse(result.episodes.any { it.id == "ep4" }, "ep4 should not appear for $cat")
                }
            }
        }

        @Nested
        inner class PodcastFilter {
            @Test
            fun `single podcast restricts results`() {
                val result = repo.search(SearchFilters(podcasts = setOf("Podcast A")))
                assertEquals(2, result.totalCount)
                assertTrue(result.episodes.all { it.podcastTitle == "Podcast A" })
            }

            @Test
            fun `multiple podcasts are ORed`() {
                val result = repo.search(SearchFilters(podcasts = setOf("Podcast A", "Podcast B")))
                assertEquals(4, result.totalCount)
            }
        }

        @Nested
        inner class CollectionsFilter {
            @Test
            fun `collections filter matches episodes containing that collection`() {
                val result = repo.search(SearchFilters(collections = setOf("science")))
                assertEquals(2, result.totalCount)
                val ids = result.episodes.map { it.id }.toSet()
                assertTrue("ep1" in ids)
                assertTrue("ep3" in ids)
            }

            @Test
            fun `collections filter does not match on substring`() {
                // 'sci' is a prefix of 'science'; the old LIKE %sci% matched it.
                assertEquals(0, repo.search(SearchFilters(collections = setOf("sci"))).totalCount)
            }

            @Test
            fun `collections filter is case-insensitive`() {
                assertEquals(2, repo.search(SearchFilters(collections = setOf("Science"))).totalCount)
            }
        }

        @Nested
        inner class TagsFilter {
            @Test
            fun `tags filter matches episodes containing that tag`() {
                val result = repo.search(SearchFilters(tags = setOf("kotlin")))
                assertEquals(1, result.totalCount)
                assertEquals("ep1", result.episodes.single().id)
            }

            @Test
            fun `tags filter does not match on substring`() {
                // 'ava' is inside 'java'; the old LIKE %ava% matched it.
                assertEquals(0, repo.search(SearchFilters(tags = setOf("ava"))).totalCount)
            }

            @Test
            fun `tags filter treats percent as a literal`() {
                assertEquals(0, repo.search(SearchFilters(tags = setOf("%"))).totalCount)
            }
        }

        @Nested
        inner class EpisodeTypeFilter {
            @Test
            fun `episode type filter restricts to that type`() {
                val result = repo.search(SearchFilters(episodeTypes = setOf("trailer")))
                assertEquals(1, result.totalCount)
                assertEquals("ep3", result.episodes.single().id)
            }
        }

        @Nested
        inner class SortAndYear {
            @Test
            fun `newest first orders by publication date descending`() {
                val result = repo.search(SearchFilters(sort = SortOrder.NEWEST))
                assertEquals(listOf("ep4", "ep2", "ep1", "ep3"), result.episodes.map { it.id })
            }

            @Test
            fun `oldest first reverses it`() {
                val result = repo.search(SearchFilters(sort = SortOrder.OLDEST))
                assertEquals(listOf("ep3", "ep1", "ep2", "ep4"), result.episodes.map { it.id })
            }

            /** Every row scores the same without a query, so relevance means newest. */
            @Test
            fun `relevance without a query falls back to newest`() {
                val relevance = repo.search(SearchFilters(sort = SortOrder.RELEVANCE)).episodes.map { it.id }
                val newest = repo.search(SearchFilters(sort = SortOrder.NEWEST)).episodes.map { it.id }
                assertEquals(newest, relevance)
            }

            @Test
            fun `a date sort still applies with a query`() {
                val result = repo.search(SearchFilters(query = "programming", sort = SortOrder.OLDEST))
                assertEquals(listOf("ep1", "ep2"), result.episodes.map { it.id })
                assertEquals(2, result.totalCount)
            }

            @Test
            fun `the year filter restricts to that year`() {
                val result = repo.search(SearchFilters(years = setOf("2023")))
                assertEquals(listOf("ep3"), result.episodes.map { it.id })
                assertEquals(1, result.totalCount)
            }

            @Test
            fun `several years are combined`() {
                val result = repo.search(SearchFilters(years = setOf("2023", "2024")))
                assertEquals(4, result.totalCount)
            }

            @Test
            fun `a year with no episodes returns nothing`() {
                assertEquals(0, repo.search(SearchFilters(years = setOf("1999"))).totalCount)
            }

            @Test
            fun `the year filter combines with a query`() {
                val result = repo.search(SearchFilters(query = "programming", years = setOf("2024")))
                assertEquals(2, result.totalCount)
            }

            @Test
            fun `filter options list the years present, newest first`() {
                assertEquals(listOf("2024", "2023"), repo.getFilterOptions("").years)
            }

            @Test
            fun `filter options narrow the years to those matching the query`() {
                assertEquals(listOf("2023"), repo.getFilterOptions("quantum").years)
            }
        }

        @Nested
        inner class Pagination {
            @Test
            fun `first page returns up to pageSize results`() {
                val result = repo.search(SearchFilters(page = 1, pageSize = 2))
                assertEquals(2, result.episodes.size)
                assertEquals(4, result.totalCount)
            }

            @Test
            fun `second page returns the remaining results`() {
                val page1Ids = repo.search(SearchFilters(page = 1, pageSize = 2)).episodes.map { it.id }.toSet()
                val page2Ids = repo.search(SearchFilters(page = 2, pageSize = 2)).episodes.map { it.id }.toSet()
                assertTrue(page1Ids.intersect(page2Ids).isEmpty())
                assertEquals(4, (page1Ids + page2Ids).size)
            }

            @Test
            fun `page beyond available results returns empty episode list with correct total`() {
                val result = repo.search(SearchFilters(page = 99, pageSize = 10))
                assertTrue(result.episodes.isEmpty())
                assertEquals(4, result.totalCount)
            }
        }
    }

    @Nested
    inner class BrokenFtsTable {
        // index.py drops episodes_fts at the start of a reindex and only
        // recreates it at the end, so the web server can meet a database whose
        // episodes table is populated but whose FTS index is gone. A search must
        // fail loudly there rather than quietly dropping the term and handing
        // back every episode as if it had matched.
        @Test
        fun `a search against a missing episodes_fts raises instead of returning everything`() {
            val dbPath = tempDir.resolve("no-fts.db").toAbsolutePath().toString()
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                createSchema(conn)
                insertFixtures(conn)
                conn.createStatement().execute("DROP TABLE episodes_fts")
            }
            val brokenRepo = EpisodeRepository().apply { this.dbPath = dbPath }
            brokenRepo.init()
            try {
                assertThrows(SQLException::class.java) { brokenRepo.search(SearchFilters(query = "kotlin")) }
            } finally {
                brokenRepo.cleanup()
            }
        }
    }

    @Nested
    inner class GetEpisodeById {
        @Test
        fun `returns episode with correct fields`() {
            val episode = repo.getEpisodeById("ep1")!!
            assertEquals("ep1", episode.id)
            assertEquals("Podcast A", episode.podcastTitle)
            assertEquals("Kotlin Episode", episode.episodeTitle)
        }

        @Test
        fun `includes transcript`() {
            val transcript = repo.getEpisodeById("ep1")!!.transcript!!
            assertTrue(transcript.startsWith("WEBVTT"))
        }

        @Test
        fun `returns null for unknown id`() {
            assertNull(repo.getEpisodeById("nonexistent"))
        }
    }

    @Nested
    inner class GetFilterOptions {
        @Test
        fun `returns all distinct podcast titles sorted`() {
            val options = repo.getFilterOptions("")
            assertEquals(listOf("Podcast A", "Podcast B"), options.podcasts)
        }

        @Test
        fun `returns collections split from CSV and sorted`() {
            val options = repo.getFilterOptions("")
            assertEquals(listOf("science", "tech"), options.collections)
        }

        @Test
        fun `returns distinct episode types`() {
            val options = repo.getFilterOptions("")
            assertEquals(listOf("full", "trailer"), options.episodeTypes)
        }

        @Test
        fun `scopes results to FTS matches when query is provided`() {
            val options = repo.getFilterOptions("kotlin")
            assertEquals(listOf("Podcast A"), options.podcasts)
        }

        @Test
        fun `second call with same query returns cached object`() {
            val first = repo.getFilterOptions("kotlin")
            val second = repo.getFilterOptions("kotlin")
            assertSame(first, second)
        }

        @Test
        fun `different query bypasses cache and re-queries`() {
            val forKotlin = repo.getFilterOptions("kotlin")
            val forPhysics = repo.getFilterOptions("physics")
            assertNotSame(forKotlin, forPhysics)
            assertEquals(listOf("Podcast A"), forKotlin.podcasts)
            assertEquals(listOf("Podcast B"), forPhysics.podcasts)
        }
    }
}
