package com.rsstowhisper.feed

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import org.jdom2.Element
import org.slf4j.LoggerFactory

private const val TEXT_LIMIT = 300

private const val LIBSYN_NAMESPACE = "https://rss.libsyn.com/ns.xml"

private val logger = LoggerFactory.getLogger(LibsynAdMarker::class.java)

/** One `<libsyn:ad-marker>`: an ad break the host declares. A `pre` carries no timestamp. */
data class LibsynAdMarker(
    val type: String?,
    val count: Int?,
    /**
     * Seconds into the publisher's master audio -- NOT the same clock as `episode_chapters`'
     * `start_s`/`end_s`, which are the downloaded file's own, and which dynamic insertion can
     * leave far adrift from this. Published as `timestamp_publisher_s` to say so in the data.
     */
    val timestampSeconds: Double?,
)

/**
 * Libsyn's ad-insertion metadata, which no ROME module claims. Timestamps are seconds into the
 * feed's master audio, which dynamic insertion can leave adrift from the file we download.
 */
fun libsynAdMarkers(entry: SyndEntry): List<LibsynAdMarker> =
    entry.foreignMarkup.orEmpty()
        .flatMap { listOf(it) + it.children }
        .filter { it.name == "ad-marker" && it.namespaceURI == LIBSYN_NAMESPACE }
        .map { marker ->
            LibsynAdMarker(
                type = marker.getAttributeValue("type")?.takeIf { it.isNotBlank() },
                count = marker.parsedAttribute("count") { it.toIntOrNull() },
                timestampSeconds = marker.parsedAttribute("timestamp") { it.toDoubleOrNull()?.takeIf(Double::isFinite) },
            )
        }

/**
 * Null for an absent attribute and for one that will not parse, logging only the second: a
 * malformed timestamp must not read as a pre-roll's deliberately absent one. Non-finite doubles
 * count as unparseable -- Jackson writes them as bare `NaN`, which its own reader then rejects.
 */
private fun <T> Element.parsedAttribute(
    name: String,
    parse: (String) -> T?,
): T? {
    val raw = getAttributeValue(name) ?: return null
    val value = parse(raw)
    if (value == null) logger.warn("Ignoring an unusable libsyn ad-marker $name=\"$raw\"")
    return value
}

/**
 * Everything a feed carries that the pipeline does not map: elements no ROME module
 * claims -- e.g. Podcasting 2.0's tags, but not psc: (Podlove) chapters, which ROME does.
 */
fun feedMarkupReport(
    feed: SyndFeed,
    limit: Int,
): String {
    val out = StringBuilder()
    out.appendLine("feed: ${feed.title}")
    out.appendLine("modules: ${moduleUris(feed.modules.orEmpty().map { it.uri })}")
    appendMarkup(out, "channel foreign markup", feed.foreignMarkup.orEmpty())

    val allEntries = feed.entries.orEmpty()
    val shown = if (limit > 0) allEntries.take(limit) else allEntries
    out.appendLine()
    out.appendLine("${shown.size} of ${allEntries.size} entries")

    shown.forEachIndexed { index, entry ->
        out.appendLine()
        out.appendLine("[${index + 1}] ${entry.title ?: "(untitled)"}")
        out.appendLine("  published: ${entry.publishedDate ?: "(none)"}")
        out.appendLine("  modules: ${moduleUris(entry.modules.orEmpty().map { it.uri })}")
        appendMarkup(out, "  foreign markup", entry.foreignMarkup.orEmpty(), indent = "  ")
    }

    out.appendLine()
    out.appendLine("distinct foreign elements across all ${allEntries.size} entries:")
    val tally = tallyElements(allEntries)
    if (tally.isEmpty()) {
        out.appendLine("  (none)")
    } else {
        tally.forEach { (name, count) -> out.appendLine("  $count x $name") }
    }
    return out.toString()
}

/** Counts nested elements too, not just top-level ones. */
internal fun tallyElements(entries: List<SyndEntry>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()

    fun walk(element: Element) {
        counts.merge(qualifiedName(element), 1, Int::plus)
        element.children.forEach { walk(it) }
    }
    entries.forEach { entry -> entry.foreignMarkup.orEmpty().forEach { walk(it) } }
    return counts.toSortedMap()
}

private fun appendMarkup(
    out: StringBuilder,
    heading: String,
    elements: List<Element>,
    indent: String = "",
) {
    if (elements.isEmpty()) {
        out.appendLine("$heading: (none)")
        return
    }
    out.appendLine("$heading:")
    elements.forEach { out.append(renderElement(it, "$indent    ")) }
}

internal fun renderElement(
    element: Element,
    indent: String,
): String {
    val out = StringBuilder()
    val attributes =
        element.attributes.joinToString("") { " ${it.name}=\"${truncate(it.value)}\"" }
    out.append("$indent<${qualifiedName(element)}$attributes>")

    val text = element.textNormalize
    val children = element.children
    when {
        children.isNotEmpty() -> {
            out.appendLine()
            children.forEach { out.append(renderElement(it, "$indent  ")) }
            out.appendLine("$indent</${qualifiedName(element)}>")
        }
        text.isNotEmpty() -> out.appendLine("${truncate(text)}</${qualifiedName(element)}>")
        else -> out.appendLine()
    }
    return out.toString()
}

private fun qualifiedName(element: Element): String =
    element.namespacePrefix.takeIf { it.isNotEmpty() }?.let { "$it:${element.name}" } ?: element.name

private fun moduleUris(uris: List<String>): String = uris.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(none)"

private fun truncate(value: String): String = if (value.length <= TEXT_LIMIT) value else value.take(TEXT_LIMIT) + "..."
