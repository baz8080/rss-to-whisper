package com.rsstowhisper.web.models

import org.owasp.html.PolicyFactory
import org.owasp.html.Sanitizers

data class Episode(
    val id: String,
    val podcastTitle: String?,
    val podcastImage: String?,
    val podcastCollections: String?,
    val episodeTitle: String?,
    val episodePublishedOn: String?,
    val episodeAudioLink: String?,
    val episodeWebLink: String?,
    val episodeImage: String?,
    val episodeSummary: String?,
    val episodeNumber: Int?,
    val episodeSeason: Int?,
    val episodeType: String?,
    val episodeDuration: Int?,
    val episodeRelativeAudioPath: String?,
    val allTags: String?,
    val snippet: String? = null,
    val transcript: String? = null,
) {
    // Computed properties — accessible from Thymeleaf as episode.formattedDuration etc.
    val formattedDuration: String? get() = episodeDuration?.let { formatDuration(it) }
    val tagList: List<String> get() =
        allTags
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?: emptyList()
    val audioPath: String? get() = episodeRelativeAudioPath

    // The raw snippet is feed-controlled text (it spans episode_title and
    // podcast_title as well as the transcript) with sentinel highlight markers.
    // Escape it, then turn only the sentinels into <mark> -- so a feed cannot
    // smuggle markup through by containing a literal "<mark>".
    val snippetHtml: String? get() = snippet?.let { renderSnippet(it) }
}

data class SearchResult(
    val episodes: List<Episode>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
) {
    val totalPages: Int get() = if (totalCount == 0) 1 else (totalCount + pageSize - 1) / pageSize
    val hasPrevious: Boolean get() = page > 1
    val hasNext: Boolean get() = page < totalPages
}

data class SearchFilters(
    val query: String = "",
    val durations: Set<String> = emptySet(),
    val podcasts: Set<String> = emptySet(),
    val collections: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val episodeTypes: Set<String> = emptySet(),
    val page: Int = 1,
    val pageSize: Int = 10,
)

data class FilterOptions(
    val podcasts: List<String>,
    val collections: List<String>,
    val episodeTypes: List<String>,
)

enum class DurationCategory(val label: String, val maxSeconds: Int?) {
    SHORT("Short (< 15 min)", 900),
    MEDIUM("Medium (15–45 min)", 2700),
    LONG("Long (> 45 min)", null),
}

// Transcript line — passed to Thymeleaf as a list for the episode detail page.
// highlightedHtml is set only on lines the search query matched: escaped text
// with the matched words wrapped in <mark>, ready for th:utext.
data class TranscriptLine(
    val millis: Long,
    val text: String,
    val highlightedHtml: String? = null,
) {
    val seconds: Double get() = millis / 1000.0
    val display: String get() = formatTimestamp(millis)
    val matched: Boolean get() = highlightedHtml != null
}

/**
 * One term of a search query, as FTS5 would see it: a run of words that must
 * appear consecutively, the last of which may be a prefix (`quant*`).
 */
data class SearchTerm(val words: List<String>, val prefix: Boolean = false)

// --- Utility functions ---

fun SearchFilters.hasActiveFilters(): Boolean =
    query.isNotBlank() || durations.isNotEmpty() || podcasts.isNotEmpty() ||
        collections.isNotEmpty() || tags.isNotEmpty() || episodeTypes.isNotEmpty()

// URLEncoder targets form encoding, where a space becomes '+'. Whether '+' is
// decoded back to a space in a *query string* is up to the server, so spaces are
// re-written as %20, which every decoder agrees on. Any '+' left after encoding
// is an encoded space -- a literal '+' in the input has already become %2B.
private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

/** What to append to an episode link so the transcript can jump to the matches: empty without a query. */
fun episodeQuerySuffix(query: String): String = if (query.isBlank()) "" else "?q=${urlEncode(query.trim())}"

fun buildSearchUrl(filters: SearchFilters): String {
    val params = mutableListOf<String>()
    if (filters.query.isNotBlank()) params.add("q=${urlEncode(filters.query)}")
    filters.durations.forEach { params.add("duration=${urlEncode(it)}") }
    filters.podcasts.forEach { params.add("podcast=${urlEncode(it)}") }
    filters.collections.forEach { params.add("collection=${urlEncode(it)}") }
    filters.tags.forEach { params.add("tag=${urlEncode(it)}") }
    filters.episodeTypes.forEach { params.add("episodeType=${urlEncode(it)}") }
    if (filters.page > 1) params.add("page=${filters.page}")
    return "/search?${params.joinToString("&")}"
}

