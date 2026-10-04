package com.rsstowhisper.pipeline

import com.rsstowhisper.external.Fingerprinter.Companion.HOP
import com.rsstowhisper.external.TimeWindow

/** Audio heard again across a show's episodes -- a theme, a jingle, a promo -- found by its Chromaprint fingerprint. */
internal object RecurringAudio {
    /** Values `[start, end)` of `a` heard again at `start + offset` of `b`, with the bit error rate over them. */
    data class Run(
        val start: Int,
        val end: Int,
        val offset: Int,
        val ber: Double,
    ) {
        val length: Int get() = end - start
    }

    /** One episode's stretch of a recurring sound, in fingerprint values `[start, end)`. */
    data class Occurrence(
        val episode: String,
        val start: Int,
        val end: Int,
    ) {
        val window: TimeWindow get() = window(start, end)
    }

    data class Cluster(
        val occurrences: List<Occurrence>,
        /** The occurrence matched by the most others: the one to cut a reference from. */
        val representative: Occurrence,
        val ber: Double,
    ) {
        val episodes: Int get() = occurrences.map { it.episode }.distinct().size
    }

    /** Offsets of `b` against `a` that many identical values agree on, best first. */
    fun alignments(
        a: IntArray,
        b: IntArray,
    ): List<Int> = alignments(a, Index(b))

    /** [b] indexed once, to align many episodes against it. */
    class Index(val values: IntArray) {
        internal val positions: Map<Int, IntArray>

        init {
            val counts = HashMap<Int, Int>()
            values.forEach { counts.merge(it, 1, Int::plus) }
            val byValue = HashMap<Int, MutableList<Int>>()
            values.forEachIndexed { i, v -> if (counts.getValue(v) <= MAX_REPEATS) byValue.getOrPut(v) { mutableListOf() } += i }
            positions = byValue.mapValues { it.value.toIntArray() }
        }
    }

