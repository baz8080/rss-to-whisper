package com.rsstowhisper.web.db

import com.rsstowhisper.web.models.DurationCategory
import com.rsstowhisper.web.models.Episode
import com.rsstowhisper.web.models.FilterOptions
import com.rsstowhisper.web.models.SearchFilters
import com.rsstowhisper.web.models.SearchResult
import com.rsstowhisper.web.models.SortOrder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

@ApplicationScoped
class EpisodeRepository {
    @ConfigProperty(name = "app.db.path")
    lateinit var dbPath: String

    private lateinit var conn: Connection

    // In-memory filter cache — single entry, keyed by query string, expiring
    // after a short TTL so an external reindex by index.py shows up without a
    // server restart. Access is serialised by the @Synchronized methods below,
    // so no additional locking is needed.
    private var cachedFilterQuery: String? = null
    private var cachedFilterOptions: FilterOptions? = null
    private var cachedFilterAtMillis: Long = 0

    @PostConstruct
    fun init() {
        conn =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
                createStatement().execute("PRAGMA query_only=ON")
                createStatement().execute("PRAGMA mmap_size=268435456")
            }
    }

    @PreDestroy
    fun cleanup() {
        conn.close()
    }

    @Synchronized
    fun search(filters: SearchFilters): SearchResult {
        val ftsQuery = safeFtsQuery(filters.query)
        val hasQuery = ftsQuery != null
        val params = mutableListOf<Any>()
        val whereClauses = mutableListOf<String>()

        if (ftsQuery != null) {
            whereClauses.add("episodes_fts MATCH ?")
            params.add(ftsQuery)
        }

        addDurationFilter(filters, whereClauses, params)
        addSetFilter(filters.podcasts, "e.podcast_title", whereClauses, params)
        addCsvContainsFilter(filters.collections, "e.podcast_collections", whereClauses, params)
        addCsvContainsFilter(filters.tags, "e.all_tags", whereClauses, params)
        addSetFilter(filters.episodeTypes, "e.episode_type", whereClauses, params)
        addYearFilter(filters.years, whereClauses, params)

        val whereClause = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"

        val countSql =
            if (hasQuery) {
                "SELECT COUNT(*) FROM episodes e JOIN episodes_fts ON e.rowid = episodes_fts.rowid $whereClause"
            } else {
                "SELECT COUNT(*) FROM episodes e $whereClause"
            }

        val totalCount =
            conn.prepareStatement(countSql).use { stmt ->
                params.forEachIndexed { i, p -> setParam(stmt, i + 1, p) }
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

        // FTS5 snippet signature: snippet(table, column_index, start, end, ellipsis, tokens)
        // column_index -1 = search across all columns
        val snippetExpr =
            if (hasQuery) {
                // char(1)/char(2) rather than literal <mark> tags: the snippet
                // is escaped before rendering, so highlight markers must be
                // something feed text cannot contain. See Episode.snippetHtml.
                "snippet(episodes_fts, -1, char(1), char(2), '…', 40)"
            } else {
                "NULL"
            }

        val selectSql =
            if (hasQuery) {
                """SELECT $SEARCH_COLUMNS, $snippetExpr AS snippet
               FROM episodes e
               JOIN episodes_fts ON e.rowid = episodes_fts.rowid
               $whereClause
               ORDER BY ${orderBy(filters, hasQuery = true)}
               LIMIT ? OFFSET ?"""
            } else {
                """SELECT $SEARCH_COLUMNS, NULL AS snippet
               FROM episodes e
               $whereClause
               ORDER BY ${orderBy(filters, hasQuery = false)}
               LIMIT ? OFFSET ?"""
            }

        val offset = (filters.page - 1) * filters.pageSize
        val episodes =
            conn.prepareStatement(selectSql).use { stmt ->
                params.forEachIndexed { i, p -> setParam(stmt, i + 1, p) }
                val paramOffset = params.size
                stmt.setInt(paramOffset + 1, filters.pageSize)
                stmt.setInt(paramOffset + 2, offset)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<Episode>()
                    while (rs.next()) results.add(mapEpisode(rs))
                    results
                }
            }

        return SearchResult(episodes, totalCount, filters.page, filters.pageSize)
    }

    @Synchronized
    fun getEpisodeById(id: String): Episode? {
        val sql = "SELECT $SEARCH_COLUMNS, e.episode_transcript FROM episodes e WHERE e.id = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapEpisode(rs, includeTranscript = true) else null
            }
        }
    }

    @Synchronized
    fun getFilterOptions(query: String): FilterOptions {
        val now = System.currentTimeMillis()
        if (query == cachedFilterQuery && now - cachedFilterAtMillis < FILTER_CACHE_TTL_MILLIS) {
            return cachedFilterOptions!!
        }

        val ftsQuery = safeFtsQuery(query)
        val hasQuery = ftsQuery != null
        val fromClause =
            if (hasQuery) {
                "FROM episodes e JOIN episodes_fts ON e.rowid = episodes_fts.rowid"
            } else {
                "FROM episodes e"
            }
        val wherePrefix = if (hasQuery) "WHERE episodes_fts MATCH ? AND" else "WHERE"

        fun queryDistinct(column: String): List<String> {
            val sql = "SELECT DISTINCT $column $fromClause $wherePrefix $column IS NOT NULL ORDER BY $column"
            return conn.prepareStatement(sql).use { stmt ->
                if (ftsQuery != null) stmt.setString(1, ftsQuery)
                stmt.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

        fun splitCsv(column: String): List<String> {
            val sql = "SELECT DISTINCT $column $fromClause $wherePrefix $column IS NOT NULL"
            return conn.prepareStatement(sql).use { stmt ->
                if (ftsQuery != null) stmt.setString(1, ftsQuery)
                stmt.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }
                        .flatMap { it.split(",").map(String::trim) }
                        .filter { it.isNotBlank() }
                        .toSortedSet()
                        .toList()
                }
            }
        }

        fun queryYears(): List<String> {
            val sql =
                "SELECT DISTINCT substr(e.episode_published_on, 1, 4) AS y $fromClause " +
                    "$wherePrefix e.episode_published_on IS NOT NULL ORDER BY y DESC"
            return conn.prepareStatement(sql).use { stmt ->
                if (ftsQuery != null) stmt.setString(1, ftsQuery)
                stmt.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }
                        .filter { it.length == 4 }
                        .toList()
                }
            }
        }

        val options =
            FilterOptions(
                podcasts = queryDistinct("e.podcast_title"),
                collections = splitCsv("e.podcast_collections"),
                episodeTypes = queryDistinct("e.episode_type"),
                years = queryYears(),
            )
        cachedFilterQuery = query
        cachedFilterOptions = options
        cachedFilterAtMillis = now
        return options
    }

    // FTS5 MATCH parses its right-hand side as a query language, so raw user
    // input like `c++` or an unbalanced quote raises a SQLException that would
    // surface as a 500. Valid queries pass through untouched (keeping phrase
    // and boolean operators working); anything FTS5 rejects is rewritten with
    // each token quoted as a literal. Returns null only for blank input.
    private fun safeFtsQuery(query: String): String? {
        if (query.isBlank()) return null
        if (isValidFtsQuery(query)) return query

        // The quoted form is a sequence of FTS5 string literals, so it is always
        // syntactically valid -- it is returned without a second probe on purpose.
        // A probe that failed for some reason other than the user's syntax (no
        // episodes_fts table mid-reindex, a locked or corrupt database) must not
        // be able to turn into a null here: that would drop the search term and
        // hand the caller the entire corpus as if it had matched. Letting the
        // real query raise the underlying error is the honest outcome.
        return quoteEachToken(query)
    }

    private fun quoteEachToken(query: String): String =
        query
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }

    // LIMIT 1 rather than LIMIT 0: FTS5 only parses the MATCH expression when
    // the statement is actually stepped, and LIMIT 0 short-circuits the step.
    // A failure here only decides whether to quote; it is never treated as
    // "this search matched nothing".
    private fun isValidFtsQuery(query: String): Boolean =
        runCatching {
            conn.prepareStatement("SELECT 1 FROM episodes_fts WHERE episodes_fts MATCH ? LIMIT 1").use { stmt ->
                stmt.setString(1, query)
                stmt.executeQuery().use { it.next() }
            }
        }.isSuccess

    /**
     * `episode_published_on` is `YYYY-MM-DD` text with an index, so
     * lexicographic comparison orders it correctly.
     *
     * With a query, rank is the secondary key on a date sort: episodes sharing
     * a publication date would otherwise come back in an arbitrary order that
     * could differ between pages of the same search.
     *
     * A recovered orphan keeps a real date from its directory name, so a null
     * date is rare; under DESC it sorts last, which is where an episode of
     * unknown age belongs.
     */
    private fun orderBy(
        filters: SearchFilters,
        hasQuery: Boolean,
    ): String {
        val rankTiebreak = if (hasQuery) ", episodes_fts.rank" else ""
        return when (filters.effectiveSort) {
            SortOrder.RELEVANCE -> if (hasQuery) "episodes_fts.rank" else "e.episode_published_on DESC"
            SortOrder.NEWEST -> "e.episode_published_on DESC$rankTiebreak"
            // SQLite puts NULLs first under ASC, which would head the oldest-first
            // list with episodes of unknown age; DESC already puts them last.
            SortOrder.OLDEST -> "e.episode_published_on IS NULL, e.episode_published_on ASC$rankTiebreak"
        }
    }

    private fun addYearFilter(
        years: Set<String>,
        clauses: MutableList<String>,
        params: MutableList<Any>,
    ) {
        if (years.isEmpty()) return
        val placeholders = years.joinToString(",") { "?" }
        clauses.add("substr(e.episode_published_on, 1, 4) IN ($placeholders)")
        params.addAll(years)
    }

    private fun addDurationFilter(
        filters: SearchFilters,
        clauses: MutableList<String>,
        params: MutableList<Any>,
    ) {
        if (filters.durations.isEmpty()) return
        val short = DurationCategory.SHORT.maxSeconds
        val medium = DurationCategory.MEDIUM.maxSeconds
        val conditions = mutableListOf<String>()
        for (d in filters.durations) {
            when (d.uppercase()) {
                "SHORT" -> conditions.add("(e.episode_duration > 0 AND e.episode_duration < $short)")
                "MEDIUM" -> conditions.add("(e.episode_duration >= $short AND e.episode_duration <= $medium)")
                "LONG" -> conditions.add("(e.episode_duration > $medium)")
            }
        }
        if (conditions.isNotEmpty()) clauses.add("(${conditions.joinToString(" OR ")})")
    }

    private fun addSetFilter(
        values: Set<String>,
        column: String,
        clauses: MutableList<String>,
        params: MutableList<Any>,
    ) {
        if (values.isEmpty()) return
        val placeholders = values.joinToString(",") { "?" }
        clauses.add("$column IN ($placeholders)")
        params.addAll(values)
    }

    // Exact element match against a ", "-separated column. Wrapping both sides
    // in commas stops 'art' matching 'smart', and instr (unlike LIKE) treats
    // '%' and '_' in the value as literals.
    private fun addCsvContainsFilter(
        values: Set<String>,
        column: String,
        clauses: MutableList<String>,
        params: MutableList<Any>,
    ) {
        if (values.isEmpty()) return
        val condition = "instr(',' || REPLACE(lower($column), ', ', ',') || ',', lower(?)) > 0"
        clauses.add("(${values.joinToString(" OR ") { condition }})")
        values.forEach { params.add(",${it.trim()},") }
    }

    private fun setParam(
        stmt: java.sql.PreparedStatement,
        index: Int,
        value: Any,
    ) {
        when (value) {
            is String -> stmt.setString(index, value)
            is Int -> stmt.setInt(index, value)
            else -> stmt.setObject(index, value)
        }
    }

    private fun mapEpisode(
        rs: ResultSet,
        includeTranscript: Boolean = false,
    ): Episode =
        Episode(
            id = rs.getString("id"),
            podcastTitle = rs.getString("podcast_title"),
            podcastImage = rs.getString("podcast_image"),
            podcastCollections = rs.getString("podcast_collections"),
            episodeTitle = rs.getString("episode_title"),
            episodePublishedOn = rs.getString("episode_published_on"),
            episodeAudioLink = rs.getString("episode_audio_link"),
            episodeWebLink = rs.getString("episode_web_link"),
            episodeImage = rs.getString("episode_image"),
            episodeSummary = rs.getString("episode_summary"),
            episodeNumber = rs.getObject("episode_number") as? Int,
            episodeSeason = rs.getObject("episode_season") as? Int,
            episodeType = rs.getString("episode_type"),
            episodeDuration = rs.getObject("episode_duration") as? Int,
            episodeRelativeAudioPath = rs.getString("episode_relative_audio_path"),
            allTags = rs.getString("all_tags"),
            snippet = runCatching { rs.getString("snippet") }.getOrNull(),
            transcript = if (includeTranscript) rs.getString("episode_transcript") else null,
        )

    companion object {
        private const val FILTER_CACHE_TTL_MILLIS = 60_000L

        private const val SEARCH_COLUMNS =
            """e.id, e.podcast_title, e.podcast_image, e.podcast_collections,
               e.episode_title, e.episode_published_on, e.episode_audio_link,
               e.episode_web_link, e.episode_image, e.episode_summary,
               e.episode_number, e.episode_season, e.episode_type,
               e.episode_duration, e.episode_relative_audio_path, e.all_tags"""
    }
}