/**
 * [buildSearchUrl] with a trailing separator, so a template can append one more
 * parameter to it.
 *
 * Tag pills are built in the template because the tags come from each result
 * card, so the set is not known here. Ending the base in `?` or `&` as
 * appropriate keeps the result a well-formed URL either way, rather than the
 * `/search?&tag=x` that appending blind would produce.
 */
fun appendableSearchUrl(filters: SearchFilters): String {
    val url = buildSearchUrl(filters)
    return if (url.endsWith("?")) url else "$url&"
}

fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatTimestamp(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private val VTT_TIMING_REGEX = Regex("""(\d{2}):(\d{2}):(\d{2})\.(\d{3}) --> .*""")

fun parseTranscript(transcript: String): List<TranscriptLine> {
    if (transcript.isBlank()) return emptyList()

    val result = mutableListOf<TranscriptLine>()
    var currentStartMs: Long? = null
    val currentText = StringBuilder()

    fun flush() {
        if (currentStartMs != null && currentText.isNotBlank()) {
            result.add(TranscriptLine(currentStartMs!!, currentText.toString().trim()))
        }
    }

    for (line in transcript.lines()) {
        val trimmed = line.trim()
        val timingMatch = VTT_TIMING_REGEX.matchEntire(trimmed)
        when {
            timingMatch != null -> {
                flush()
                val h = timingMatch.groupValues[1].toLong()
                val m = timingMatch.groupValues[2].toLong()
                val s = timingMatch.groupValues[3].toLong()
                val ms = timingMatch.groupValues[4].toLong()
                currentStartMs = h * 3_600_000L + m * 60_000L + s * 1_000L + ms
                currentText.clear()
            }
            trimmed.isBlank() -> {
                flush()
                currentStartMs = null
                currentText.clear()
            }
            trimmed != "WEBVTT" && currentStartMs != null -> {
                if (currentText.isNotEmpty()) currentText.append(" ")
                currentText.append(trimmed)
            }
        }
    }
    flush()
    return result
}

// Sentinel code points wrapped around FTS5 matches by EpisodeRepository. They
// cannot occur in feed or transcript text, so they survive HTML escaping
// unambiguously and mark exactly what the search engine matched.
const val SNIPPET_MARK_START = "\u0001"
const val SNIPPET_MARK_END = "\u0002"

fun renderSnippet(snippet: String): String =
    escapeHtml(snippet)
        .replace(SNIPPET_MARK_START, "<mark>")
        .replace(SNIPPET_MARK_END, "</mark>")

// Episode summaries arrive as raw HTML from third-party feeds. Anything outside
// this allow-list (script, iframe, event handlers, javascript: URLs) is dropped.
// IMAGES is included because show notes routinely embed artwork; it permits
// <img> only with an http/https src, and no event handlers survive it.
private val SUMMARY_POLICY: PolicyFactory =
    Sanitizers.FORMATTING
        .and(Sanitizers.BLOCKS)
        .and(Sanitizers.IMAGES)
        .and(Sanitizers.LINKS)

fun sanitizeHtml(html: String): String = SUMMARY_POLICY.sanitize(html)

// --- Query term matching ---
//
// The FTS index is one row per episode, so SQLite can say an episode matches
// but not where. To take the reader to the passage, the query is re-applied
// cue by cue here, imitating what the unicode61 tokenizer did at index time:
// a word is a run of letters and digits, compared case-insensitively with
// diacritics removed. "don't" is two words to both, so the two agree.

private val WORD_REGEX = Regex("""[\p{L}\p{N}]+""")
private val COMBINING_MARKS = Regex("""\p{M}+""")
private val COLUMN_FILTER_PREFIX = Regex("""^[A-Za-z_]\w*:""")
private val FTS_OPERATORS = setOf("AND", "OR", "NOT", "NEAR")
private val NEAR_DISTANCE = Regex("""(NEAR\s*\([^)]*?),\s*\d+\s*(\))""")
private val PLUS_JOIN = Regex("""\s*\+\s*""")

private fun foldWord(word: String): String =
    COMBINING_MARKS.replace(java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD), "").lowercase()

private fun words(text: String): List<String> = WORD_REGEX.findAll(text).map { foldWord(it.value) }.toList()

