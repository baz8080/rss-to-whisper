package com.rsstowhisper.feed

import com.rometools.rome.io.SyndFeedInput
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SEGMENTED_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0"
         xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
         xmlns:podcast="https://podcastindex.org/namespace/1.0"
         xmlns:omny="https://omny.fm/rss-extensions">
      <channel>
        <title>Test Feed</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <podcast:medium>podcast</podcast:medium>
        <item>
          <title>Episode One</title>
          <guid>ep1</guid>
          <itunes:duration>3600</itunes:duration>
          <podcast:chapters url="https://example.com/ep1/chapters.json" type="application/json+chapters"/>
          <podcast:soundbite startTime="1234.5" duration="60.0">A clip</podcast:soundbite>
          <omny:clips>
            <omny:clip type="Advertisement" start="0" end="92"/>
            <omny:clip type="Content" start="92" end="3600"/>
          </omny:clips>
        </item>
        <item>
          <title>Episode Two</title>
          <guid>ep2</guid>
          <podcast:chapters url="https://example.com/ep2/chapters.json" type="application/json+chapters"/>
        </item>
      </channel>
    </rss>
    """.trimIndent()

private val PLAIN_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0">
      <channel>
        <title>Plain Feed</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <item><title>Episode One</title><guid>ep1</guid></item>
      </channel>
    </rss>
    """.trimIndent()

private val PSC_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0"
         xmlns:podcast="https://podcastindex.org/namespace/1.0"
         xmlns:psc="http://podlove.org/simple-chapters">
      <channel>
        <title>Podlove Feed</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <item>
          <title>Episode One</title>
          <guid>ep1</guid>
          <podcast:chapters url="https://example.com/ep1/chapters.json" type="application/json+chapters"/>
          <psc:chapters version="1.1">
            <psc:chapter start="00:00:00" title="Intro"/>
          </psc:chapters>
        </item>
      </channel>
    </rss>
    """.trimIndent()

/** Copied from a Libsyn-hosted feed, namespace declaration and all -- not built from the constant. */
private val LIBSYN_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0"
         xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
         xmlns:libsyn="https://rss.libsyn.com/ns.xml">
      <channel>
        <title>A Libsyn Show</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <item>
          <title>Episode One</title>
          <guid isPermaLink="false">abc123</guid>
          <itunes:duration>13962</itunes:duration>
          <libsyn:ad-markers>
            <libsyn:ad-marker type="pre" count="2"/>
            <libsyn:ad-marker type="mid" count="2" timestamp="1244"/>
            <libsyn:ad-marker type="post" count="3" timestamp="13926"/>
          </libsyn:ad-markers>
          <libsyn:showId>123456</libsyn:showId>
        </item>
      </channel>
    </rss>
    """.trimIndent()

private val MALFORMED_MARKERS_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0" xmlns:libsyn="https://rss.libsyn.com/ns.xml">
      <channel>
        <title>A Libsyn Show</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <item>
          <title>Episode One</title>
          <guid isPermaLink="false">abc123</guid>
          <libsyn:ad-markers>
            <libsyn:ad-marker type="mid" count="1" timestamp=""/>
            <libsyn:ad-marker type="mid" count="1" timestamp="00:20:44"/>
            <libsyn:ad-marker type="mid" count="1" timestamp="NaN"/>
            <libsyn:ad-marker type="mid" count="1" timestamp="Infinity"/>
            <libsyn:ad-marker type="post" count="two" timestamp="13926"/>
          </libsyn:ad-markers>
        </item>
      </channel>
    </rss>
    """.trimIndent()

private val LONG_FUNDING_URL = "https://example.com/" + "x".repeat(310)

private val TRUNCATION_RSS =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0">
      <channel>
        <title>Truncation Feed</title>
        <link>https://example.com</link>
        <description>A test feed</description>
        <item>
          <title>Episode One</title>
          <guid>ep1</guid>
          <podcast:funding url="$LONG_FUNDING_URL">Support the show</podcast:funding>
        </item>
      </channel>
    </rss>
    """.trimIndent()

