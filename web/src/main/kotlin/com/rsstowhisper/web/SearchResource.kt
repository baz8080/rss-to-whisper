package com.rsstowhisper.web

import com.rsstowhisper.web.db.EpisodeRepository
import com.rsstowhisper.web.models.SearchFilters
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
            }

        return Response.ok(templateEngine.process("episode", ctx), MediaType.TEXT_HTML).build()
    }

    companion object {
        // The database file's own mtime, so the server's zone is the honest one
        // to render it in -- it is a fact about this machine's filesystem.
        private val INDEX_BUILT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
