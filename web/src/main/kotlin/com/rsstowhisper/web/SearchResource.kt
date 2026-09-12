package com.rsstowhisper.web

import com.rsstowhisper.web.db.EpisodeRepository
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.net.URI
import java.nio.file.Files
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Path("/")
@ApplicationScoped
@Blocking
class SearchResource {
    @Inject
    lateinit var repository: EpisodeRepository

    @Inject
    lateinit var templateEngine: TemplateEngine

    @ConfigProperty(name = "app.audio.base-url", defaultValue = "/audio")
    lateinit var audioBaseUrl: String

    /**
     * Where the pipeline writes its episode directories, so the word-timing
     * sidecars can be served from beside the audio.
     *
     * Optional: blank means the module has no access to that tree, and the
     * word-timing feature stays hidden rather than half-working.
     */
    @ConfigProperty(name = "app.data.directory", defaultValue = "")
    lateinit var dataDirectory: String

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
        val base = audioBaseUrl.trimEnd('/')

        val ctx =
            Context().apply {
                setVariable("result", result)
                setVariable("filters", filters)
                setVariable("filterOptions", filterOptions)
                setVariable("audioBaseUrl", base)
                setVariable("hasActiveFilters", filters.hasActiveFilters())
                setVariable("prevUrl", buildSearchUrl(filters.copy(page = filters.page - 1)))
                setVariable("nextUrl", buildSearchUrl(filters.copy(page = filters.page + 1)))
                setVariable("clearUrl", buildSearchUrl(SearchFilters(query = filters.query)))
                setVariable("sortOptions", SortOrder.entries)
                // Adding a tag resets to page 1: the result set changes, so the
                // old page number points at a different set of episodes.
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
     * Served by this module rather than fetched from the audio host: the path
     * is derivable from a column already in hand, it needs no CORS grant on a
     * server that only has to serve audio, and Content-Encoding lets the
     * browser inflate it instead of the page carrying a decompressor.
     */
    @GET
    @Path("/episode/{id}/words")
    fun episodeWords(
        @PathParam("id") id: String,
    ): Response {
        val wordsPath = wordsFileFor(id) ?: return Response.status(Response.Status.NOT_FOUND).build()
        return Response.ok(Files.readAllBytes(wordsPath))
            .type("application/x-ndjson")
            // The file is gzip on disk and goes out as-is; the browser inflates it.
            .header("Content-Encoding", "gzip")
            .header("Cache-Control", "public, max-age=3600")
            .build()
    }

    /**
     * Null whenever the sidecar cannot be served: no data directory configured,
     * no such episode, an episode with no audio path, a path that escapes the
     * data directory, or an episode transcribed before word timings existed.
     */
    private fun wordsFileFor(id: String): java.nio.file.Path? {
        if (dataDirectory.isBlank()) return null
        val relative = repository.getEpisodeById(id)?.episodeRelativeAudioPath ?: return null

        val root = java.nio.file.Path.of(dataDirectory).toAbsolutePath().normalize()
        // episode_relative_audio_path comes from the database, but a path that
        // reaches outside the data directory must not be servable whatever put
        // it there.
        val audioPath = root.resolve(relative).normalize()
        if (!audioPath.startsWith(root)) return null

        val wordsPath = (audioPath.parent ?: return null).resolve(WORDS_FILENAME).normalize()
        if (!wordsPath.startsWith(root) || !Files.isRegularFile(wordsPath)) return null
        return wordsPath
    }

    /**
     * The same search as `/search`, as JSON, so the corpus is usable from a
     * shell or a notebook.
     */
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
    ): SearchResult =
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
                // Capped: the transcript is not in this payload, but an
                // unbounded page size still lets one request read the corpus.
                pageSize = pageSize.coerceIn(1, MAX_API_PAGE_SIZE),
            ),
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
        return Response.ok(episode, MediaType.APPLICATION_JSON).build()
    }

    @GET
    @Path("/podcasts")
    @Produces(MediaType.TEXT_HTML)
    fun podcasts(): Response {
        val summaries = repository.getPodcastSummaries()
        val ctx =
            Context().apply {
                setVariable("summaries", summaries)
                setVariable("totalEpisodes", summaries.sumOf { it.episodeCount })
                setVariable("totalHours", "%,.0f".format(summaries.sumOf { it.totalDurationSeconds } / 3600.0))
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

        val base = audioBaseUrl.trimEnd('/')
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
                setVariable("audioBaseUrl", base)
                setVariable("transcriptLines", transcriptLines)
                setVariable("linkifiedSummary", linkifiedSummary)
                setVariable("query", query.trim())
                setVariable("matchCount", transcriptLines.count { it.matched })
                // Null hides the feature entirely. Whether this episode actually
                // has a sidecar is settled by the fetch: episodes transcribed
                // before word timings existed have none, and the page falls back
                // silently rather than the server stat'ing the file to render.
                setVariable("wordsUrl", if (dataDirectory.isBlank()) null else "/episode/$id/words")
            }

        return Response.ok(templateEngine.process("episode", ctx), MediaType.TEXT_HTML).build()
    }

    companion object {
        /** Written by the pipeline beside each episode's audio. */
        internal const val WORDS_FILENAME = "words.jsonl.gz"

        /** One page of the API cannot be made to return the whole corpus. */
        internal const val MAX_API_PAGE_SIZE = 100

        // The database file's own mtime, so the server's zone is the honest one
        // to render it in -- it is a fact about this machine's filesystem.
        private val INDEX_BUILT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
