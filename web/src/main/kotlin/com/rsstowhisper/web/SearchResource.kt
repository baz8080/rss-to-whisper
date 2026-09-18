package com.rsstowhisper.web

import com.rsstowhisper.web.db.EpisodeRepository
import com.rsstowhisper.web.models.Episode
import com.rsstowhisper.web.models.SearchFilters
import com.rsstowhisper.web.models.SearchResult
import com.rsstowhisper.web.models.SortOrder
import com.rsstowhisper.web.models.appendableSearchUrl
import com.rsstowhisper.web.models.buildSearchUrl
import com.rsstowhisper.web.models.episodeQuerySuffix
import com.rsstowhisper.web.models.hasActiveFilters
import com.rsstowhisper.web.models.highlightMatches
import com.rsstowhisper.web.models.linkify
import com.rsstowhisper.web.models.parseTranscript
import com.rsstowhisper.web.models.sanitizeHtml
import com.rsstowhisper.web.models.searchTerms
import com.rsstowhisper.web.models.urlEncode
import io.smallrye.common.annotation.Blocking
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.CacheControl
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Request
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.RuntimeDelegate
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale

@Path("/")
@ApplicationScoped
@Blocking
class SearchResource {
    @Inject
    lateinit var repository: EpisodeRepository

    @Inject
    lateinit var templateEngine: TemplateEngine

    @ConfigProperty(name = "app.data.url")
    lateinit var dataUrl: String

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun index(): Response = Response.seeOther(URI("/search")).build()

    @GET
    @Path("/search")
    @Produces(MediaType.TEXT_HTML)
    fun search(
        @QueryParam("q") @DefaultValue("") query: String,
        @QueryParam("duration") durations: List<String>,
        @QueryParam("podcast") podcasts: List<String>,
        @QueryParam("collection") collections: List<String>,
        @QueryParam("tag") tags: List<String>,
        @QueryParam("episodeType") episodeTypes: List<String>,
        @QueryParam("year") years: List<String>,
        @QueryParam("sort") @DefaultValue("relevance") sort: String,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @HeaderParam("HX-Request") htmxRequest: String?,
    ): String {
        val filters =
            SearchFilters(
                query = query.trim(),
                durations = durations.toSet(),
                podcasts = podcasts.toSet(),
                collections = collections.toSet(),
                tags = tags.toSet(),
                episodeTypes = episodeTypes.toSet(),
                years = years.toSet(),
                sort = SortOrder.parse(sort),
                page = page.coerceAtLeast(1),
            )
        val result = repository.search(filters)
        val filterOptions = repository.getFilterOptions(filters.query)
        val base = dataUrl.trimEnd('/')

        val ctx =
            Context().apply {
                setVariable("result", result)
                setVariable("filters", filters)
                setVariable("filterOptions", filterOptions)
                setVariable("dataUrl", base)
                setVariable("hasActiveFilters", filters.hasActiveFilters())
                setVariable("prevUrl", buildSearchUrl(filters.copy(page = filters.page - 1)))
                setVariable("nextUrl", buildSearchUrl(filters.copy(page = filters.page + 1)))
                setVariable("clearUrl", buildSearchUrl(SearchFilters(query = filters.query)))
                // Both controls carry their own state: the select serialises
                // `sort`, the checkboxes serialise `year`. Hide either while its
                // filter is active and the next form submit drops that filter --
                // silently reordering or rewidening the results.
                setVariable("showSort", filters.query.isNotBlank() || filters.sort != SortOrder.RELEVANCE)
                // Relevance is not on offer without a query: every row scores the
                // same, so it would read as a choice that does nothing.
                setVariable(
                    "sortOptions",
                    if (filters.query.isBlank()) listOf(SortOrder.NEWEST, SortOrder.OLDEST) else SortOrder.entries,
                )
                // A query can narrow the corpus past the value being filtered
                // on -- easy to reach from the podcasts page, which lands on
                // /search?podcast=X -- and the options come back narrowed by that
                // query, which would otherwise leave no checkbox to untick.
                setVariable("yearOptions", (filterOptions.years + filters.years).distinct().sortedDescending())
                setVariable("podcastOptions", (filterOptions.podcasts + filters.podcasts).distinct().sorted())
                setVariable(
                    "collectionOptions",
                    (filterOptions.collections + filters.collections).distinct().sorted(),
                )
                setVariable(
                    "episodeTypeOptions",
                    (filterOptions.episodeTypes + filters.episodeTypes).distinct().sorted(),
                )
                // Page 1 on both: changing the tags changes the result set, so
                // the page number carried over would point somewhere else.
                setVariable("tagBaseUrl", appendableSearchUrl(filters.copy(page = 1)))
                setVariable(
                    "tagRemoveUrls",
                    filters.tags.associateWith { tag ->
                        buildSearchUrl(filters.copy(tags = filters.tags - tag, page = 1))
                    },
                )
                // Result links carry the query so the episode page can jump to the matches.
                setVariable("episodeQuerySuffix", episodeQuerySuffix(filters.query))
            }

        // HTMX partial request: return only the #app fragment so the browser
        // can swap it in-place without a full page reload.
        return if (htmxRequest == "true") {
            templateEngine.process("search", setOf("app"), ctx)
        } else {
            templateEngine.process("search", ctx)
        }
    }

