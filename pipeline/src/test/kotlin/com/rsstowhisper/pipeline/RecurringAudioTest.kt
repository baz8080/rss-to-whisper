package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Fingerprinter
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurringAudioTest {
    private fun random(
        size: Int,
        seed: Int,
    ): IntArray {
        val r = Random(seed)
        return IntArray(size) { r.nextInt() }
    }

    /** [values] with each bit flipped with probability [p]. */
    private fun noisy(
        values: IntArray,
        p: Double,
        seed: Int,
    ): IntArray {
        val r = Random(seed)
        return IntArray(values.size) { i ->
            var v = values[i]
            for (bit in 0 until 32) if (r.nextDouble() < p) v = v xor (1 shl bit)
            v
        }
    }

    private fun episode(
        size: Int,
        seed: Int,
        vararg placed: Pair<Int, IntArray>,
    ): IntArray {
        val e = random(size, seed)
        for ((at, sound) in placed) sound.copyInto(e, at)
        return e
    }

    private val theme = random(240, 1)

    @Test
    fun `a sound heard again is found at its offset`() {
        val a = episode(3000, 10, 500 to theme)
        val b = episode(4000, 11, 1700 to noisy(theme, 0.05, 2))
        val runs = RecurringAudio.shared(a, RecurringAudio.Index(b))
        assertEquals(1, runs.size)
        val run = runs.single()
        assertEquals(1200, run.offset)
        assertTrue(run.start in 495..505 && run.end in 735..745, "$run")
        assertTrue(run.ber < 0.1, "$run")
    }

    @Test
    fun `unrelated audio matches nothing`() {
        for (seed in 0 until 5) {
            assertEquals(emptyList(), RecurringAudio.shared(random(3000, 100 + seed), RecurringAudio.Index(random(3000, 200 + seed))))
        }
    }

    @Test
    fun `silence matches nothing`() {
        val silence = IntArray(3000) { 42 }
        assertEquals(emptyList(), RecurringAudio.runs(silence, silence.copyOf(), 0))
    }

    @Test
    fun `speech over the music ends the match`() {
        val voiced = theme.copyOf()
        noisy(theme.copyOfRange(120, 240), 0.4, 3).copyInto(voiced, 120)
        val a = episode(2000, 12, 300 to theme)
        val b = episode(2000, 13, 300 to voiced)
        val run = RecurringAudio.shared(a, RecurringAudio.Index(b)).single()
        assertTrue(run.start in 295..305, "$run")
        assertTrue(run.end in 405..420, "the match runs into the voiced half: $run")
    }

    @Test
    fun `a sound only partly there is matched where it is`() {
        val a = episode(2000, 14, 300 to theme)
        val b = episode(2000, 15, 0 to theme.copyOfRange(100, 240))
        val run = RecurringAudio.shared(a, RecurringAudio.Index(b)).single()
        assertEquals(-400, run.offset)
        assertTrue(run.start in 395..405 && run.end in 535..545, "$run")
    }

    @Test
    fun `a sound played twice is found twice`() {
        val a = episode(3000, 16, 200 to theme)
        val b = episode(5000, 17, 300 to theme, 4000 to theme)
        val runs = RecurringAudio.alignments(a, b).flatMap { RecurringAudio.runs(a, b, it) }
        assertEquals(setOf(100, 3800), runs.map { it.offset }.toSet())
    }

    @Test
    fun `discovery groups each sound's occurrences across episodes`() {
        val jingle = random(60, 4)
        val episodes =
            (0 until 6).map { n ->
                val placed = mutableListOf(100 + 37 * n to noisy(theme, 0.03, 50 + n))
                if (n % 2 == 0) placed += 1500 + 11 * n to jingle
                "ep$n" to episode(2400, 60 + n, *placed.toTypedArray())
            }
        val clusters = RecurringAudio.discover(episodes, anchors = 3)
        assertEquals(listOf(6, 3), clusters.map { it.episodes })
        val themeStarts = clusters[0].occurrences.associate { it.episode to it.start }
        for (n in 0 until 6) assertTrue(kotlin.math.abs(themeStarts.getValue("ep$n") - (100 + 37 * n)) <= 5, "$themeStarts")
    }

    @Test
    fun `an ad played just before the theme does not join the theme to other ads`() {
        val (adA, adB) = random(200, 5) to random(200, 6)
        val episodes =
            listOf(
                "a1" to episode(2000, 70, 100 to adA, 300 to theme),
                "a2" to episode(2000, 71, 100 to adA, 300 to theme),
                "b1" to episode(2000, 72, 100 to adB, 300 to theme),
                "b2" to episode(2000, 73, 100 to adB, 300 to theme),
                "t1" to episode(2000, 74, 600 to theme),
                "t2" to episode(2000, 75, 900 to theme),
            )
        val clusters = RecurringAudio.discover(episodes, anchors = 6)
        val ads = clusters.filter { c -> c.occurrences.all { it.start < 250 } }
        assertEquals(
            listOf(setOf("a1", "a2"), setOf("b1", "b2")),
            ads.map {
                    c ->
                c.occurrences.map { it.episode }.toSet()
            }.sortedBy { it.first() },
        )
        assertEquals(6, clusters.first().episodes)
    }

    @Test
    fun `a theme cut short in some episodes is still one sound`() {
        val episodes =
            (0 until 8).map { n ->
                val heard = if (n == 0) theme.copyOfRange(0, 120) else theme
                "ep$n" to episode(2400, 80 + n, 200 + 13 * n to heard)
            }
        val clusters = RecurringAudio.discover(episodes, anchors = 8)
        assertEquals(1, clusters.size, "$clusters")
        val full = clusters.single().occurrences.first { it.episode == "ep5" }
        assertTrue(full.end - full.start >= 230, "$full")
    }

    @Test
    fun `a run's window allows for the audio each value hears`() {
        val w = RecurringAudio.window(100, 300)
        assertEquals(100 * Fingerprinter.HOP + 1.0, w.start, 1e-9)
        assertEquals(300 * Fingerprinter.HOP + 0.75, w.end, 1e-9)
        assertEquals(0.0, RecurringAudio.window(0, 80).start)
    }

    @Test
    fun `a reference cut from an episode's values finds itself there again`() {
        val episode = episode(3000, 90, 800 to theme)
        val range = RecurringAudio.values(RecurringAudio.window(800, 1040).start, RecurringAudio.window(800, 1040).end)
        assertEquals(800 until 1040, range)
        val found = RecurringAudio.matches(episode.copyOfRange(range.first, range.last + 1), episode(4000, 91, 2500 to theme))
        assertEquals(1, found.size)
        assertEquals(RecurringAudio.window(2500, 2740).start, found.single().start, 0.2)
    }
}
