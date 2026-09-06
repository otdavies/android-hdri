package app.hdri.processing

import kotlin.math.*
import org.json.*
import org.opencv.core.*

/**
 * Robust radiometric transfer at the SAME scene locations; never equalizes whole-image histograms.
 */
internal object OverlapPhotometry {
    private data class Sample(val a: Int, val b: Int, val radius: Double, val delta: DoubleArray)

    data class Report(
        val samples: Int,
        val beforeEv: Double,
        val afterEv: Double,
        val maxGainEv: Double,
        val radialEv: List<Double>,
    ) {
        fun json() =
            JSONObject()
                .put("samples", samples)
                .put("heldOutMedianBeforeEv", beforeEv)
                .put("heldOutMedianAfterEv", afterEv)
                .put("maxGainEv", maxGainEv)
                .put("radialBgrEv", JSONArray(radialEv))
    }

    fun fit(
        frames: List<Prepared>,
        layers: List<SeamLayer>,
        w: Int,
        h: Int,
        progress: (Double) -> Unit,
        check: () -> Unit,
    ): Report {
        val smooth =
            layers.map { l ->
                BooleanArray(w * h).also { good ->
                    for (y in 1 until h - 1) for (x in 0 until w) {
                        val p = y * w + x
                        if (l.weights[p] < .05f) continue
                        var low = Double.POSITIVE_INFINITY
                        var high = Double.NEGATIVE_INFINITY
                        for (dy in -1..1) for (dx in -1..1) {
                            val q = (y + dy) * w + (x + dx + w) % w
                            val v = (l.rgb[q * 3] + l.rgb[q * 3 + 1] + l.rgb[q * 3 + 2]) / 3.0
                            low = min(low, v)
                            high = max(high, v)
                        }
                        good[p] = high - low < .10 && (0..2).all { l.rgb[p * 3 + it] > -16f }
                    }
                }
            }
        val samples = ArrayList<Sample>()
        for (a in frames.indices) {
            check()
            progress(.4 * a / max(1, frames.size))
            for (b in a + 1 until frames.size) {
                val pixels = ArrayList<Int>()
                for (p in 0 until w * h) if (smooth[a][p] && smooth[b][p]) pixels += p
                if (pixels.size < 40) continue
                val count = min(200, pixels.size)
                for (i in 0 until count) {
                    val p = pixels[(i.toLong() * (pixels.size - 1) / max(1, count - 1)).toInt()]
                    val delta =
                        DoubleArray(3) {
                            (layers[a].rgb[p * 3 + it] - layers[b].rgb[p * 3 + it]).toDouble()
                        }
                    if (delta.any { abs(it) > 1.0 } || delta.max() - delta.min() > .4) continue
                    samples +=
                        Sample(a, b, (layers[a].radii[p] - layers[b].radii[p]).toDouble(), delta)
                }
            }
        }
        if (samples.size < 100) return Report(samples.size, 0.0, 0.0, 0.0, listOf(0.0, 0.0, 0.0))
        val train = samples.filterIndexed { i, _ -> i % 5 != 0 }
        val held = samples.filterIndexed { i, _ -> i % 5 == 0 }
        val before = ArrayList<Double>()
        val after = ArrayList<Double>()
        var maxGain = 0.0
        val radial = DoubleArray(3)
        for (c in 0..2) {
            check()
            progress(.4 + .6 * c / 3)
            val candidate = solve(train, frames.size, c, check)
            val e0 = held.map { abs(it.delta[c]) }.sorted()
            val e1 =
                held
                    .map {
                        abs(
                            it.delta[c] + candidate[it.a] - candidate[it.b] +
                                candidate.last() * it.radius
                        )
                    }
                    .sorted()
            val accepted =
                e1[e1.size / 2] < e0[e0.size / 2] * .9 &&
                    e1[e1.size * 9 / 10] <= e0[e0.size * 9 / 10] * 1.05
            val fit =
                if (accepted) solve(samples, frames.size, c, check)
                else DoubleArray(frames.size + 1)
            before += e0
            // Report only the withheld evaluation, not the refit-to-all training residual.
            after += if (accepted) e1 else e0
            for (i in frames.indices) {
                frames[i].logGain[c] = fit[i]
                frames[i].radialGain[c] = fit.last()
                maxGain = max(maxGain, abs(fit[i]) / ln(2.0))
            }
            radial[c] = fit.last() / ln(2.0)
        }
        layers.forEachIndexed { i, l ->
            for (p in 0 until w * h) for (c in 0..2) {
                l.rgb[p * 3 + c] +=
                    (frames[i].logGain[c] + frames[i].radialGain[c] * l.radii[p]).toFloat()
            }
        }
        progress(1.0)
        before.sort()
        after.sort()
        return Report(
            samples.size,
            before[before.size / 2] / ln(2.0),
            after[after.size / 2] / ln(2.0),
            maxGain,
            radial.toList(),
        )
    }

    private fun solve(
        samples: List<Sample>,
        count: Int,
        channel: Int,
        check: () -> Unit,
    ): DoubleArray {
        val n = count + 1
        var result = DoubleArray(n)
        repeat(6) {
            check()
            val h = DoubleArray(n * n)
            val b = DoubleArray(n)
            samples.forEach { s ->
                val ids = intArrayOf(s.a, s.b, count)
                val a = doubleArrayOf(1.0, -1.0, s.radius)
                val residual =
                    s.delta[channel] + result[s.a] - result[s.b] + result[count] * s.radius
                val weight = min(1.0, .04 / max(1e-6, abs(residual)))
                for (j in 0..2) {
                    b[ids[j]] -= weight * a[j] * s.delta[channel]
                    for (k in 0..2) h[ids[j] * n + ids[k]] += weight * a[j] * a[k]
                }
            }
            for (i in 0 until n) h[i * n + i] += if (i == count) 4.0 else .25
            val hm = Mat(n, n, CvType.CV_64F)
            val bm = Mat(n, 1, CvType.CV_64F)
            val solution = Mat()
            try {
                hm.put(0, 0, *h)
                bm.put(0, 0, *b)
                if (!Core.solve(hm, bm, solution, Core.DECOMP_CHOLESKY)) return DoubleArray(n)
                solution.get(0, 0, result)
                for (i in 0 until n) result[i] =
                    result[i].coerceIn(
                        -ln(2.0) * (if (i == count) .5 else .6),
                        ln(2.0) * (if (i == count) .5 else .6),
                    )
            } finally {
                hm.release()
                bm.release()
                solution.release()
            }
        }
        return result
    }

    /**
     * Apply once per decoded frame, with separable radial factors and one scanline of JVM storage.
     */
    fun apply(frame: Prepared, image: Mat) {
        if (frame.logGain.all { it == 0.0 } && frame.radialGain.all { it == 0.0 }) return
        val w = image.cols()
        val h = image.rows()
        val row = FloatArray(w * 3)
        val xGain =
            DoubleArray(w * 3) { i ->
                exp(
                    frame.logGain[i % 3] +
                        frame.radialGain[i % 3] * 2 * ((i / 3).toDouble() / w - .5).pow(2)
                )
            }
        for (y in 0 until h) {
            image.get(y, 0, row)
            for (c in 0..2) {
                val gy = exp(frame.radialGain[c] * 2 * (y.toDouble() / h - .5).pow(2))
                for (x in 0 until w) row[x * 3 + c] =
                    (row[x * 3 + c] * xGain[x * 3 + c] * gy).toFloat()
            }
            image.put(y, 0, row)
        }
    }
}
