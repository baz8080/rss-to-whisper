package com.rsstowhisper.feed

import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import org.jdom2.Element

private const val TEXT_LIMIT = 300

/**
 * Everything a feed carries that the pipeline does not map. ROME hands back as foreign
 * markup any element no registered module claimed, and rome-modules 2.1.0 registers
 * nothing for the Podcasting 2.0 namespace, so chapters, transcripts and soundbites all
 * land there intact.
 */
fun feedMarkupReport(
    feed: SyndFeed,
    limit: Int,
): String {
    val out = StringBuilder()
    out.appendLine("feed: ${feed.title}")
    out.appendLine("modules: ${moduleUris(feed.modules.orEmpty().map { it.uri })}")
    appendMarkup(out, "channel foreign markup", feed.foreignMarkup.orEmpty())

    val entries = feed.entries.orEmpty().take(limit)
    out.appendLine()
    out.appendLine("${entries.size} of ${feed.entries.orEmpty().size} entries")

    entries.forEachIndexed { index, entry ->
        out.appendLine()
        out.appendLine("[${index + 1}] ${entry.title ?: "(untitled)"}")
        out.appendLine("  published: ${entry.publishedDate ?: "(none)"}")
        out.appendLine("  modules: ${moduleUris(entry.modules.orEmpty().map { it.uri })}")
        appendMarkup(out, "  foreign markup", entry.foreignMarkup.orEmpty(), indent = "  ")
    }

    out.appendLine()
    out.appendLine("distinct foreign elements across the sample:")
    val tally = tallyElements(entries)
    if (tally.isEmpty()) {
        out.appendLine("  (none)")
    } else {
        tally.forEach { (name, count) -> out.appendLine("  $count x $name") }
    }
    return out.toString()
}

/**
 * Counts every foreign element in the sample by qualified name, nested ones included, so
 * a tag that appears on one episode in ten is still visible.
 */
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
