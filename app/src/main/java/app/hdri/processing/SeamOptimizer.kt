package app.hdri.processing

import kotlin.math.*

internal data class SeamLayer(
    val rgb: FloatArray,
    val weights: FloatArray,
    val radii: FloatArray = FloatArray(weights.size),
)

/** Binary label swaps minimize disagreement along an entire boundary, including longitude wrap. */
internal object SeamOptimizer {
    fun optimize(
        layers: List<SeamLayer>,
        labels: IntArray,
        w: Int,
        h: Int,
        progress: (Double) -> Unit,
        check: () -> Unit,
    ) {
        val n = w * h
        val ids = IntArray(n) { -1 }
        // Only the active pair needs a gradient cache. Retaining one full map
        // per photograph unnecessarily grows the live heap during graph cuts.
        val gradientA = FloatArray(n)
        val gradientB = FloatArray(n)
        var activeA = -1
        var activeB = -1
        fun gradient(p: Int, label: Int): Float {
            val cache =
                when (label) {
                    activeA -> gradientA
                    activeB -> gradientB
                    else -> null
                }
            if (cache != null && cache[p] >= 0f) return cache[p]
            val l = layers[label]
            val right = p / w * w + (p % w + 1) % w
            val down = min(n - 1, p + w)
            var value = 0f
            for (c in 0..2) value +=
                abs(l.rgb[p * 3 + c] - l.rgb[right * 3 + c]) +
                    abs(l.rgb[p * 3 + c] - l.rgb[down * 3 + c])
            val result = min(4f, value / 3)
            if (cache != null) cache[p] = result
            return result
        }
        fun difference(p: Int, a: Int, b: Int): Float {
            var value = .015f + .75f * max(gradient(p, a), gradient(p, b))
            for (c in 0..2) value += abs(layers[a].rgb[p * 3 + c] - layers[b].rgb[p * 3 + c])
            return min(value, 12f)
        }
        fun boundary(p: Int, q: Int, a: Int, b: Int) =
            if (a == b) 0f else (difference(p, a, b) + difference(q, a, b)) * .5f
        repeat(2) { pass ->
            val pairs = sortedSetOf<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val p = y * w + x
                val a = labels[p]
                if (a < 0) continue
                for (q in intArrayOf(y * w + (x + 1) % w, if (y + 1 < h) p + w else p)) {
                    val b = labels[q]
                    if (b >= 0 && b != a) pairs += min(a, b) * layers.size + max(a, b)
                }
            }
            pairs.forEachIndexed { index, pair ->
                check()
                progress((pass + index.toDouble() / max(1, pairs.size)) / 2)
                val a = pair / layers.size
                val b = pair % layers.size
                activeA = a
                activeB = b
                gradientA.fill(-1f)
                gradientB.fill(-1f)
                val la = layers[a]
                val lb = layers[b]
                val pixels = IntArray(n)
                var size = 0
                for (p in 0 until n) if (labels[p] == a || labels[p] == b) {
                    ids[p] = size
                    pixels[size++] = p
                }
                if (size == 0) return@forEachIndexed
                val graph = CutGraph(size + 2, size * 12 + 8)
                val source = size
                val sink = size + 1
                for (i in 0 until size) {
                    val p = pixels[i]
                    val x = p % w
                    val y = p / w
                    val wa = la.weights[p]
                    val wb = lb.weights[p]
                    var ca = if (wa > .00001f) (-.12 * ln(wa.toDouble())).toFloat() else 10000f
                    var cb = if (wb > .00001f) (-.12 * ln(wb.toDouble())).toFloat() else 10000f
                    // Swapping a/b must include edges to fixed third labels. Omitting
                    // these unaries can improve one seam while tearing an adjacent one.
                    for (q in
                        intArrayOf(
                            y * w + (x + 1) % w,
                            y * w + (x + w - 1) % w,
                            if (y > 0) p - w else -1,
                            if (y + 1 < h) p + w else -1,
                        )) {
                        if (q < 0 || ids[q] >= 0 || labels[q] < 0) continue
                        ca += boundary(p, q, a, labels[q])
                        cb += boundary(p, q, b, labels[q])
                    }
                    graph.edge(source, i, cb, 0f)
                    graph.edge(i, sink, ca, 0f)
                    for (q in intArrayOf(y * w + (x + 1) % w, if (y + 1 < h) p + w else -1)) {
                        if (q < 0 || ids[q] < 0) continue
                        val cost = boundary(p, q, a, b)
                        graph.edge(i, ids[q], cost, cost)
                    }
                }
                val side = graph.cut(source, sink, check)
                for (i in 0 until size) {
                    labels[pixels[i]] = if (side[i]) a else b
                    ids[pixels[i]] = -1
                }
            }
        }
    }

    /** Dinic with an explicit path stack: Android thread stacks cannot hold recursive grid DFS. */
    private class CutGraph(val n: Int, edgeCapacity: Int) {
        private val head = IntArray(n) { -1 }
        private val to = IntArray(edgeCapacity)
        private val next = IntArray(edgeCapacity)
        private val capacity = FloatArray(edgeCapacity)
        private var count = 0

        fun edge(a: Int, b: Int, forward: Float, backward: Float) {
            to[count] = b
            capacity[count] = forward
            next[count] = head[a]
            head[a] = count++
            to[count] = a
            capacity[count] = backward
            next[count] = head[b]
            head[b] = count++
        }

        fun cut(source: Int, sink: Int, check: () -> Unit): BooleanArray {
            val level = IntArray(n)
            val queue = IntArray(n)
            val current = IntArray(n)
            val vertices = IntArray(n)
            val edges = IntArray(n)
            val available = FloatArray(n)
            while (true) {
                check()
                level.fill(-1)
                level[source] = 0
                var start = 0
                var end = 1
                queue[0] = source
                while (start < end) {
                    val v = queue[start++]
                    var e = head[v]
                    while (e >= 0) {
                        val u = to[e]
                        if (capacity[e] > 1e-6f && level[u] < 0) {
                            level[u] = level[v] + 1
                            queue[end++] = u
                        }
                        e = next[e]
                    }
                }
                if (level[sink] < 0) return BooleanArray(n) { level[it] >= 0 }
                head.copyInto(current)
                var depth = 0
                vertices[0] = source
                available[0] = Float.MAX_VALUE
                while (depth >= 0) {
                    val v = vertices[depth]
                    if (v == sink) {
                        val flow = available[depth]
                        for (i in 0 until depth) {
                            val e = edges[i]
                            capacity[e] -= flow
                            capacity[e xor 1] += flow
                        }
                        depth = 0
                        continue
                    }
                    var e = current[v]
                    while (e >= 0 && (capacity[e] <= 1e-6f || level[to[e]] != level[v] + 1)) e =
                        next[e]
                    current[v] = e
                    if (e < 0) {
                        level[v] = -1
                        depth--
                        if (depth >= 0) current[vertices[depth]] = next[edges[depth]]
                    } else {
                        edges[depth] = e
                        vertices[depth + 1] = to[e]
                        available[depth + 1] = min(available[depth], capacity[e])
                        depth++
                    }
                }
            }
        }
    }
}
