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
    fun `limit caps the entries sampled`() {
        val report = feedMarkupReport(parse(SEGMENTED_RSS), limit = 1)

        assertTrue(report.contains("1 of 2 entries"))
        assertFalse(report.contains("Episode Two"))
    }

    @Test
    fun `a feed carrying nothing extra says so`() {
        val report = feedMarkupReport(parse(PLAIN_RSS), limit = 10)

        assertTrue(report.contains("foreign markup: (none)"))
        assertTrue(report.contains("distinct foreign elements across the sample:\n  (none)"))
    }
}
