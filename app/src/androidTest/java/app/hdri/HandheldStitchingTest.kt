package app.hdri

import app.hdri.core.*
import app.hdri.data.Capture
import app.hdri.processing.*
import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader

class HandheldStitchingTest {
    @Test
    fun jointWarpGeneralizesToUnseenDetailDuringAnOrbit() {
        val lens = Lens(800, 600, 580.0, 580.0, 400.0, 300.0)
        val rotations = (0 until 9).map { Q.look((it % 3 - 1) * 22.0, (it / 3 - 1) * 17.0) }
        val origins =
            rotations.map {
                it.rotate(V3(0.0, 0.0, -.32)) + V3(0.0, it.rotate(V3.FORWARD).y * .12, 0.0)
            }
        val frames =
            rotations.mapIndexed { i, q ->
                Prepared(
                    Capture(i, q, V3.ZERO, lens, emptyList()),
                    lens,
                    File("unused-$i"),
                    File("unused-$i"),
                )
            }
        val train = mutableListOf<Observation>()
        val held = mutableListOf<Observation>()
        var landmark = 0
        for (y in -30..30 step 3) for (x in -48..48 step 3) {
            val distance =
                2.2 + 1.6 * sin(Math.toRadians(x * 1.8)).pow(2) + .4 * cos(Math.toRadians(y * 2.0))
            val point = Q.look(x.toDouble(), y.toDouble()).rotate(V3.FORWARD) * distance
            val rays =
                frames.indices.map { i -> rotations[i].inverse().rotate(point - origins[i]).unit() }
            for (a in frames.indices) for (b in a + 1 until frames.size) {
                if (rotations[a].angle(rotations[b]) > 35) continue
                fun visible(i: Int) =
                    lens.project(rays[i])?.let {
                        it.first in 30.0..770.0 && it.second in 30.0..570.0
                    } ?: false
                if (visible(a) && visible(b))
                    (if (landmark % 5 == 0) held else train).add(
                        Observation(a, b, rays[a], rays[b])
                    )
            }
            landmark++
        }
        fun errors() =
            held
                .map {
                    JointMeshAlignment.correctedRay(frames[it.a], it.u)
                        .angle(JointMeshAlignment.correctedRay(frames[it.b], it.v))
                }
                .sorted()
        val before = errors()
        assertTrue(before[before.size / 2] > .3)
        val report = JointMeshAlignment.fit(frames, train, {}, {})
        val after = errors()
        println(
            "Orbit held-out median ${before[before.size/2]} -> ${after[after.size/2]}, p90 ${before[before.size*9/10]} -> ${after[after.size*9/10]}; $report"
        )
        assertTrue(after[after.size / 2] < before[before.size / 2] * .25)
        assertTrue(after[after.size * 9 / 10] < before[before.size * 9 / 10] * .4)
        assertTrue(frames.all { it.mesh != null })
    }

    @Test
    fun overlapCorrectionPreservesRealLightingWithExposureAndShadingErrors() {
        assertTrue(OpenCVLoader.initLocal())
        val w = 160
        val h = 80
        val lens = Lens(800, 600, 580.0, 580.0, 400.0, 300.0)
        val frames =
            (0 until 4).map { i ->
                Prepared(
                    Capture(i, Q(0.0, 0.0, 0.0, 1.0), V3.ZERO, lens, emptyList()),
                    lens,
                    File("unused-$i"),
                    File("unused-$i"),
                )
            }
        val gains = doubleArrayOf(-.18, .12, .2, -.14)
        val layers =
            frames.indices.map { i ->
                val radius =
                    FloatArray(w * h) { p ->
                        (2 * ((p % w).toDouble() / w - i * .13 - .2).pow(2) +
                                2 * ((p / w).toDouble() / h - .5).pow(2))
                            .toFloat()
                    }
                val rgb =
                    FloatArray(w * h * 3) { k ->
                        val p = k / 3
                        val x = p % w
                        val y = p / w
                        val c = k % 3
                        // The world really gets brighter and changes colour left-to-right.
                        val scene = -1.0 + 1.8 * x / w + .15 * sin(y * .03) + (c - 1) * .3 * x / w
                        (scene + gains[i] - .22 * radius[p]).toFloat()
                    }
                val weights =
                    FloatArray(w * h) { p ->
                        if ((p % w).toDouble() / w in (i * .13)..(.61 + i * .13)) 1f else 0f
                    }
                // One moving foreground patch must not set the exposure of the whole panorama.
                if (i == 2)
                    for (y in 25..38) for (x in 60..78) for (c in 0..2) rgb[(y * w + x) * 3 + c] +=
                        .8f
                SeamLayer(rgb, weights, radius)
            }
        val report = OverlapPhotometry.fit(frames, layers, w, h, {}, {})
        println("Photometry fixture: $report")
        assertTrue(report.samples > 100)
        assertTrue(report.afterEv < report.beforeEv * .25)
        for (i in frames.indices) for (c in 0..2) assertEquals(
            -gains[i],
            frames[i].logGain[c],
            .025,
        )
        val left = layers[0].rgb[(40 * w + 20) * 3 + 1]
        val right = layers[3].rgb[(40 * w + 140) * 3 + 1]
        assertEquals(1.8 * 120 / w, (right - left).toDouble(), .035)
    }

    @Test
    fun cameraResponseRetainsShadowAndMidtoneTextureAcrossWideBrackets() {
        val response = Radiance.cameraResponse()
        for (z in 1..255) assertTrue("Flattened code $z", response[z * 3] > response[(z - 1) * 3])
        // Independent 16-point Camera2 forward interpolation, then quantized JPEG codes.
        val times = doubleArrayOf(.001, .008, .064)
        val truth = DoubleArray(256) { .4 + it * .07 }
        val images =
            times.map { time ->
                ByteArray(truth.size * 3) { i ->
                    val linear = (truth[i / 3] * time).coerceIn(0.0, 1.0)
                    val low = min(14, floor(linear * 15).toInt())
                    val t = linear * 15 - low
                    val encoded =
                        Radiance.srgb(low / 15.0) * (1 - t) + Radiance.srgb((low + 1) / 15.0) * t
                    (encoded * 255).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        val merged = Radiance.merge(images, times, response)
        val errors = truth.indices.map { abs(merged.rgb[it * 3] - truth[it]) / truth[it] }.sorted()
        assertTrue(errors[errors.size * 9 / 10] < .025)
        // Broad tonal intervals must retain a real brightness slope, including 64..96/160..192.
        assertTrue(merged.rgb[200 * 3] - merged.rgb[100 * 3] > 6.5f)
    }
}
