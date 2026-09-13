package com.rsstowhisper.web

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream

/**
 * The route over HTTP rather than as a method call.
 *
 * `@Context Request`, the gzip headers and the conditional 304 all live above
 * the method boundary the other tests here stop at, so none of them is covered
 * by calling `episodeWords` directly on a hand-built resource. This is an
 * end-to-end smoke test of that wiring, not a net under the path rules -- those
 * are pinned in SearchResourceTest.
 */
@QuarkusTest
@TestProfile(WordsRouteHttpTest.Fixture::class)
class WordsRouteHttpTest {
    class Fixture : QuarkusTestProfile {
        // Quarkus asks for the overrides more than once. Built once and handed
        // back each time, so a second tree is not made and then orphaned.
        override fun getConfigOverrides(): Map<String, String> = overrides

        companion object {
            private val overrides: Map<String, String> by lazy { build() }

            private fun build(): Map<String, String> {
                val root = Files.createTempDirectory("words-http")
                Runtime.getRuntime().addShutdownHook(Thread { deleteTree(root) })

                val episodeDir = root.resolve("data").resolve("Show").resolve("ep")
                Files.createDirectories(episodeDir)
                Files.write(episodeDir.resolve("words.jsonl.gz"), gzip(NDJSON))

                val db = root.resolve("podcasts.db")
                // Asked for by name: the profile runs before the app's
                // classloader is in place, so DriverManager has not found it.
                Class.forName("org.sqlite.JDBC")
                DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
                    conn.createStatement().use {
                        it.execute(
                            """CREATE TABLE episodes (
                                id TEXT PRIMARY KEY,
                                podcast_title TEXT, podcast_link TEXT, podcast_language TEXT,
                                podcast_copyright TEXT, podcast_author TEXT, podcast_image TEXT,
                                podcast_type TEXT, podcast_collections TEXT,
                                episode_title TEXT, episode_published_on TEXT,
                                episode_audio_link TEXT, episode_web_link TEXT,
                                episode_image TEXT, episode_summary TEXT, episode_subtitle TEXT,
                                episode_authors TEXT, episode_number INTEGER, episode_season INTEGER,
                                episode_type TEXT, episode_duration INTEGER,
                                episode_transcript TEXT, episode_transcript_plain TEXT,
                                episode_relative_audio_path TEXT, all_tags TEXT
                            )""",
                        )
                        it.execute(
                            """CREATE VIRTUAL TABLE episodes_fts USING fts5(
                                episode_title, episode_transcript_plain, podcast_title, all_tags,
                                content='episodes', content_rowid='rowid'
                            )""",
                        )
                        it.execute(
                            "INSERT INTO episodes (id, episode_title, episode_relative_audio_path, " +
                                "episode_transcript) VALUES ('ep1', 'One', 'Show/ep/audio.mp3', '')",
                        )
                    }
                }
                return mapOf(
                    "app.data.directory" to root.resolve("data").toString(),
                    "app.db.path" to db.toString(),
                )
            }

            private fun deleteTree(root: Path) {
                if (!Files.exists(root)) return
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { it.toFile().delete() }
                }
            }
        }
    }

    /**
     * The client inflates it, which is the point: the file is gzip on disk and
     * goes out untouched under a Content-Encoding that says so.
     */
    @Test
    fun `serves the sidecar as gzip the client can inflate`() {
        val body =
            given().get("/episode/ep1/words")
                .then()
                .statusCode(200)
                .header("Content-Type", "application/x-ndjson")
                .header("Content-Encoding", "gzip")
                .header("Cache-Control", "no-cache, no-transform")
                .extract().asString()

        assertEquals(NDJSON, body)
    }

    /** Strong and quoted, so a browser echoing it back gets a 304. */
    @Test
    fun `answers a matching entity tag with 304 and a stale one with the body`() {
        val tag =
            given().get("/episode/ep1/words")
                .then().statusCode(200)
                .extract().header("ETag")

        assertEquals(true, Regex("""^"[0-9a-f]{32}"$""").matches(tag), "unexpected ETag: $tag")

        given().header("If-None-Match", tag).get("/episode/ep1/words")
            .then().statusCode(304)

        given().header("If-None-Match", "\"not-the-tag\"").get("/episode/ep1/words")
            .then().statusCode(200)
    }

    @Test
    fun `is 404 for an episode that is not there`() {
        assertEquals(404, given().get("/episode/nope/words").then().extract().statusCode())
    }

    companion object {
        private const val NDJSON =
            """{"w":" one","s":0.0,"e":0.4,"p":0.9,"seg":0}
{"w":" two","s":0.5,"e":0.9,"p":0.2,"seg":0}
"""

        private fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray()) }
            return out.toByteArray()
        }
    }
}
