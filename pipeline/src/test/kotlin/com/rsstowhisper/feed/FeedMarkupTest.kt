package com.rsstowhisper.feed

import com.rometools.rome.io.SyndFeedInput
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `long attribute values are truncated with an ellipsis`() {
        val report = feedMarkupReport(parse(TRUNCATION_RSS), limit = 10)

        assertTrue(report.contains(LONG_FUNDING_URL.take(300) + "..."))
        assertFalse(report.contains(LONG_FUNDING_URL))
    }
}
