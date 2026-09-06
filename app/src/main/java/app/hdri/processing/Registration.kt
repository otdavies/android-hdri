package app.hdri.processing

import app.hdri.core.*
import app.hdri.data.Capture
import java.io.File
import kotlin.math.*
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

data class Prepared(
    val capture: Capture,
    val lens: Lens,
    val hdr: File,
    val preview: File,
    var rotation: Q = capture.rotation,
)

internal data class Observation(val a: Int, val b: Int, val u: V3, val v: V3)

/**
 * Rotation-only bundle refinement with an AR prior. Rejects matches requiring large scene
 * deformation.
 */
internal object Registration {
    private data class Features(val points: Array<KeyPoint>, val descriptors: Mat, val lens: Lens)

    fun refine(
        frames: List<Prepared>,
        progress: (String, Double) -> Unit,
        check: () -> Unit,
    ): List<String> {
        val detector = ORB.create(1600)
        val features =
            frames.map { f ->
                check()
                val image = Imgcodecs.imread(f.preview.path, Imgcodecs.IMREAD_GRAYSCALE)
                val small = Mat()
                val scale = min(1.0, 700.0 / image.cols())
                Imgproc.resize(image, small, Size(image.cols() * scale, image.rows() * scale))
                val key = MatOfKeyPoint()
                val desc = Mat()
                val mask = Mat()
                detector.detectAndCompute(small, mask, key, desc)
                val result =
                    Features(key.toArray(), desc, f.lens.scaled(small.cols(), small.rows()))
                image.release()
                small.release()
                key.release()
                mask.release()
                result
            }
        val observations = mutableListOf<Observation>()
        val links = IntArray(frames.size)
        var candidates = 0
        var rejected = 0
        val pairs = buildList {
            for (a in frames.indices) for (b in a + 1 until frames.size) {
                val fa = frames[a]
                val fb = frames[b]
                if (
                    fa.rotation.rotate(V3.FORWARD).angle(fb.rotation.rotate(V3.FORWARD)) <
                        min(85.0, max(fa.lens.fovX, fa.lens.fovY) * 1.08)
                )
                    add(a to b)
            }
        }
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        try {
            pairs.forEachIndexed { index, (a, b) ->
                check()
                progress(
                    "Matching overlapping photos · ${index+1}/${pairs.size}",
                    .75 * index / max(1, pairs.size),
                )
                val af = features[a]
                val bf = features[b]
                if (af.descriptors.rows() < 12 || bf.descriptors.rows() < 12) return@forEachIndexed
                val knn = mutableListOf<MatOfDMatch>()
                matcher.knnMatch(af.descriptors, bf.descriptors, knn, 2)
                val matches =
                    knn.mapNotNull { m ->
                        val d = m.toArray()
                        if (d.size == 2 && d[0].distance < .72 * d[1].distance) d[0] else null
                    }
                knn.forEach { it.release() }
                if (matches.size < 14) return@forEachIndexed
                val src = MatOfPoint2f(*matches.map { af.points[it.queryIdx].pt }.toTypedArray())
                val dst = MatOfPoint2f(*matches.map { bf.points[it.trainIdx].pt }.toTypedArray())
                val inliers = Mat()
                val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 2.5, inliers, 1500, .995)
                if (!h.empty()) {
                    candidates++
                    val valid =
                        matches.indices
                            .filter { inliers.get(it, 0)?.firstOrNull() == 1.0 }
                            .map { m ->
                                val match = matches[m]
                                val u = af.points[match.queryIdx].pt
                                val v = bf.points[match.trainIdx].pt
                                Observation(a, b, af.lens.ray(u.x, u.y), bf.lens.ray(v.x, v.y))
                            }
                            .filter {
                                it.u
                                    .let { u -> frames[a].rotation.rotate(u) }
                                    .angle(frames[b].rotation.rotate(it.v)) < 7.0
                            }
                    if (valid.size >= 12) {
                        observations +=
                            valid.filterIndexed { n, _ -> n % max(1, valid.size / 60) == 0 }
                        links[a]++
                        links[b]++
                    } else rejected++
                }
                src.release()
                dst.release()
                inliers.release()
                h.release()
            }
        } finally {
            features.forEach { it.descriptors.release() }
            detector.clear()
            matcher.clear()
        }
        // Simultaneous robust tangent updates keep loop closure distributed across the sphere.
        repeat(70) { iteration ->
            check()
            progress(
                "Refining spherical alignment · ${iteration+1}/70",
                .75 + .25 * iteration / 70.0,
            )
            val steps = Array(frames.size) { V3.ZERO }
            val weights = DoubleArray(frames.size)
            observations.forEach { o ->
                val u = frames[o.a].rotation.rotate(o.u)
                val v = frames[o.b].rotation.rotate(o.v)
                val residual = u.cross(v)
                val w = min(1.0, .015 / residual.length().coerceAtLeast(1e-8))
                steps[o.a] = steps[o.a] + residual * w
                steps[o.b] = steps[o.b] - residual * w
                weights[o.a] += w
                weights[o.b] += w
            }
            for (i in 1 until frames.size) {
                if (weights[i] == 0.0) continue
                val step = steps[i] * (.55 / weights[i])
                val q = (Q.axis(step) * frames[i].rotation).normalized()
                if (q.angle(frames[i].capture.rotation) < 8.0) frames[i].rotation = q
            }
        }
        val errors =
            observations
                .map {
                    frames[it.a].rotation.rotate(it.u).angle(frames[it.b].rotation.rotate(it.v))
                }
                .sorted()
        return buildList {
            val isolated = links.count { it == 0 }
            if (isolated > 0)
                add(
                    "$isolated directions have too little texture for visual alignment; they use the tracked camera orientation. Inspect those seams."
                )
            if (
                errors.isNotEmpty() &&
                    errors[(errors.size * .9).toInt().coerceAtMost(errors.lastIndex)] > 1.0
            )
                add(
                    "Some overlaps contain parallax or movement. Inspect nearby objects and moving people in the viewer."
                )
            if (candidates > 0 && rejected > candidates / 3)
                add(
                    "Several overlaps disagreed with camera tracking. Capture from a fixed lens position for cleaner seams."
                )
        }
    }
}
