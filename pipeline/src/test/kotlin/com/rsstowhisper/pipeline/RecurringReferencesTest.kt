package com.rsstowhisper.pipeline

import com.rsstowhisper.PodcastConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurringReferencesTest {
    private val theme = IntArray(40) { it * 7919 - Int.MAX_VALUE / 2 }

    private fun ref(
        id: String,
        kind: Reference.Kind = Reference.Kind.INTRO,
        values: IntArray = theme,
    ) = Reference(id, kind, "2020-01-01-abcd1234-Ep", 10.0, 15.0, values)

    @Test
    fun `references read back as written, values above 2^31 included`(
        @TempDir dir: Path,
    ) {
        val values = theme.copyOf().also { it[0] = 4293918828L.toInt() }
        RecurringReferences.add(dir, "Shite-Talk", "Shite Talk", listOf(ref("intro-1", values = values), ref("ad-3", Reference.Kind.AD)))
        val refs = RecurringReferences.load(dir).of(PodcastConfig(name = "Shite Talk", url = "u"))
        assertEquals(listOf("intro-1", "ad-3"), refs.map { it.id })
        assertContentEquals(values, refs[0].fingerprint)
        assertTrue(refs[0].kind.marks)
        assertTrue(!refs[1].kind.marks)
    }

    @Test
    fun `an id already in the file is refused`(
        @TempDir dir: Path,
    ) {
        RecurringReferences.add(dir, "Show", "Show", listOf(ref("intro-1")))
        assertThrows<IllegalStateException> { RecurringReferences.add(dir, "Show", "Show", listOf(ref("intro-1"))) }
    }

    @Test
    fun `a file with an unknown kind or too few values is refused, not skipped`(
        @TempDir dir: Path,
    ) {
        RecurringReferences.add(dir, "Show", "Show", listOf(ref("intro-1")))
        val file = dir.resolve("Show.json")
        Files.writeString(file, Files.readString(file).replace("\"intro\"", "\"jingle\""))
        assertThrows<IllegalStateException> { RecurringReferences.load(dir) }
        RecurringReferences.add(dir, "Other", "Other", listOf(ref("intro-2", values = theme.copyOf(8))))
        Files.delete(file)
        assertThrows<IllegalArgumentException> { RecurringReferences.load(dir) }
    }

    @Test
    fun `a missing folder holds no references`(
        @TempDir dir: Path,
    ) {
        assertEquals(0, RecurringReferences.load(dir.resolve("none")).size)
    }

    @Test
    fun `a reference without a note keeps none through another write`(
        @TempDir dir: Path,
    ) {
        RecurringReferences.add(dir, "Show", "Show", listOf(ref("intro-1")))
        RecurringReferences.add(dir, "Show", "Show", listOf(ref("outro-2", Reference.Kind.OUTRO)))
        val refs = RecurringReferences.load(dir).of(PodcastConfig(name = "Show", url = "u"))
        assertEquals(listOf(null, null), refs.map { it.note })
    }

    @Test
    fun `an id twice in one batch is refused before anything is written`(
        @TempDir dir: Path,
    ) {
        assertThrows<IllegalStateException> { RecurringReferences.add(dir, "Show", "Show", listOf(ref("intro-1"), ref("intro-1"))) }
        assertTrue(!Files.exists(dir.resolve("Show.json")))
    }

    @Test
    fun `a cut outside the episode or too short is null`() {
        val episode = IntArray(1000) { it }
        val inside = RecurringAudio.window(100, 200)
        assertEquals(100, RecurringReferences.cut(episode, inside.start, inside.end)?.size)
        assertEquals(null, RecurringReferences.cut(episode, 0.5, 20.0))
        assertEquals(null, RecurringReferences.cut(episode, 20.0, 20.1))
        assertEquals(null, RecurringReferences.cut(episode, 110.0, 130.0))
    }

    @Test
    fun `a labels row that cannot be read is reported, and the rest are kept`() {
        val header = "entry\tshow\tcandidate\tlabel\tsource_episode\tsource_from\tsource_to\tnote"
        val (rows, problems) =
            RecurringReferences.parseLabels(
                listOf(
                    header,
                    "1\tShite-Talk\t1\tI\tep\t162.8\t191.5\t",
                    "2\tShite-Talk\t3\tA\tep\t1:23\t191.5\tbad time",
                    "3\tShite-Talk\t4",
                    "4\tShite-Talk\t5\tO\tep\t10\t20\ta note",
                ),
            )
        assertEquals(listOf("1", "5"), rows.map { it.candidate })
        assertEquals(listOf(null, "a note"), rows.map { it.note })
        assertEquals(listOf("row 3", "row 4"), problems.map { it.substringBefore(" has") })
        assertEquals(listOf("no header row"), RecurringReferences.parseLabels(emptyList()).second)
        assertTrue(RecurringReferences.parseLabels(listOf("show\tlabel")).second.single().startsWith("no candidate"))
    }
}
