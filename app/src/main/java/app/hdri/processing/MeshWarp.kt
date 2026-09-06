package app.hdri.processing

import app.hdri.core.*
import kotlin.math.*
import org.opencv.core.*

/** Smooth inverse-map residuals in normalized source coordinates. Never invents radiance. */
internal class MeshWarp(val cols: Int, val rows: Int, val dx: DoubleArray, val dy: DoubleArray) {
    fun offset(x: Double, y: Double, out: DoubleArray) {
        val gx = x.coerceIn(0.0, 1.0) * (cols - 1)
        val gy = y.coerceIn(0.0, 1.0) * (rows - 1)
        val ix = floor(gx).toInt().coerceAtMost(cols - 2)
        val iy = floor(gy).toInt().coerceAtMost(rows - 2)
        val u = gx - ix
        val v = gy - iy
        val p = iy * cols + ix
        out[0] =
            (dx[p] * (1 - u) + dx[p + 1] * u) * (1 - v) +
                (dx[p + cols] * (1 - u) + dx[p + cols + 1] * u) * v
        out[1] =
            (dy[p] * (1 - u) + dy[p + 1] * u) * (1 - v) +
                (dy[p + cols] * (1 - u) + dy[p + cols + 1] * u) * v
    }

    companion object {
        fun fit(index: Int, frames: List<Prepared>, observations: List<Observation>): MeshWarp? {
            val frame = frames[index]
            val lens = frame.lens
            val samples =
                observations.mapNotNull { o ->
                    if (o.a != index && o.b != index) return@mapNotNull null
                    val a = frames[o.a].rotation.rotate(o.u)
                    val b = frames[o.b].rotation.rotate(o.v)
                    if (a.angle(b) > 6.0) return@mapNotNull null
                    val target =
                        lens.project(frame.rotation.inverse().rotate((a + b).unit()))
                            ?: return@mapNotNull null
                    val actual =
                        lens.project(if (o.a == index) o.u else o.v) ?: return@mapNotNull null
                    val dx = (actual.first - target.first) / lens.width
                    val dy = (actual.second - target.second) / lens.height
                    if (hypot(dx, dy) > .06) return@mapNotNull null
                    doubleArrayOf(target.first / lens.width, target.second / lens.height, dx, dy)
                }
            if (samples.size < 12) return null
            val cols = 9
            val rows = 7
            val n = cols * rows
            val h = DoubleArray(n * n)
            val bx = DoubleArray(n)
            val by = DoubleArray(n)
            samples.forEach { s ->
                val gx = s[0] * (cols - 1)
                val gy = s[1] * (rows - 1)
                val x = floor(gx).toInt().coerceIn(0, cols - 2)
                val y = floor(gy).toInt().coerceIn(0, rows - 2)
                val u = gx - x
                val v = gy - y
                val p = y * cols + x
                val ids = intArrayOf(p, p + 1, p + cols, p + cols + 1)
                val w = doubleArrayOf((1 - u) * (1 - v), u * (1 - v), (1 - u) * v, u * v)
                // Normalize density across frames; preserve the same deformation prior.
                val confidence = 100.0 / samples.size
                for (a in 0..3) {
                    bx[ids[a]] += confidence * w[a] * s[2]
                    by[ids[a]] += confidence * w[a] * s[3]
                    for (b in 0..3) h[ids[a] * n + ids[b]] += confidence * w[a] * w[b]
                }
            }
            for (y in 0 until rows) for (x in 0 until cols) {
                val p = y * cols + x
                h[p * n + p] += .03
                for (q in
                    intArrayOf(
                        if (x + 1 < cols) p + 1 else -1,
                        if (y + 1 < rows) p + cols else -1,
                    )) {
                    if (q < 0) continue
                    val smooth = .7
                    h[p * n + p] += smooth
                    h[q * n + q] += smooth
                    h[p * n + q] -= smooth
                    h[q * n + p] -= smooth
                }
            }
            val hm = Mat(n, n, CvType.CV_64F)
            val rhs = Mat(n, 2, CvType.CV_64F)
            val result = Mat()
            try {
                hm.put(0, 0, *h)
                rhs.put(0, 0, *DoubleArray(n * 2) { if (it % 2 == 0) bx[it / 2] else by[it / 2] })
                if (!Core.solve(hm, rhs, result, Core.DECOMP_CHOLESKY)) return null
                val data = DoubleArray(n * 2)
                result.get(0, 0, data)
                val dx = DoubleArray(n) { data[it * 2] }
                val dy = DoubleArray(n) { data[it * 2 + 1] }
                if (data.any { !it.isFinite() || abs(it) > .06 }) return null
                // Bound gradients and prohibit folds, including both triangles of every cell.
                for (y in 0 until rows - 1) for (x in 0 until cols - 1) {
                    val p = y * cols + x
                    for (t in
                        listOf(
                            intArrayOf(p, p + 1, p + cols),
                            intArrayOf(p + cols + 1, p + cols, p + 1),
                        )) {
                        fun vx(a: Int, b: Int) =
                            (b % cols - a % cols).toDouble() / (cols - 1) + dx[b] - dx[a]
                        fun vy(a: Int, b: Int) =
                            (b / cols - a / cols).toDouble() / (rows - 1) + dy[b] - dy[a]
                        val area = vx(t[0], t[1]) * vy(t[0], t[2]) - vy(t[0], t[1]) * vx(t[0], t[2])
                        if (area < .4 / ((cols - 1) * (rows - 1))) return null
                    }
                }
                return MeshWarp(cols, rows, dx, dy)
            } finally {
                hm.release()
                rhs.release()
                result.release()
            }
        }
    }
}