    /**
     * The per-word timings written beside the audio, as gzipped NDJSON.
     *
     * Proxied so the data host needs no CORS grant; Content-Encoding lets the browser inflate it.
     */
    @GET
    @Path("/episode/{id}/words")
    fun episodeWords(
        @PathParam("id") id: String,
        @HeaderParam("If-None-Match") ifNoneMatch: String?,
        @jakarta.ws.rs.core.Context request: Request,
    ): Response {
        val uri = wordsUri(id) ?: return Response.status(Response.Status.NOT_FOUND).build()
        // Only a 404 from the data host means "no sidecar"; the page retries anything else on the next play.
        val conditional = ifNoneMatch?.takeIf { it.isNotBlank() }
        val upstream = fetch(uri, conditional) ?: return Response.status(Response.Status.BAD_GATEWAY).build()
        val upstreamTag = upstream.headers().firstValue("ETag").map(::parseEntityTag).orElse(null)
        when (upstream.statusCode()) {
            200 -> Unit
            304 ->
                return if (conditional == null) {
                    Response.status(Response.Status.BAD_GATEWAY).build()
                } else {
                    Response.notModified().cacheControl(REVALIDATE).apply { upstreamTag?.let { tag(it) } }.build()
                }
            404 -> return Response.status(Response.Status.NOT_FOUND).build()
            else -> return Response.status(Response.Status.BAD_GATEWAY).build()
        }
        val bytes = upstream.body()

        val tag = upstreamTag ?: entityTagFor(bytes)
        request.evaluatePreconditions(tag)?.let { return it.cacheControl(REVALIDATE).tag(tag).build() }

        return Response.ok(bytes)
            .type("application/x-ndjson")
            .header("Content-Encoding", "gzip")
            .cacheControl(REVALIDATE)
            .tag(tag)
            .build()
    }

    /**
     * Null unless [dataUrl] is an absolute http(s) URL with a host and no query or fragment,
     * since the server cannot fetch a browser-relative path and the file path is appended to it.
     */
    private fun dataBase(): String? {
        val base = dataUrl.trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        val http = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
        return base.takeIf { http && uri.host != null && uri.rawQuery == null && uri.rawFragment == null }
    }

    private fun wordsUri(id: String): URI? {
        val base = dataBase() ?: return null
        val relative = repository.getEpisodeById(id)?.episodeRelativeAudioPath ?: return null

        val segments = relative.split('/')
        // The value comes from the database; it must not walk up out of the data URL.
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

        val directory =
            segments.dropLast(1).joinToString("") { "/" + urlEncode(it) }
        return runCatching { URI("$base$directory/$WORDS_FILENAME") }.getOrNull()
    }

