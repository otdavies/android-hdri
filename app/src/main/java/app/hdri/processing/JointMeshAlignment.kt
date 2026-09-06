package app.hdri.processing

import app.hdri.core.*
import kotlin.math.*

/** Couples all overlapping views. A handheld orbit is not a rotation about one optical centre. */
internal object JointMeshAlignment {
    data class Report(val before: Double, val after: Double, val p90: Double, val constraints: Int)

    private const val COLS = 13
    private const val ROWS = 9
    private const val N = COLS * ROWS

    private class Row(
        val ids: IntArray,
        val values: DoubleArray,
        val target: Double,
        val base: Double,
    ) {
        var weight = base

        fun dot(x: DoubleArray): Double {
            var v = 0.0
            for (i in ids.indices) v += values[i] * x[ids[i]]
            return v
        }
    }

    fun fit(
        frames: List<Prepared>,
        observations: List<Observation>,
        progress: (Double) -> Unit,
        check: () -> Unit,
    ): Report {
        val counts = observations.groupingBy { it.a to it.b }.eachCount()
        val support = IntArray(frames.size)
        val data = ArrayList<Row>()
        val used = ArrayList<Observation>()
        observations.forEach { o ->
            val a = frames[o.a].rotation.rotate(o.u)
            val b = frames[o.b].rotation.rotate(o.v)
            if (a.angle(b) > 8) return@forEach
            val mid = (a + b).unit()
            val pa = point(frames[o.a], mid) ?: return@forEach
            val pb = point(frames[o.b], mid) ?: return@forEach
            if (listOf(pa, pb).any { it.first !in 0.01..0.99 || it.second !in 0.01..0.99 })
                return@forEach
            val ids = IntArray(16)
            val values = Array(3) { DoubleArray(16) }
            listOf(Triple(o.a, pa, 1.0), Triple(o.b, pb, -1.0)).forEachIndexed {
                side,
                (index, p, sign) ->
                val frame = frames[index]
                val gx = p.first * (COLS - 1)
                val gy = p.second * (ROWS - 1)
                val x = floor(gx).toInt().coerceIn(0, COLS - 2)
                val y = floor(gy).toInt().coerceIn(0, ROWS - 2)
                val u = gx - x
                val v = gy - y
                val vertices =
                    intArrayOf(
                        y * COLS + x,
                        y * COLS + x + 1,
                        (y + 1) * COLS + x,
                        (y + 1) * COLS + x + 1,
                    )
                val weights = doubleArrayOf((1 - u) * (1 - v), u * (1 - v), (1 - u) * v, u * v)
                val origin = world(frame, p.first, p.second)
                for (axis in 0..1) {
                    val delta =
                        (world(
                            frame,
                            p.first + (if (axis == 0) .0001 else 0.0),
                            p.second + (if (axis == 1) .0001 else 0.0),
                        ) - origin) * 10000.0
                    val jacobian = doubleArrayOf(delta.x, delta.y, delta.z)
                    for (j in 0..3) {
                        val slot = side * 8 + axis * 4 + j
                        ids[slot] = index * N * 2 + vertices[j] * 2 + axis
                        for (k in 0..2) values[k][slot] = sign * jacobian[k] * weights[j]
                    }
                }
            }
            val delta = a - b
            val target = doubleArrayOf(delta.x, delta.y, delta.z)
            for (k in 0..2) data +=
                Row(ids, values[k], target[k], min(2.0, 100.0 / counts.getValue(o.a to o.b)))
            used += o
            support[o.a]++
            support[o.b]++
        }
        val before = errors(frames, used)
        if (used.size < 12)
            return Report(
                percentile(before, .5),
                percentile(before, .5),
                percentile(before, .9),
                used.size,
            )
        val regular = ArrayList<Row>()
        for (i in frames.indices) for (y in 0 until ROWS) for (x in 0 until COLS) for (axis in
            0..1) {
            val p = y * COLS + x
            fun add(vertices: IntArray, values: DoubleArray) {
                regular +=
                    Row(
                        IntArray(vertices.size) { i * N * 2 + vertices[it] * 2 + axis },
                        values,
                        0.0,
                        1.0,
                    )
            }
            // Second derivatives preserve locally affine structure. A weak membrane and
            // zero-offset prior constrain textureless cells without pinning supported detail.
            add(intArrayOf(p), doubleArrayOf(.08))
            if (x + 1 < COLS) add(intArrayOf(p, p + 1), doubleArrayOf(-.18, .18))
            if (y + 1 < ROWS) add(intArrayOf(p, p + COLS), doubleArrayOf(-.18, .18))
            if (x > 0 && x + 1 < COLS)
                add(intArrayOf(p - 1, p, p + 1), doubleArrayOf(.65, -1.3, .65))
            if (y > 0 && y + 1 < ROWS)
                add(intArrayOf(p - COLS, p, p + COLS), doubleArrayOf(.65, -1.3, .65))
        }
        val all = data + regular
        var solution = DoubleArray(frames.size * N * 2)
        repeat(4) { iteration ->
            check()
            progress(iteration / 4.0)
            solution = solve(all, solution, check)
            for (o in used.indices) {
                var error = 0.0
                for (k in 0..2) error +=
                    (data[o * 3 + k].dot(solution) - data[o * 3 + k].target).pow(2)
                val robust = min(1.0, .003 / sqrt(error).coerceAtLeast(1e-8))
                for (k in 0..2) data[o * 3 + k].weight = data[o * 3 + k].base * robust
            }
        }
        // Increase shape regularization only in views that would otherwise fold or
        // exceed the deformation bound, then solve the coupled system again.
        for (attempt in 0 until 3) {
            val bad =
                frames.indices
                    .filter { i ->
                        support[i] >= 12 &&
                            !valid(
                                MeshWarp(
                                    COLS,
                                    ROWS,
                                    DoubleArray(N) { solution[i * N * 2 + it * 2] },
                                    DoubleArray(N) { solution[i * N * 2 + it * 2 + 1] },
                                )
                            )
                    }
                    .toSet()
            if (bad.isEmpty()) break
            regular.forEach { if (it.ids[0] / (N * 2) in bad) it.weight *= 4 }
            solution = solve(all, solution, check)
        }
        val previous = frames.map { it.mesh }
        var scale = 1.0
        var candidate: List<MeshWarp?>
        while (true) {
            candidate =
                frames.indices.map { i ->
                    if (support[i] < 12) previous[i]
                    else
                        MeshWarp(
                            COLS,
                            ROWS,
                            DoubleArray(N) { solution[i * N * 2 + it * 2] * scale },
                            DoubleArray(N) { solution[i * N * 2 + it * 2 + 1] * scale },
                        )
                }
            if (candidate.all { it == null || valid(it) }) break
            scale *= .5
            if (scale < .125)
                return Report(
                    percentile(before, .5),
                    percentile(before, .5),
                    percentile(before, .9),
                    used.size,
                )
        }
        frames.forEachIndexed { i, f -> f.mesh = candidate[i] }
        val after = errors(frames, used)
        if (
            percentile(after, .5) > percentile(before, .5) ||
                percentile(after, .9) > percentile(before, .9) * 1.05
        ) {
            frames.forEachIndexed { i, f -> f.mesh = previous[i] }
            return Report(
                percentile(before, .5),
                percentile(before, .5),
                percentile(before, .9),
                used.size,
            )
        }
        progress(1.0)
        return Report(
            percentile(before, .5),
            percentile(after, .5),
            percentile(after, .9),
            used.size,
        )
    }