class FeedMarkupTest {
    private fun parse(xml: String) = SyndFeedInput().build(InputSource(StringReader(xml)))

    @Test
    fun `report names the namespaced elements no module claimed`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 10)

        assertTrue(report.contains("""<podcast:chapters url="https://example.com/ep1/chapters.json""""))
        assertTrue(report.contains("""type="application/json+chapters""""))
        assertTrue(report.contains("<podcast:soundbite"))
    }

    @Test
    fun `report descends into nested segment elements`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 10)

        assertTrue(report.contains("<omny:clips>"))
        assertTrue(report.contains("""<omny:clip type="Advertisement" start="0" end="92">"""))
    }

    @Test
    fun `itunes duration stays with its module rather than the foreign markup`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 10)

        assertTrue(report.contains("http://www.itunes.com/dtds/podcast-1.0.dtd"))
        assertFalse(report.contains("itunes:duration"))
    }

    @Test
    fun `tally counts every occurrence including nested ones`() {
        val tally = tallyElements(parse(SEGMENTED_RSS).entries)

        assertEquals(2, tally["podcast:chapters"])
        assertEquals(1, tally["podcast:soundbite"])
        assertEquals(1, tally["omny:clips"])
        assertEquals(2, tally["omny:clip"])
    }

    @Test
    fun `limit caps the entries shown but not the entries tallied`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 1)

        assertTrue(report.contains("1 of 2 entries"))
        assertFalse(report.contains("Episode Two"))
        // Episode Two's own podcast:chapters would be invisible here if the tally were
        // computed only over the shown entries.
        assertTrue(report.contains("2 x podcast:chapters"))
    }

    @Test
    fun `a limit of 0 shows every entry, matching the other limit flags in this CLI`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 0)

        assertTrue(report.contains("2 of 2 entries"))
        assertTrue(report.contains("Episode Two"))
    }

    @Test
    fun `a feed carrying nothing extra says so`() {
        val report = feedMarkupReport(parse(PLAIN_RSS), limit = 10)

        assertTrue(report.contains("foreign markup: (none)"))
        assertTrue(report.contains("distinct foreign elements across all 1 entries:\n  (none)"))
    }

    @Test
    fun `psc chapters are claimed by rome-modules and stay invisible, unlike podcast chapters`() {
        val report = feedMarkupReport(parse(PSC_RSS), limit = 10)

        assertTrue(report.contains("podcast:chapters"))
        assertFalse(report.contains("psc:chapters"))
        assertFalse(report.contains("psc:chapter"))
    }

    @Test
    fun `channel-level foreign markup is rendered under its own heading`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 10)

        assertTrue(report.contains("channel foreign markup:\n    <podcast:medium>podcast</podcast:medium>"))
    }

    @Test
    fun `ad markers are read out of a real feed's XML, namespace URI and all`() {
        val markers = libsynAdMarkers(parse(LIBSYN_RSS).entries.single())

        assertEquals(
            listOf(
                LibsynAdMarker("pre", 2, null),
                LibsynAdMarker("mid", 2, 1244.0),
                LibsynAdMarker("post", 3, 13926.0),
            ),
            markers,
        )
    }

    @Test
    fun `values that will not parse read as absent rather than as broken numbers`() {
        val markers = libsynAdMarkers(parse(MALFORMED_MARKERS_RSS).entries.single())

        assertEquals(5, markers.size)
        // A NaN or an Infinity would parse, and then Jackson would write it as a bare literal
        // that its own reader rejects, making the transcript unreadable.
        assertTrue(markers.take(4).all { it.timestampSeconds == null })
        assertNull(markers[4].count)
        assertEquals(13926.0, markers[4].timestampSeconds)
    }

    @Test
    fun `long attribute values are truncated with an ellipsis`() {
        val report = feedMarkupReport(parse(TRUNCATION_RSS), limit = 10)

        assertTrue(report.contains(LONG_FUNDING_URL.take(300) + "..."))
        assertFalse(report.contains(LONG_FUNDING_URL))
    }
}