    /** Null when the data host could not be reached at all. */
    private fun fetch(
        uri: URI,
        ifNoneMatch: String?,
    ): HttpResponse<ByteArray>? =
        try {
            val request =
                HttpRequest.newBuilder(uri).timeout(FETCH_TIMEOUT).GET()
                    .apply { ifNoneMatch?.let { header("If-None-Match", it) } }
                    .build()
            HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    /** The same search as `/search`, for use from a shell or a notebook. */
    @GET
    @Path("/api/search")
    @Produces(MediaType.APPLICATION_JSON)
    fun apiSearch(
        @QueryParam("q") @DefaultValue("") query: String,
        @QueryParam("duration") durations: List<String>,
        @QueryParam("podcast") podcasts: List<String>,
        @QueryParam("collection") collections: List<String>,
        @QueryParam("tag") tags: List<String>,
        @QueryParam("episodeType") episodeTypes: List<String>,
        @QueryParam("year") years: List<String>,
        @QueryParam("sort") @DefaultValue("relevance") sort: String,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("pageSize") @DefaultValue("10") pageSize: Int,
    ): SearchResult {
        val result =
            repository.search(
                SearchFilters(
                    query = query.trim(),
                    durations = durations.toSet(),
                    podcasts = podcasts.toSet(),
                    collections = collections.toSet(),
                    tags = tags.toSet(),
                    episodeTypes = episodeTypes.toSet(),
                    years = years.toSet(),
                    sort = SortOrder.parse(sort),
                    page = page.coerceAtLeast(1),
                    pageSize = pageSize.coerceIn(1, MAX_API_PAGE_SIZE),
                ),
            )
        return result.copy(episodes = result.episodes.map(::withSafeSummary))
    }

    /**
     * Some feeds write `episode_summary` as HTML and some as plain prose, so the
     * episode page branches on it too. The markup is sanitised, because handing
     * an API consumer feed-supplied script moves that obligation onto them
     * silently. The prose is left exactly as it is: an HTML sanitiser turns
     * "Ben & Jerry's" into entities and deletes anything inside angle brackets,
     * which for a summary nobody will render as HTML is only damage.
     */
    private fun withSafeSummary(episode: Episode): Episode =
        episode.copy(
            episodeSummary = episode.episodeSummary?.let { if (it.contains('<')) sanitizeHtml(it) else it },
        )

    /** The full episode, transcript included -- which `/api/search` deliberately omits. */
    @GET
    @Path("/api/episode/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    fun apiEpisode(
        @PathParam("id") id: String,
    ): Response {
        val episode =
            repository.getEpisodeById(id)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Episode not found", "id" to id))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
        return Response.ok(withSafeSummary(episode), MediaType.APPLICATION_JSON).build()
    }

    @GET
    @Path("/podcasts")
    @Produces(MediaType.TEXT_HTML)
    fun podcasts(): Response {
        val summaries = repository.getPodcastSummaries()
        val totals = repository.getCorpusTotals()
        val ctx =
            Context().apply {
                setVariable("summaries", summaries)
                setVariable("totalEpisodes", totals.episodeCount)
                setVariable("totalHours", "%,.0f".format(Locale.ROOT, totals.totalDurationSeconds / 3600.0))
                // Thymeleaf cannot call top-level Kotlin functions here, so the
                // link for each row is built now rather than in the template.
                setVariable(
                    "searchUrls",
                    summaries.associate { it.title to buildSearchUrl(SearchFilters(podcasts = setOf(it.title))) },
                )
                setVariable("indexBuiltAt", repository.indexBuiltAt()?.let { INDEX_BUILT_FORMAT.format(it) })
            }
        return Response.ok(templateEngine.process("podcasts", ctx), MediaType.TEXT_HTML).build()
    }

    @GET
    @Path("/episode/{id}")
    @Produces(MediaType.TEXT_HTML)
    fun episode(
        @PathParam("id") id: String,
        @QueryParam("q") @DefaultValue("") query: String,
    ): Response {
        val episode =
            repository.getEpisodeById(id)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity("Episode not found")
                    .type(MediaType.TEXT_HTML)
                    .build()

        val base = dataUrl.trimEnd('/')
        val terms = searchTerms(query)
        val transcriptLines =
            episode.transcript?.let { parseTranscript(it) }.orEmpty().map { line ->
                line.copy(highlightedHtml = highlightMatches(line.text, terms))
            }
        val linkifiedSummary =
            episode.episodeSummary?.let {
                if (it.contains('<')) sanitizeHtml(it) else linkify(it)
            }

        val ctx =
            Context().apply {
                setVariable("episode", episode)
                setVariable("dataUrl", base)
                setVariable("transcriptLines", transcriptLines)
                setVariable("linkifiedSummary", linkifiedSummary)
                setVariable("query", query.trim())
                setVariable("matchCount", transcriptLines.count { it.matched })
                setVariable("wordsUrl", if (dataBase() == null) null else "/episode/$id/words")
            }

        return Response.ok(templateEngine.process("episode", ctx), MediaType.TEXT_HTML).build()
    }

    companion object {
        /** Written by the pipeline beside each episode's audio. */
        internal const val WORDS_FILENAME = "words.jsonl.gz"

        private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(10)

        private val HTTP: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        }

        private fun parseEntityTag(header: String): EntityTag =
            RuntimeDelegate.getInstance().createHeaderDelegate(EntityTag::class.java).fromString(header)

        /**
         * Used when the data host sends no ETag. Strong, and taken over the bytes
         * themselves so it cannot outrun them.
         * The sidecar is tens of kilobytes; digesting it costs nothing worth
         * trading correctness for.
         */
        private fun entityTagFor(bytes: ByteArray): EntityTag =
            EntityTag(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 32),
            )

        /**
         * no-transform is CacheControl's default and is kept deliberately: the
         * body is already gzip, and an intermediary re-encoding it would leave
         * the browser inflating something twice.
         */
        private val REVALIDATE: CacheControl =
            CacheControl().apply {
                isNoCache = true
                isNoTransform = true
            }

        /**
         * The transcript is not in the search payload, but an unbounded page
         * size would still let one request read the whole corpus.
         */
        internal const val MAX_API_PAGE_SIZE = 100

        // A fact about this machine's filesystem, so the server's zone is the
        // honest one to render it in.
        private val INDEX_BUILT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