    private fun point(f: Prepared, world: V3) =
        f.lens.project(f.rotation.inverse().rotate(world))?.let {
            it.first / f.lens.width to it.second / f.lens.height
        }

    private fun world(f: Prepared, x: Double, y: Double) =
        f.rotation.rotate(f.lens.ray(x * f.lens.width, y * f.lens.height))

    internal fun correctedRay(f: Prepared, ray: V3): V3 {
        val p = f.lens.project(ray) ?: return f.rotation.rotate(ray)
        val x = p.first / f.lens.width
        val y = p.second / f.lens.height
        var u = x
        var v = y
        val delta = DoubleArray(2)
        repeat(8) {
            f.mesh?.offset(u, v, delta)
            u = x - delta[0]
            v = y - delta[1]
        }
        return world(f, u, v)
    }

    private fun errors(frames: List<Prepared>, obs: List<Observation>) =
        obs.map { correctedRay(frames[it.a], it.u).angle(correctedRay(frames[it.b], it.v)) }
            .sorted()

    private fun percentile(x: List<Double>, p: Double) =
        if (x.isEmpty()) 0.0 else x[min(x.lastIndex, (x.size * p).toInt())]

    private fun valid(m: MeshWarp): Boolean {
        if ((m.dx.asSequence() + m.dy.asSequence()).any { !it.isFinite() || abs(it) > .08 })
            return false
        for (y in 0 until m.rows - 1) for (x in 0 until m.cols - 1) {
            val p = y * m.cols + x
            for (t in
                listOf(
                    intArrayOf(p, p + 1, p + m.cols),
                    intArrayOf(p + m.cols + 1, p + m.cols, p + 1),
                )) {
                fun vx(a: Int, b: Int) =
                    (b % m.cols - a % m.cols).toDouble() / (m.cols - 1) + m.dx[b] - m.dx[a]
                fun vy(a: Int, b: Int) =
                    (b / m.cols - a / m.cols).toDouble() / (m.rows - 1) + m.dy[b] - m.dy[a]
                if (
                    vx(t[0], t[1]) * vy(t[0], t[2]) - vy(t[0], t[1]) * vx(t[0], t[2]) <
                        .35 / ((m.cols - 1) * (m.rows - 1))
                )
                    return false
            }
        }
        return true
    }