    fun alignments(
        a: IntArray,
        b: Index,
    ): List<Int> {
        val counts = HashMap<Int, Int>()
        a.forEach { counts.merge(it, 1, Int::plus) }
        val votes = HashMap<Int, Int>()
        a.forEachIndexed { i, v ->
            // Silence and held notes repeat one value, and would vote for every offset at once.
            if (counts.getValue(v) > MAX_REPEATS) return@forEachIndexed
            b.positions[v]?.forEach { j -> votes.merge(j - i, 1, Int::plus) }
        }
        // Two encodes of one master can sit a fraction of a value apart, splitting the votes between neighbours.
        val pooled = votes.keys.associateWith { d -> (-1..1).sumOf { votes[d + it] ?: 0 } }
        val chosen = mutableListOf<Int>()
        for ((d, n) in pooled.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })) {
            if (n < MIN_VOTES || chosen.size == MAX_ALIGNMENTS) break
            if (chosen.none { kotlin.math.abs(it - d) <= 1 }) chosen += (-1..1).map { d + it }.maxBy { votes[it] ?: 0 }
        }
        return chosen
    }

    /**
     * Where `a` and `b` stay alike at [offset]: the bit error rate, averaged over [SMOOTHING] values, at most [MAX_BER]
     * for at least [minLength] values. Speech over the music raises the rate and ends the run.
     */
    fun runs(
        a: IntArray,
        b: IntArray,
        offset: Int,
        minLength: Int = MIN_RUN,
    ): List<Run> {
        val from = maxOf(0, -offset)
        val to = minOf(a.size, b.size - offset)
        if (to - from < minLength) return emptyList()
        val errors = IntArray(to - from) { Integer.bitCount(a[from + it] xor b[from + it + offset]) }
        val sums = LongArray(errors.size + 1)
        errors.forEachIndexed { i, e -> sums[i + 1] = sums[i] + e }

        fun rate(
            s: Int,
            e: Int,
        ) = (sums[e] - sums[s]).toDouble() / (32 * (e - s))
        val half = SMOOTHING / 2
        val runs = mutableListOf<Run>()
        var start = -1
        for (k in 0..errors.size) {
            val alike = k < errors.size && rate(maxOf(0, k - half), minOf(errors.size, k + half)) <= MAX_BER
            if (alike && start < 0) start = k
            if (!alike && start >= 0) {
                // The average reaches a few values past where the sounds part; each end stops at a value that still matches.
                var (s, e) = start to k
                while (s < e && errors[s] > MAX_BER * 32) s++
                while (e > s && errors[e - 1] > MAX_BER * 32) e--
                if (e - s >= minLength && varied(a, from + s, from + e)) runs += Run(from + s, from + e, offset, rate(s, e))
                start = -1
            }
        }
        return runs
    }

    /** A run of one held value is silence or a tone, alike in any two files. */
    private fun varied(
        values: IntArray,
        start: Int,
        end: Int,
    ): Boolean = (start until end).map { values[it] }.distinct().size * 2 >= end - start

    /** Every run of `a` found again in `b`, at any offset. Runs that overlap in `a` keep the one with the fewest errors. */
    fun shared(
        a: IntArray,
        b: Index,
        minLength: Int = MIN_RUN,
    ): List<Run> {
        val found = alignments(a, b).flatMap { runs(a, b.values, it, minLength) }.sortedBy { it.ber }
        val kept = mutableListOf<Run>()
        for (run in found) {
            if (kept.none { it.start < run.end && run.start < it.end }) kept += run
        }
        return kept.sortedBy { it.start }
    }

    /**
     * Sounds heard in several of [episodes]: every episode is compared with [anchors] of them spread through the list,
     * so the cost grows with the number of episodes rather than its square. Ordered by how many episodes have each.
     */
    fun discover(
        episodes: List<Pair<String, IntArray>>,
        anchors: Int = ANCHORS,
        minLength: Int = MIN_RUN,
    ): List<Cluster> {
        val picked =
            if (episodes.size <= anchors) {
                episodes.indices.toList()
            } else {
                (0 until anchors).map { it * (episodes.size - 1) / (anchors - 1) }.distinct()
            }
        val found = mutableListOf<Pair<Int, Int>>()
        val runs = mutableListOf<Run>()
        for (anchor in picked) {
            val index = Index(episodes[anchor].second)
            for ((i, episode) in episodes.withIndex()) {
                if (i == anchor) continue
                for (run in shared(episode.second, index, minLength)) {
                    found += i to anchor
                    runs += run
                }
            }
        }
        val (pairs, pieces) = split(found, runs, minLength / 2)
        val occurrences = mutableListOf<Occurrence>()
        val links = mutableListOf<Pair<Int, Int>>()
        val bers = mutableListOf<Double>()
        for ((pair, run) in pairs.zip(pieces)) {
            occurrences += Occurrence(episodes[pair.first].first, run.start, run.end)
            occurrences += Occurrence(episodes[pair.second].first, run.start + run.offset, run.end + run.offset)
            links += occurrences.size - 2 to occurrences.size - 1
            bers += run.ber
        }
        val groups = UnionFind(occurrences.size)
        links.forEach { (x, y) -> groups.join(x, y) }
        // One stretch found from several anchors is one occurrence.
        for (byEpisode in occurrences.indices.groupBy { occurrences[it].episode }.values) {
            val sorted = byEpisode.sortedBy { occurrences[it].start }
            for ((n, x) in sorted.withIndex()) {
                for (y in sorted.drop(n + 1)) {
                    val (p, q) = occurrences[x] to occurrences[y]
                    if (q.start >= p.end) break
                    val overlap = minOf(p.end, q.end) - q.start
                    if (overlap * 2 >= maxOf(p.end - p.start, q.end - q.start)) groups.join(x, y)
                }
            }
        }
        val degree = IntArray(occurrences.size)
        links.forEach { (x, y) ->
            degree[x]++
            degree[y]++
        }
        val linkBer = links.indices.groupBy { groups.find(links[it].first) }
        val clusters =
            occurrences.indices
                .groupBy { groups.find(it) }
                .map { (root, members) ->
                    Cluster(
                        occurrences = merged(members.map { occurrences[it] }),
                        representative = occurrences[members.maxWith(compareBy<Int> { degree[it] }.thenBy { -it })],
                        ber = linkBer.getValue(root).map { bers[it] }.average(),
                    )
                }
        return joined(
            clusters,
            minLength / 2,
        ).sortedWith(compareByDescending<Cluster> { it.episodes }.thenBy { it.occurrences.first().start })
    }

    /** Pieces of one sound that [split] cut apart, where a sound heard in only some episodes ended: back to back in nearly the same episodes. */
    private fun joined(
        clusters: List<Cluster>,
        gap: Int,
    ): List<Cluster> {
        val out = clusters.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            search@ for (i in out.indices) {
                for (j in out.indices) {
                    if (i == j) continue
                    val (x, y) = out[i] to out[j]
                    val ends = x.occurrences.groupBy { it.episode }
                    val adjacent = y.occurrences.filter { o -> ends[o.episode]?.any { kotlin.math.abs(it.end - o.start) <= gap } == true }
                    if (adjacent.map { it.episode }.distinct().size < JOIN_SHARE * maxOf(x.episodes, y.episodes)) continue
                    val all = merged(x.occurrences + y.occurrences, gap)
                    val rep = x.representative
                    out[i] =
                        Cluster(
                            occurrences = all,
                            representative = all.first { it.episode == rep.episode && it.start <= rep.start && rep.start < it.end },
                            ber = (x.ber * x.occurrences.size + y.ber * y.occurrences.size) / (x.occurrences.size + y.occurrences.size),
                        )
                    out.removeAt(j)
                    changed = true
                    break@search
                }
            }
        }
        return out
    }

    /**
     * Each run cut wherever another run in either of its episodes starts or ends, so that a run over two sounds back
     * to back -- an ad, then the theme -- becomes one piece per sound. Cuts within [minPiece] of an end are ignored.
     */
    private fun split(
        pairs: List<Pair<Int, Int>>,
        runs: List<Run>,
        minPiece: Int,
    ): Pair<List<Pair<Int, Int>>, List<Run>> {
        var (ps, rs) = pairs to runs
        repeat(MAX_SPLIT_ROUNDS) {
            val edges = HashMap<Int, MutableSet<Int>>()
            for ((pair, run) in ps.zip(rs)) {
                edges.getOrPut(pair.first) { sortedSetOf() } += listOf(run.start, run.end)
                edges.getOrPut(pair.second) { sortedSetOf() } += listOf(run.start + run.offset, run.end + run.offset)
            }
            val nextPairs = mutableListOf<Pair<Int, Int>>()
            val nextRuns = mutableListOf<Run>()
            var cut = false
            for ((pair, run) in ps.zip(rs)) {
                val inside =
                    (edges.getValue(pair.first) + edges.getValue(pair.second).map { it - run.offset })
                        .filter { it >= run.start + minPiece && it <= run.end - minPiece }
                        .sorted()
                val points = mutableListOf(run.start)
                inside.forEach { if (it - points.last() >= minPiece) points += it }
                if (run.end - points.last() < minPiece && points.size > 1) points.removeAt(points.size - 1)
                points += run.end
                if (points.size > 2) cut = true
                for (k in 0 until points.size - 1) {
                    nextPairs += pair
                    nextRuns += run.copy(start = points[k], end = points[k + 1])
                }
            }
            ps = nextPairs
            rs = nextRuns
            if (!cut) return ps to rs
        }
        return ps to rs
    }

    /** Each episode's overlapping stretches as one, spanning them all; with [gap], stretches that far apart too. */
    private fun merged(
        occurrences: List<Occurrence>,
        gap: Int = -1,
    ): List<Occurrence> =
        occurrences.groupBy { it.episode }.flatMap { (episode, list) ->
            val out = mutableListOf<Occurrence>()
            for (o in list.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && o.start <= last.end + gap) {
                    out[out.size - 1] = Occurrence(episode, last.start, maxOf(last.end, o.end))
                } else {
                    out += o
                }
            }
            out
        }.sortedWith(compareBy({ it.episode }, { it.start }))

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var node = x
            while (parent[node] != root) node = parent[node].also { parent[node] = root }
            return root
        }

        fun join(
            x: Int,
            y: Int,
        ) {
            parent[find(x)] = find(y)
        }
    }

    /**
     * Values `[start, end)` as the audio they match. A value hears about 2 s from its own time, so matching values start
     * up to 1.2 s before a shared sound and stop 0.75-0.85 s before its end (measured on splices of known times).
     */
    fun window(
        start: Int,
        end: Int,
    ): TimeWindow {
        // At the file's first value nothing came before the sound to be heard with it.
        val from = if (start == 0) 0.0 else start * HOP + START_SHIFT_SECONDS
        return TimeWindow(from, maxOf(from, end * HOP + END_SHIFT_SECONDS))
    }

    /** The values whose [window] is `[from, to]`: the inverse, to cut a reference out of an episode. */
    fun values(
        from: Double,
        to: Double,
    ): IntRange {
        val start = if (from <= 0.0) 0 else Math.round((from - START_SHIFT_SECONDS) / HOP).toInt()
        return start until Math.round((to - END_SHIFT_SECONDS) / HOP).toInt()
    }

    /** Where [reference] is heard in [episode]; a part of it heard, where speech over it ends the match, counts. */
    fun matches(
        reference: IntArray,
        episode: IntArray,
    ): List<TimeWindow> =
        shared(episode, Index(reference), minOf(MIN_RUN, reference.size * 3 / 4))
            .map { window(it.start, it.end) }

    private const val START_SHIFT_SECONDS = 1.0
    private const val END_SHIFT_SECONDS = 0.75

    /** About 4 s: the shortest jingle worth naming. */
    const val MIN_RUN = 32
    const val ANCHORS = 16

    /** Unrelated audio differs in half its bits; one master encoded twice, in under a tenth. */
    const val MAX_BER = 0.25
    const val SMOOTHING = 16
    private const val MIN_VOTES = 8
    private const val MAX_REPEATS = 20
    private const val MAX_ALIGNMENTS = 10
    private const val MAX_SPLIT_ROUNDS = 5
    private const val JOIN_SHARE = 0.8
}
