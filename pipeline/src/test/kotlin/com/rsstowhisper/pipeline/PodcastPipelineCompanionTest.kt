package com.rsstowhisper.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.rometools.modules.itunes.EntryInformationImpl
import com.rometools.modules.itunes.FeedInformationImpl
import com.rometools.modules.itunes.types.Duration
import com.rometools.rome.feed.synd.SyndCategoryImpl
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEnclosureImpl
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.feed.synd.SyndLinkImpl
import com.rometools.rome.feed.synd.SyndPersonImpl
import com.rsstowhisper.audio.Id3Builder
import org.jdom2.Attribute
import org.jdom2.Element
import org.jdom2.Namespace
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LIBSYN_NS = Namespace.getNamespace("libsyn", "https://rss.libsyn.com/ns.xml")

class PodcastPipelineCompanionTest {
    /** Parse a yyyy-MM-dd string as midnight UTC, matching how formatDate works. */
    private fun utcDate(s: String): Date =
        java.text.SimpleDateFormat("yyyy-MM-dd")
            .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(s)!!

    private fun entry(
        title: String? = "My Episode",
        publishedDate: Date? = null,
        enclosures: List<SyndEnclosureImpl> = emptyList(),
        links: List<SyndLinkImpl> = emptyList(),
        guid: String? = null,
    ): SyndEntry =
        SyndEntryImpl().apply {
            this.title = title
            this.publishedDate = publishedDate
            this.enclosures = enclosures
            this.links = links
            this.uri = guid
        }

    private fun enclosure(
        url: String,
        type: String,
        length: Long = 0L,
    ): SyndEnclosureImpl =
        SyndEnclosureImpl().apply {
            this.url = url
            this.type = type
            this.length = length
        }

    private fun link(
        href: String,
        type: String? = null,
        rel: String? = null,
        length: Long = 0L,
    ): SyndLinkImpl =
        SyndLinkImpl().apply {
            this.href = href
            this.type = type
            this.rel = rel
            this.length = length
        }

    // ---------- md5Hash8 ----------