/**
 * The terms a query asks for, in FTS5's own reading of it.
 *
 * Quoted phrases are kept whole, so "climate change" highlights the pair and
 * not every "change"; so are words joined with `+`, which is FTS5's other
 * phrase syntax. Bare words are single terms. AND, OR, NOT and NEAR are
 * operators to FTS5 only when upper-case, and are dropped on the same rule,
 * along with NEAR's distance argument; everything else -- column filters,
 * `^`, parentheses -- is stripped rather than matched, since it constrains
 * where a term appears, not what it is.
 */
fun searchTerms(query: String): List<SearchTerm> {
    val terms = mutableListOf<SearchTerm>()
    // NEAR(a b, 10): the 10 is a distance, not a word.
    val cleaned = NEAR_DISTANCE.replace(query, "$1$2")

    fun add(
        raw: String,
        prefix: Boolean,
    ) {
        val ws = words(raw)
        if (ws.isNotEmpty()) terms += SearchTerm(ws, prefix)
    }

    fun addBare(chunk: String) {
        // "a + b" is one phrase to FTS5. Pulled together here so it stays one
        // token, whose words() then split on the '+' like any other punctuation.
        val joined = PLUS_JOIN.replace(chunk, "+")
        joined.split(Regex("""[\s()]+""")).forEach { token ->
            if (token.isEmpty() || token in FTS_OPERATORS) return@forEach
            var t = COLUMN_FILTER_PREFIX.replace(token.removePrefix("^"), "")
            val prefix = t.endsWith("*")
            if (prefix) t = t.trimEnd('*')
            add(t, prefix)
        }
    }

    val bare = StringBuilder()
    var i = 0
    while (i < cleaned.length) {
        if (cleaned[i] != '"') {
            bare.append(cleaned[i++])
            continue
        }
        addBare(bare.toString())
        bare.clear()

        // Inside a phrase a doubled quote is a literal one, as in SQL.
        val phrase = StringBuilder()
        i++
        while (i < cleaned.length) {
            if (cleaned[i] == '"') {
                if (i + 1 < cleaned.length && cleaned[i + 1] == '"') {
                    phrase.append('"')
                    i += 2
                    continue
                }
                break
            }
            phrase.append(cleaned[i++])
        }
        i++ // past the closing quote, or one past the end of an unterminated phrase
        val prefix = i < cleaned.length && cleaned[i] == '*'
        if (prefix) i++
        add(phrase.toString(), prefix)
    }
    addBare(bare.toString())

    return terms.distinct()
}

/**
 * [text] escaped for HTML with every occurrence of a term wrapped in `<mark>`,
 * or null when no term occurs -- so a caller can tell a matched line from an
 * unmatched one without parsing the result.
 */
fun highlightMatches(
    text: String,
    terms: List<SearchTerm>,
): String? {
    if (terms.isEmpty()) return null

    val tokens = WORD_REGEX.findAll(text).map { Triple(foldWord(it.value), it.range.first, it.range.last + 1) }.toList()

    val hits = mutableListOf<IntRange>()
    for (start in tokens.indices) {
        for (term in terms) {
            val n = term.words.size
            if (start + n > tokens.size) continue
            val matches =
                term.words.withIndex().all { (k, word) ->
                    val token = tokens[start + k].first
                    if (k == n - 1 && term.prefix) token.startsWith(word) else token == word
                }
            if (matches) hits += tokens[start].second until tokens[start + n - 1].third
        }
    }
    if (hits.isEmpty()) return null

    // Overlapping hits ("climate change" and "change") become one mark.
    val merged = mutableListOf<IntRange>()
    for (hit in hits.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && hit.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, hit.last)
        } else {
            merged += hit
        }
    }

    val out = StringBuilder()
    var pos = 0
    for (range in merged) {
        out.append(escapeHtml(text.substring(pos, range.first)))
        out.append("<mark>").append(escapeHtml(text.substring(range.first, range.last + 1))).append("</mark>")
        pos = range.last + 1
    }
    out.append(escapeHtml(text.substring(pos)))
    return out.toString()
}

private val URL_REGEX = Regex("""https?://[^\s<>"]+""")

private fun escapeHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

fun linkify(text: String): String {
    val result = StringBuilder()
    var last = 0
    for (match in URL_REGEX.findAll(text)) {
        result.append(escapeHtml(text.substring(last, match.range.first)))
        val url = match.value
        result.append("""<a href="${escapeHtml(url)}" target="_blank">${escapeHtml(url)}</a>""")
        last = match.range.last + 1
    }
    result.append(escapeHtml(text.substring(last)))
    return result.toString()
}