    /** Matrix-free preconditioned conjugate gradients keeps the full-sphere solve bounded. */
    private fun solve(rows: List<Row>, initial: DoubleArray, check: () -> Unit): DoubleArray {
        val n = initial.size
        val b = DoubleArray(n)
        val diagonal = DoubleArray(n) { 1e-9 }
        rows.forEach { row ->
            for (j in row.ids.indices) {
                val id = row.ids[j]
                b[id] += row.weight * row.values[j] * row.target
                diagonal[id] += row.weight * row.values[j].pow(2)
            }
        }
        fun multiply(x: DoubleArray, out: DoubleArray) {
            out.fill(0.0)
            rows.forEach { row ->
                val dot = row.dot(x) * row.weight
                for (j in row.ids.indices) out[row.ids[j]] += row.values[j] * dot
            }
        }
        fun dot(a: DoubleArray, b: DoubleArray): Double {
            var v = 0.0
            for (i in a.indices) v += a[i] * b[i]
            return v
        }
        val x = initial.copyOf()
        val ax = DoubleArray(n)
        multiply(x, ax)
        val r = DoubleArray(n) { b[it] - ax[it] }
        val z = DoubleArray(n) { r[it] / diagonal[it] }
        val p = z.copyOf()
        var rz = dot(r, z)
        val limit = max(1e-20, dot(b, b) * 1e-10)
        repeat(120) { iteration ->
            if (iteration % 8 == 0) check()
            if (dot(r, r) < limit) return x
            multiply(p, ax)
            val denominator = dot(p, ax)
            if (denominator <= 1e-24 || !denominator.isFinite()) return x
            val alpha = rz / denominator
            for (i in 0 until n) {
                x[i] += alpha * p[i]
                r[i] -= alpha * ax[i]
                z[i] = r[i] / diagonal[i]
            }
            val next = dot(r, z)
            val beta = next / max(1e-30, rz)
            rz = next
            for (i in 0 until n) p[i] = z[i] + beta * p[i]
        }
        return x
    }
}