    @Test
    fun `md5Hash8 returns 8 hex characters`() {
        val result = PodcastPipeline.md5Hash8("https://cdn/ep.mp3")
        assertEquals(8, result.length)
        assertTrue(result.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `md5Hash8 is deterministic`() {
        assertEquals(
            PodcastPipeline.md5Hash8("https://cdn/ep.mp3"),
            PodcastPipeline.md5Hash8("https://cdn/ep.mp3"),
        )
    }

    @Test
    fun `md5Hash8 differs for different inputs`() {
        assertNotEquals(
            PodcastPipeline.md5Hash8("https://cdn/ep1.mp3"),
            PodcastPipeline.md5Hash8("https://cdn/ep2.mp3"),
        )
    }

    // ---------- episodeId ----------

    @Test
    fun `episodeId returns hash of guid`() {
        val guid = "https://example.com/guid/123"
        val e = entry(guid = guid)
        assertEquals(PodcastPipeline.md5Hash8(guid), PodcastPipeline.episodeId(e))
    }

    // ---------- episodeStablePrefix ----------

    @Test
    fun `episodeStablePrefix returns date-hash format`() {
        val date = utcDate("2024-03-14")
        val e = entry(publishedDate = date, guid = "https://example.com/guid/1")
        val result = PodcastPipeline.episodeStablePrefix(e)
        assertEquals("2024-03-14-${PodcastPipeline.episodeId(e)}", result)
    }

    @Test
    fun `episodeStablePrefix uses unknown-date when no date`() {
        val e = entry(guid = "https://example.com/guid/1")
        val result = PodcastPipeline.episodeStablePrefix(e)
        assertEquals("unknown-date-${PodcastPipeline.episodeId(e)}", result)
    }

    // ---------- getEpisodeDirName ----------

    @Test
    fun `getEpisodeDirName includes stable prefix and title`() {
        val date = utcDate("2024-03-14")
        val e = entry(title = "Hello", publishedDate = date, guid = "https://example.com/guid/1")
        val result = PodcastPipeline.getEpisodeDirName(e)
        assertEquals("2024-03-14-${PodcastPipeline.episodeId(e)}-Hello", result)
    }

    // ---------- findExistingEpisodeDir ----------

    @Test
    fun `findExistingEpisodeDir returns directory matching stable prefix`(
        @TempDir tempDir: Path,
    ) {
        val hash = PodcastPipeline.md5Hash8("https://cdn/ep.mp3")
        val existing = Files.createDirectories(tempDir.resolve("2024-03-14-$hash-Old-Title"))
        assertEquals(existing, PodcastPipeline.findExistingEpisodeDir(tempDir, "2024-03-14-$hash"))
    }

    @Test
    fun `findExistingEpisodeDir returns null when prefix does not match`(
        @TempDir tempDir: Path,
    ) {
        Files.createDirectories(tempDir.resolve("2024-03-14-aaaabbbb-Some-Title"))
        assertNull(PodcastPipeline.findExistingEpisodeDir(tempDir, "2024-03-14-ccccdddd"))
    }

    // ---------- getMp3Info ----------

    @Test
    fun `getMp3Info returns info from matching enclosure`() {
        val e =
            entry(
                enclosures =
                    listOf(
                        enclosure("https://cdn/ep1.mp3", "audio/mpeg", length = 12345L),
                    ),
            )
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")

        assertEquals("https://cdn/ep1.mp3", result?.url)
        assertEquals(12345L, result?.length)
        assertEquals(Path.of("/data/show/ep/audio.mp3"), result?.filePath)
        assertEquals("show/ep/audio.mp3", result?.localFilePath)
    }

    @Test
    fun `getMp3Info accepts audio mp3 type`() {
        val e = entry(enclosures = listOf(enclosure("https://cdn/ep.mp3", "audio/mp3")))
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")
        assertEquals("https://cdn/ep.mp3", result?.url)
    }

    @Test
    fun `getMp3Info skips non-audio enclosures`() {
        val e =
            entry(
                enclosures =
                    listOf(
                        enclosure("https://cdn/image.jpg", "image/jpeg"),
                        enclosure("https://cdn/video.mp4", "video/mp4"),
                    ),
            )
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")
        assertNull(result)
    }

    @Test
    fun `getMp3Info falls back to links when no matching enclosure`() {
        val e =
            entry(
                enclosures = listOf(enclosure("https://cdn/image.jpg", "image/jpeg")),
                links = listOf(link("https://cdn/ep.mp3", type = "audio/mpeg", length = 999L)),
            )
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")
        assertEquals("https://cdn/ep.mp3", result?.url)
        assertEquals(999L, result?.length)
    }

    @Test
    fun `getMp3Info returns null when nothing matches`() {
        val e = entry(links = listOf(link("https://page", type = "text/html")))
        assertNull(PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data"))
    }

    @Test
    fun `getMp3Info falls back to rel enclosure link with no type`() {
        // Same fallback buildEpisodeDict uses — the two must agree, or the
        // pipeline downloads an episode it then refuses to write json for.
        val e = entry(links = listOf(link("https://cdn/ep.mp3", rel = "enclosure", length = 42L)))
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")
        assertEquals("https://cdn/ep.mp3", result?.url)
        assertEquals(42L, result?.length)
    }

    @Test
    fun `getMp3Info ignores a typed non-audio enclosure link`() {
        // A link that declares itself video is not audio. Downloading it as
        // audio.mp3 would park an undecodable file on disk, and its presence
        // marks the download as done, so every later run would re-transcribe it.
        val e = entry(links = listOf(link("https://cdn/ep.mp4", type = "video/mp4", rel = "enclosure")))
        assertNull(PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data"))
    }

    @Test
    fun `getMp3Info prefers an mp3 link over an earlier untyped enclosure link`() {
        val e =
            entry(
                links =
                    listOf(
                        link("https://cdn/notes.html", rel = "enclosure"),
                        link("https://cdn/ep.mp3", type = "audio/mpeg", length = 99L),
                    ),
            )
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")
        assertEquals("https://cdn/ep.mp3", result?.url)
        assertEquals(99L, result?.length)
    }

    @Test
    fun `getMp3Info prefers enclosure over link when both match`() {
        val e =
            entry(
                enclosures = listOf(enclosure("https://cdn/from-enclosure.mp3", "audio/mpeg")),
                links = listOf(link("https://cdn/from-link.mp3", type = "audio/mpeg")),
            )
        val result = PodcastPipeline.getMp3Info(e, Path.of("/data/show/ep"), "/data")
        assertEquals("https://cdn/from-enclosure.mp3", result?.url)
    }

    // ---------- buildEpisodeDict ----------

    private fun feedWithItunes(
        title: String = "Podcast Title",
        link: String? = "https://example.com",
        language: String? = "en",
        copyright: String? = "(c) 2024",
        author: String? = null,
        imageUrl: String? = null,
        categories: List<String> = emptyList(),
        itunesAuthor: String? = null,
        itunesImageUri: String? = null,
        itunesType: String? = "episodic",
    ): SyndFeedImpl =
        SyndFeedImpl().apply {
            this.feedType = "rss_2.0"
            this.title = title
            this.link = link
            this.language = language
            this.copyright = copyright
            this.author = author
            if (imageUrl != null) {
                this.image =
                    SyndImageImpl().apply {
                        this.url = imageUrl
                    }
            }
            if (categories.isNotEmpty()) {
                this.categories = categories.map { SyndCategoryImpl().apply { name = it } }
            }
            val feedItunes =
                FeedInformationImpl().apply {
                    this.author = itunesAuthor
                    this.imageUri = itunesImageUri
                    this.type = itunesType
                }
            this.modules.add(feedItunes)
        }

    private fun entryWithItunes(
        title: String? = "Episode Title",
        description: String? = null,
        publishedDate: Date? = null,
        audioUrl: String? = "https://cdn/ep.mp3",
        categories: List<String> = emptyList(),
        authors: List<String> = emptyList(),
        itunes: EntryInformationImpl? = null,
        foreignImageHref: String? = null,
        extraForeignMarkup: List<Element> = emptyList(),
        guid: String? = "https://example.com/guid/default",
    ): SyndEntry =
        SyndEntryImpl().apply {
            this.title = title
            this.link = "https://example.com/ep"
            this.uri = guid
            if (description != null) {
                this.description =
                    SyndContentImpl().apply {
                        this.value = description
                    }
            }
            this.publishedDate = publishedDate
            this.enclosures =
                if (audioUrl != null) {
                    listOf(enclosure(audioUrl, "audio/mpeg"))
                } else {
                    emptyList()
                }
            if (categories.isNotEmpty()) {
                this.categories = categories.map { SyndCategoryImpl().apply { name = it } }
            }
            if (authors.isNotEmpty()) {
                this.authors = authors.map { SyndPersonImpl().apply { name = it } }
            }
            if (itunes != null) {
                this.modules.add(itunes)
            }
            val markup = mutableListOf<Element>()
            if (foreignImageHref != null) {
                markup += Element("image").apply { setAttribute(Attribute("href", foreignImageHref)) }
            }
            markup += extraForeignMarkup
            if (markup.isNotEmpty()) {
                this.foreignMarkup = markup
            }
        }

    /** The shape Libsyn publishes: a container, and a `pre` with no timestamp attribute at all. */
    private fun adMarkers(vararg markers: Triple<String, Int, Int?>): Element =
        Element("ad-markers", LIBSYN_NS).apply {
            markers.forEach { (type, count, timestamp) ->
                addContent(
                    Element("ad-marker", LIBSYN_NS).apply {
                        setAttribute("type", type)
                        setAttribute("count", count.toString())
                        if (timestamp != null) setAttribute("timestamp", timestamp.toString())
                    },
                )
            }
        }

    /** Attribute values exactly as written, for the malformed ones real feeds turn out to carry. */
    private fun rawAdMarkers(vararg markers: Pair<String, String>): Element =
        Element("ad-markers", LIBSYN_NS).apply {
            markers.forEach { (type, timestamp) ->
                addContent(
                    Element("ad-marker", LIBSYN_NS).apply {
                        setAttribute("type", type)
                        setAttribute("timestamp", timestamp)
                    },
                )
            }
        }

    private fun taggedMp3(
        dir: Path,
        vararg chapters: ByteArray,
    ): Path {
        val path = dir.resolve("audio.mp3")
        // Trailing bytes stand in for the audio, as a real file has after its tag.
        Files.write(path, Id3Builder.tag(3, chapters.toList()) + ByteArray(2048) { 0x55 })
        return path
    }

    @Test
    fun `buildEpisodeDict returns null when transcript is empty`() {
        val feed = feedWithItunes()
        val e = entryWithItunes()
        assertNull(PodcastPipeline.buildEpisodeDict(feed, e, ""))
    }

    @Test
    fun `buildEpisodeDict returns null when episode has no guid`() {
        val feed = feedWithItunes()
        val e = entryWithItunes(guid = null)
        assertNull(PodcastPipeline.buildEpisodeDict(feed, e, "some transcript"))
    }

    @Test
    fun `buildEpisodeDict returns null when there is no audio link`() {
        val feed = feedWithItunes()
        val e = entryWithItunes(audioUrl = null)
        assertNull(PodcastPipeline.buildEpisodeDict(feed, e, "some transcript"))
    }

    @Test
    fun `buildEpisodeDict populates top-level fields`() {
        val date = utcDate("2024-05-01")
        val itunes =
            EntryInformationImpl().apply {
                this.episode = 7
                this.season = 2
                this.episodeType = "full"
                this.subtitle = "A subtitle"
                this.summary = "iTunes summary"
                this.duration = Duration(90_000L) // 90 seconds
            }
        val feed =
            feedWithItunes(
                title = "Pod",
                link = "https://pod",
                language = "en-us",
                copyright = "c",
                imageUrl = "https://img/a.jpg",
                itunesAuthor = "Itunes Author",
                itunesType = "serial",
            )
        val guid = "https://example.com/guid/ep42"
        val e =
            entryWithItunes(
                title = "Ep",
                description = "The summary",
                publishedDate = date,
                audioUrl = "https://cdn/ep.mp3",
                authors = listOf("Alice", "Bob"),
                itunes = itunes,
                guid = guid,
            )

        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "transcript", listOf("col1"))!!

        assertEquals(PodcastPipeline.md5Hash8(guid), dict["_id"])
        assertEquals(listOf("col1"), dict["podcast_collections"])
        assertEquals("Pod", dict["podcast_title"])
        assertEquals("https://pod", dict["podcast_link"])
        assertEquals("en-us", dict["podcast_language"])
        assertEquals("c", dict["podcast_copyright"])
        assertEquals("Itunes Author", dict["podcast_author"]) // falls back to itunes
        assertEquals("https://img/a.jpg", dict["podcast_image"])
        assertEquals("serial", dict["podcast_type"])
        assertEquals("Ep", dict["episode_title"])
        assertEquals("2024-05-01", dict["episode_published_on"])
        assertEquals("https://cdn/ep.mp3", dict["episode_audio_link"])
        assertEquals("https://example.com/ep", dict["episode_web_link"])
        assertEquals("The summary", dict["episode_summary"]) // description preferred over itunes
        assertEquals("A subtitle", dict["episode_subtitle"])
        assertEquals(listOf("Alice", "Bob"), dict["episode_authors"])
        assertEquals(7, dict["episode_number"])
        assertEquals(2, dict["episode_season"])
        assertEquals("full", dict["episode_type"])
        assertEquals(90, dict["episode_duration"]) // 90000 / 1000
        assertEquals("transcript", dict["episode_transcript"])
        assertFalse(dict.containsKey("episode_relative_audio_path"))
    }

    @Test
    fun `buildEpisodeDict defaults collections to empty list`() {
        val feed = feedWithItunes()
        val e = entryWithItunes()
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t", collections = null)!!
        assertEquals(emptyList<String>(), dict["podcast_collections"])
    }

    @Test
    fun `buildEpisodeDict falls back to itunes summary when no description`() {
        val itunes = EntryInformationImpl().apply { this.summary = "from itunes" }
        val feed = feedWithItunes()
        val e = entryWithItunes(description = null, itunes = itunes)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        assertEquals("from itunes", dict["episode_summary"])
    }

    @Test
    fun `buildEpisodeDict falls back to itunes image uri when image module missing`() {
        val itunes = EntryInformationImpl().apply { this.imageUri = "https://itunes/img.jpg" }
        val feed = feedWithItunes(imageUrl = null, itunesImageUri = "https://feed-itunes.jpg")
        val e = entryWithItunes(itunes = itunes)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        assertEquals("https://feed-itunes.jpg", dict["podcast_image"])
        assertEquals("https://itunes/img.jpg", dict["episode_image"])
    }

    @Test
    fun `buildEpisodeDict uses foreign markup image when itunes image absent`() {
        val feed = feedWithItunes()
        val e = entryWithItunes(foreignImageHref = "https://fm/img.jpg")
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        assertEquals("https://fm/img.jpg", dict["episode_image"])
    }

    @Test
    fun `buildEpisodeDict collects and dedupes lowercased tags`() {
        val itunes = EntryInformationImpl().apply { this.keywords = arrayOf("Tech", "science") }
        val feed = feedWithItunes(categories = listOf("News", "TECH"))
        val e = entryWithItunes(categories = listOf("science", "History"), itunes = itunes)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!

        @Suppress("UNCHECKED_CAST")
        val tags = dict["all_tags"] as List<String>
        assertEquals(listOf("news", "tech", "science", "history"), tags)
    }

    @Test
    fun `buildEpisodeDict trims whitespace from tags before deduplication`() {
        val itunes = EntryInformationImpl().apply { this.keywords = arrayOf(" science", " technology") }
        val feed = feedWithItunes(categories = listOf("science", "technology"))
        val e = entryWithItunes(itunes = itunes)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!

        @Suppress("UNCHECKED_CAST")
        val tags = dict["all_tags"] as List<String>
        assertEquals(listOf("science", "technology"), tags)
    }

    @Test
    fun `buildEpisodeDict filters tags with 2 or fewer characters`() {
        val itunes = EntryInformationImpl().apply { this.keywords = arrayOf("ok", "good", "a", "ab") }
        val feed = feedWithItunes(categories = listOf("it", "tech"))
        val e = entryWithItunes(itunes = itunes)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!

        @Suppress("UNCHECKED_CAST")
        val tags = dict["all_tags"] as List<String>
        assertEquals(listOf("tech", "good"), tags)
    }

    @Test
    fun `buildEpisodeDict parses string duration fallback`() {
        // When milliseconds is 0, fall back to string parsing via timeToSeconds
        // Duration("01:30") means 1m30s -> 90 seconds; but its toString should contain ":"
        val itunes = EntryInformationImpl().apply { this.duration = Duration("01:30") }
        val feed = feedWithItunes()
        val e = entryWithItunes(itunes = itunes)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        // 01:30 = 90 seconds via timeToSeconds, OR ms>0 path. Either way, must be 90.
        assertEquals(90, (dict["episode_duration"] as Number).toInt())
    }

    @Test
    fun `buildEpisodeDict episode_duration is null when no itunes module`() {
        val feed = feedWithItunes()
        val e = entryWithItunes(itunes = null)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        assertNull(dict["episode_duration"])
    }

    @Test
    fun `buildEpisodeDict audio link falls back to enclosure rel link`() {
        val feed = feedWithItunes()
        val e =
            SyndEntryImpl().apply {
                title = "t"
                link = "https://example.com/ep"
                uri = "https://example.com/guid/enclosure-test"
                enclosures = emptyList()
                links = listOf(link("https://cdn/ep.mp3", rel = "enclosure"))
            }
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        assertEquals("https://cdn/ep.mp3", dict["episode_audio_link"])
    }

    @Test
    fun `buildEpisodeDict episode_published_on is null when no date`() {
        val feed = feedWithItunes()
        val e = entryWithItunes(publishedDate = null)
        val dict = PodcastPipeline.buildEpisodeDict(feed, e, "t")!!
        assertTrue(dict.containsKey("episode_published_on"))
        assertNull(dict["episode_published_on"])
    }

    @Test
    fun `buildEpisodeDict reads the audio's ID3 chapters, in seconds`(
        @TempDir dir: Path,
    ) {
        val audio =
            taggedMp3(
                dir,
                Id3Builder.chap("ch1", 0, 12_340, "Intro", 3),
                Id3Builder.chap("ch2", 12_340, 1_800_000, "Space Bites", 3),
            )

        val dict =
            PodcastPipeline.buildEpisodeDict(feedWithItunes(), entryWithItunes(), "t", audioPath = audio)!!

        assertEquals(
            listOf(
                mapOf("start_s" to 0.0, "end_s" to 12.34, "title" to "Intro"),
                mapOf("start_s" to 12.34, "end_s" to 1800.0, "title" to "Space Bites"),
            ),
            dict["episode_chapters"],
        )
    }

    @Test
    fun `buildEpisodeDict writes an empty chapter list for audio carrying none`(
        @TempDir dir: Path,
    ) {
        val audio = dir.resolve("audio.mp3")
        Files.write(audio, ByteArray(2048) { 0x55 })

        val dict =
            PodcastPipeline.buildEpisodeDict(feedWithItunes(), entryWithItunes(), "t", audioPath = audio)!!

        assertTrue(dict.containsKey("episode_chapters"))
        assertEquals(emptyList<Map<String, Any?>>(), dict["episode_chapters"])
    }

    @Test
    fun `buildEpisodeDict captures libsyn ad markers, a pre-roll's timestamp staying null`() {
        val entry =
            entryWithItunes(
                extraForeignMarkup =
                    listOf(adMarkers(Triple("pre", 2, null), Triple("mid", 2, 1244), Triple("post", 3, 13926))),
            )

        val dict = PodcastPipeline.buildEpisodeDict(feedWithItunes(), entry, "t")!!

        assertEquals(
            listOf(
                mapOf("type" to "pre", "count" to 2, "timestamp_publisher_s" to null),
                mapOf("type" to "mid", "count" to 2, "timestamp_publisher_s" to 1244.0),
                mapOf("type" to "post", "count" to 3, "timestamp_publisher_s" to 13926.0),
            ),
            dict["episode_ad_markers"],
        )
    }

    @Test
    fun `buildEpisodeDict writes an empty ad marker list for an entry carrying none`() {
        val dict = PodcastPipeline.buildEpisodeDict(feedWithItunes(), entryWithItunes(), "t")!!

        assertTrue(dict.containsKey("episode_ad_markers"))
        assertEquals(emptyList<Map<String, Any?>>(), dict["episode_ad_markers"])
    }

    @Test
    fun `buildEpisodeDict drops chapters whose times cannot be published`(
        @TempDir dir: Path,
    ) {
        val audio =
            taggedMp3(
                dir,
                // -1 writes 0xFFFFFFFF, the tag's "value unused" sentinel.
                Id3Builder.chap("ch1", 0, -1, "Unused end", 3),
                Id3Builder.chap("ch2", 9_000, 4_000, "Backwards", 3),
                Id3Builder.chap("ch3", 0, 5_000, "Real", 3),
            )

        val dict =
            PodcastPipeline.buildEpisodeDict(feedWithItunes(), entryWithItunes(), "t", audioPath = audio)!!

        assertEquals(listOf(mapOf("start_s" to 0.0, "end_s" to 5.0, "title" to "Real")), dict["episode_chapters"])
    }

    @Test
    fun `buildEpisodeDict writes null chapters when the audio could not be read at all`(
        @TempDir dir: Path,
    ) {
        val dict =
            PodcastPipeline.buildEpisodeDict(
                feedWithItunes(),
                entryWithItunes(),
                "t",
                audioPath = dir.resolve("never-written.mp3"),
            )!!

        assertTrue(dict.containsKey("episode_chapters"))
        assertNull(dict["episode_chapters"], "an empty list would claim the audio carries no chapters")
    }

    @Test
    fun `buildEpisodeDict drops an ad marker timestamp that would break the JSON`() {
        val entry =
            entryWithItunes(
                extraForeignMarkup =
                    listOf(rawAdMarkers("mid" to "NaN", "mid" to "Infinity", "mid" to "00:20:44", "mid" to "")),
            )

        val dict = PodcastPipeline.buildEpisodeDict(feedWithItunes(), entry, "t")!!

        @Suppress("UNCHECKED_CAST")
        val markers = dict["episode_ad_markers"] as List<Map<String, Any?>>
        assertEquals(4, markers.size)
        assertTrue(markers.all { it["timestamp_publisher_s"] == null })
        // Jackson writes a NaN as a bare literal its own reader then rejects, which is how a
        // transcript.json becomes unreadable to re-transcription.
        val mapper = ObjectMapper()
        assertNotNull(mapper.readTree(mapper.writeValueAsString(dict)))
    }

    @Test
    fun `buildEpisodeDict ignores an ad-marker element outside libsyn's namespace`() {
        val other = Namespace.getNamespace("ads", "https://example.com/ads")
        val entry =
            entryWithItunes(
                extraForeignMarkup =
                    listOf(
                        Element("ad-markers", other).apply {
                            addContent(Element("ad-marker", other).apply { setAttribute("type", "mid") })
                        },
                    ),
            )

        val dict = PodcastPipeline.buildEpisodeDict(feedWithItunes(), entry, "t")!!

        assertEquals(emptyList<Map<String, Any?>>(), dict["episode_ad_markers"])
    }

    private fun recoveredDir(dirName: String = "2019-01-01-deadbeef-An-Old-Episode") = EpisodeDirName.parse(dirName)!!

    @Test
    fun `buildRecoveredEpisodeDict keeps the audio's chapters, the feed's ad markers being gone`(
        @TempDir dir: Path,
    ) {
        val audio = taggedMp3(dir, Id3Builder.chap("ch1", 0, 5_000, "Advertisement", 3))

        val dict =
            PodcastPipeline.buildRecoveredEpisodeDict(
                feedWithItunes(),
                recoveredDir(),
                "t",
                null,
                audioPath = audio,
            )!!

        assertEquals(listOf(mapOf("start_s" to 0.0, "end_s" to 5.0, "title" to "Advertisement")), dict["episode_chapters"])
        assertNull(dict["episode_ad_markers"])
    }

    @Test
    fun `buildRecoveredEpisodeDict has the same keys as buildEpisodeDict plus the marker`() {
        val feed = feedWithItunes()
        val fromFeed = PodcastPipeline.buildEpisodeDict(feed, entryWithItunes(), "t")!!
        val recovered = PodcastPipeline.buildRecoveredEpisodeDict(feed, recoveredDir(), "t", null)!!

        assertEquals(fromFeed.keys + "episode_metadata_recovered", recovered.keys)
        assertEquals(27, recovered.size)
    }

    @Test
    fun `buildRecoveredEpisodeDict shares the podcast fields with buildEpisodeDict`() {
        val feed = feedWithItunes(author = "Someone", imageUrl = "https://img/pod.png")
        val fromFeed = PodcastPipeline.buildEpisodeDict(feed, entryWithItunes(), "t", listOf("science"))!!
        val recovered =
            PodcastPipeline.buildRecoveredEpisodeDict(feed, recoveredDir(), "t", null, listOf("science"))!!

        PodcastPipeline.podcastFields(feed, listOf("science")).keys.forEach { key ->
            assertEquals(fromFeed[key], recovered[key], key)
        }
    }

    @Test
    fun `buildRecoveredEpisodeDict recovers what the directory name carries`() {
        val dict =
            PodcastPipeline.buildRecoveredEpisodeDict(
                feedWithItunes(),
                recoveredDir("2019-01-01-deadbeef-An-Old-Episode"),
                "transcript text",
                1830,
            )!!

        assertEquals("deadbeef", dict["_id"])
        assertEquals("2019-01-01", dict["episode_published_on"])
        assertEquals("An Old Episode", dict["episode_title"])
        assertEquals(1830, dict["episode_duration"])
        assertEquals("transcript text", dict["episode_transcript"])
        assertFalse(dict.containsKey("episode_relative_audio_path"))
        assertEquals(true, dict["episode_metadata_recovered"])
    }

    @Test
    fun `buildRecoveredEpisodeDict nulls every field only the feed entry could supply`() {
        val dict = PodcastPipeline.buildRecoveredEpisodeDict(feedWithItunes(), recoveredDir(), "t", null)!!

        listOf(
            "episode_audio_link", "episode_web_link", "episode_image", "episode_summary",
            "episode_subtitle", "episode_authors", "episode_number", "episode_season", "episode_type",
            "episode_ad_markers",
        ).forEach { key ->
            assertTrue(dict.containsKey(key), "$key is missing")
            // Not emptyList() either: index.py turns that into "", which passes a null guard.
            assertNull(dict[key], key)
        }
    }

    @Test
    fun `buildRecoveredEpisodeDict title is null when the directory carries no title`() {
        val dict =
            PodcastPipeline.buildRecoveredEpisodeDict(feedWithItunes(), recoveredDir("2019-01-01-deadbeef-"), "t", null)!!
        assertNull(dict["episode_title"])
    }

    @Test
    fun `buildRecoveredEpisodeDict tags come from the feed only, normalised`() {
        val feed = feedWithItunes(categories = listOf("  Science  ", "SCIENCE", "History", "ok"))
        val dict = PodcastPipeline.buildRecoveredEpisodeDict(feed, recoveredDir(), "t", null)!!
        assertEquals(listOf("science", "history"), dict["all_tags"])
    }

    @Test
    fun `buildRecoveredEpisodeDict returns null for an empty transcript`() {
        assertNull(PodcastPipeline.buildRecoveredEpisodeDict(feedWithItunes(), recoveredDir(), "", null))
    }
}
