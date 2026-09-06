package app.hdri.processing

import app.hdri.core.*
import app.hdri.data.Capture
import java.io.File
import kotlin.math.*
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

data class Prepared(
    val capture: Capture,
    var lens: Lens,
    val hdr: File,
    val preview: File,
    var rotation: Q = capture.rotation,
) {
    internal var mesh: MeshWarp? = null
}

internal data class Observation(val a: Int, val b: Int, val u: V3, val v: V3)

internal data class RegistrationReport(
    val warnings: List<String>,
    val pairs: Int,
    val observations: Int,
    val isolated: Int,
    val beforeDegrees: Double,
    val afterDegrees: Double,
    val p90Degrees: Double,
    val meshes: Int,
    val focalScale: Double,
    val localMatches: Int,
)

/** Gyro initializes a robust joint rotation solve; image evidence can correct accumulated drift. */
internal object Registration {
    private data class Features(val points: Array<KeyPoint>, val descriptors: Mat, val lens: Lens)

    fun refine(
        frames: List<Prepared>,
        progress: (String, Double) -> Unit,
        check: () -> Unit,
    ): List<String> = analyze(frames, progress, check).warnings

    fun analyze(
        frames: List<Prepared>,
        progress: (String, Double) -> Unit,
        check: () -> Unit,
    ): RegistrationReport {
        val detector = SIFT.create(1800, 3, .012, 12.0)
        val contrast = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val features = mutableListOf<Features>()
        val observations = mutableListOf<Observation>()
        val links = IntArray(frames.size)
        val matchers = mutableListOf<DescriptorMatcher>()
        var accepted = 0
        try {
            frames.forEachIndexed { i, f ->
                check()
                progress("Finding image detail · ${i+1}/${frames.size}", .3 * i / frames.size)
                val image = Imgcodecs.imread(f.preview.path, Imgcodecs.IMREAD_GRAYSCALE)
                val key = MatOfKeyPoint()
                val desc = Mat()
                val mask = Mat()
                try {
                    require(!image.empty()) {
                        "An alignment preview is missing. Rebuild this sphere."
                    }
                    val scale =
                        min(
                            1.0,
                            (if (f.rotation.rotate(V3.FORWARD).y > -.25) 1000.0 else 800.0) /
                                max(image.cols(), image.rows()),
                        )
                    if (scale < 1)
                        Imgproc.resize(
                            image,
                            image,
                            Size(image.cols() * scale, image.rows() * scale),
                        )
                    contrast.apply(image, image)
                    detector.detectAndCompute(image, mask, key, desc)
                    // RootSIFT: Hellinger distance improves exposure/contrast robustness.
                    val data = FloatArray((desc.total() * desc.channels()).toInt())
                    if (data.isNotEmpty()) {
                        desc.get(0, 0, data)
                        for (row in 0 until desc.rows()) {
                            val start = row * desc.cols()
                            var sum = 1e-12
                            for (c in 0 until desc.cols()) sum += data[start + c]
                            for (c in 0 until desc.cols()) data[start + c] =
                                sqrt(data[start + c] / sum).toFloat()
                        }
                        desc.put(0, 0, data)
                    }
                    features +=
                        Features(key.toArray(), desc, f.lens.scaled(image.cols(), image.rows()))
                } catch (e: Exception) {
                    desc.release()
                    throw e
                } finally {
                    image.release()
                    key.release()
                    mask.release()
                }
            }
            features.forEach { f ->
                val matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED)
                if (!f.descriptors.empty()) {
                    matcher.add(listOf(f.descriptors))
                    matcher.train()
                }
                matchers += matcher
            }
            val pairs = buildList {
                for (a in frames.indices) for (b in a + 1 until frames.size) {
                    val fa = frames[a]
                    val fb = frames[b]
                    if (
                        fa.rotation.rotate(V3.FORWARD).angle(fb.rotation.rotate(V3.FORWARD)) <
                            min(90.0, max(fa.lens.fovX, fa.lens.fovY) * 1.22)
                    )
                        add(a to b)
                }
            }
            pairs.forEachIndexed { index, (a, b) ->
                check()
                progress(
                    "Matching overlapping photos · ${index+1}/${pairs.size}",
                    .3 + .35 * index / max(1, pairs.size),
                )
                val af = features[a]
                val bf = features[b]
                if (af.descriptors.rows() < 10 || bf.descriptors.rows() < 10) return@forEachIndexed
                val knn = mutableListOf<MatOfDMatch>()
                val matches =
                    try {
                        matchers[b].knnMatch(af.descriptors, knn, 2)
                        knn.mapNotNull { m ->
                                val d = m.toArray()
                                if (d.size != 2 || d[0].distance >= .78 * d[1].distance) null
                                else d[0]
                            }
                            .filter { m ->
                                val u = af.points[m.queryIdx].pt
                                val v = bf.points[m.trainIdx].pt
                                frames[a]
                                    .rotation
                                    .rotate(af.lens.ray(u.x, u.y))
                                    .angle(frames[b].rotation.rotate(bf.lens.ray(v.x, v.y))) < 22.0
                            }
                            .sortedBy { it.distance }
                            .distinctBy { it.trainIdx }
                    } finally {
                        knn.forEach { it.release() }
                    }
                if (matches.size < 10) return@forEachIndexed
                val src = MatOfPoint2f(*matches.map { af.points[it.queryIdx].pt }.toTypedArray())
                val dst = MatOfPoint2f(*matches.map { bf.points[it.trainIdx].pt }.toTypedArray())
                val inliers = Mat()
                val homography =
                    Calib3d.findHomography(src, dst, Calib3d.RANSAC, 4.0, inliers, 2000, .995)
                try {
                    if (homography.empty()) return@forEachIndexed
                    val flags = ByteArray(matches.size)
                    inliers.get(0, 0, flags)
                    val valid = matches.filterIndexed { i, _ -> flags[i].toInt() != 0 }
                    if (valid.size < 10) return@forEachIndexed
                    // Reject a match confined to one line (curtains/repeating edges).
                    fun spread(points: List<Point>): Double {
                        var xx = 0.0
                        var yy = 0.0
                        var xy = 0.0
                        val cx = points.sumOf { it.x } / points.size
                        val cy = points.sumOf { it.y } / points.size
                        points.forEach {
                            val x = it.x - cx
                            val y = it.y - cy
                            xx += x * x
                            yy += y * y
                            xy += x * y
                        }
                        return sqrt(max(0.0, xx * yy - xy * xy)) / points.size
                    }
                    if (
                        min(
                            spread(valid.map { af.points[it.queryIdx].pt }),
                            spread(valid.map { bf.points[it.trainIdx].pt }),
                        ) < 90.0
                    )
                        return@forEachIndexed
                    val selected =
                        valid.filterIndexed { i, _ -> i % max(1, valid.size / 100) == 0 }.take(140)
                    selected.forEach { m ->
                        val u = af.points[m.queryIdx].pt
                        val v = bf.points[m.trainIdx].pt
                        observations +=
                            Observation(a, b, af.lens.ray(u.x, u.y), bf.lens.ray(v.x, v.y))
                    }
                    accepted++
                    links[a]++
                    links[b]++
                } finally {
                    src.release()
                    dst.release()
                    inliers.release()
                    homography.release()
                }
            }
        } finally {
            features.forEach { it.descriptors.release() }
            detector.clear()
            matchers.forEach { it.clear() }
            contrast.collectGarbage()
            contrast.clear()
        }
        fun errors(observations: List<Observation>) =
            observations
                .map {
                    frames[it.a].rotation.rotate(it.u).angle(frames[it.b].rotation.rotate(it.v))
                }
                .sorted()
        fun percentile(e: List<Double>, p: Double) =
            if (e.isEmpty()) 0.0 else e[(e.size * p).toInt().coerceAtMost(e.lastIndex)]
        val before = percentile(errors(observations), .5)
        val focal =
            solve(
                frames,
                observations,
                { i ->
                    check()
                    progress("Refining spherical alignment · ${i+1}/25", .65 + .2 * i / 25)
                },
            )
        val corrected =
            observations.map { it.copy(u = calibrated(it.u, focal), v = calibrated(it.v, focal)) }
        frames.forEach { it.lens = it.lens.copy(fx = it.lens.fx * focal, fy = it.lens.fy * focal) }
        val after = errors(corrected)
        val local =
            LocalAlignment.matches(
                frames,
                { p -> progress("Aligning overlap detail", .86 + .08 * p) },
                check,
            )
        frames.forEachIndexed { i, frame ->
            check()
            progress(
                "Correcting small viewpoint shifts · ${i+1}/${frames.size}",
                .94 + .06 * i / frames.size,
            )
            frame.mesh = MeshWarp.fit(i, frames, corrected + local)
        }
        val isolated = links.count { it == 0 }
        return RegistrationReport(
            buildList {
                if (isolated > 0)
                    add(
                        "$isolated directions have no reliable visual matches; gyro orientation is retained there. Inspect blank walls and sky seams."
                    )
                if (percentile(after, .9) > 1.0)
                    add(
                        "Nearby objects or movement leave some alignment uncertainty. Inspect those seams in the viewer."
                    )
            },
            accepted,
            observations.size,
            isolated,
            before,
            percentile(after, .5),
            percentile(after, .9),
            frames.count { it.mesh != null },
            focal,
            local.size,
        )
    }

    /** IRLS Gauss–Newton on SO(3), with a soft orientation prior and a bounded trust step. */
    private fun calibrated(v: V3, scale: Double) = V3(v.x / scale, v.y / scale, v.z).unit()

    internal fun solve(
        frames: List<Prepared>,
        observations: List<Observation>,
        iteration: (Int) -> Unit,
    ): Double {
        if (observations.isEmpty()) return 1.0
        val n = frames.size * 3 + 1
        val fIndex = n - 1
        var logF = 0.0
        val refineFocal =
            observations.map { it.a to it.b }.distinct().size >= 6 &&
                observations.flatMap { listOf(it.a, it.b) }.distinct().size >= 6
        val counts = observations.groupingBy { it.a to it.b }.eachCount()
        repeat(25) { iter ->
            iteration(iter)
            val h = DoubleArray(n * n)
            val rhs = DoubleArray(n)
            observations.forEach { o ->
                val cu = calibrated(o.u, exp(logF))
                val cv = calibrated(o.v, exp(logF))
                val u = frames[o.a].rotation.rotate(cu)
                val v = frames[o.b].rotation.rotate(cv)
                fun derivative(ray: V3) =
                    ray * (ray.x * ray.x + ray.y * ray.y) - V3(ray.x, ray.y, 0.0)
                val df =
                    frames[o.a].rotation.rotate(derivative(cu)) -
                        frames[o.b].rotation.rotate(derivative(cv))
                val cross = u.cross(v)
                val dot = u.dot(v)
                val residual = (u - v).length()
                // Each overlap gets a bounded vote, so a carpet cannot overwhelm all walls.
                val weight =
                    min(1.0, .008 / residual.coerceAtLeast(1e-9)) /
                        sqrt(counts.getValue(o.a to o.b).toDouble())
                val ua = doubleArrayOf(u.x, u.y, u.z)
                val va = doubleArrayOf(v.x, v.y, v.z)
                val cr = doubleArrayOf(cross.x, cross.y, cross.z)
                val a = o.a * 3
                val b = o.b * 3
                val af = u.cross(df)
                val bf = v.cross(df) * -1.0
                val ha = doubleArrayOf(af.x, af.y, af.z)
                val hb = doubleArrayOf(bf.x, bf.y, bf.z)
                h[fIndex * n + fIndex] += weight * df.dot(df)
                rhs[fIndex] -= weight * df.dot(u - v)
                for (k in 0..2) {
                    h[(a + k) * n + fIndex] += weight * ha[k]
                    h[fIndex * n + a + k] += weight * ha[k]
                    h[(b + k) * n + fIndex] += weight * hb[k]
                    h[fIndex * n + b + k] += weight * hb[k]
                }
                for (r in 0..2) {
                    rhs[a + r] += weight * cr[r]
                    rhs[b + r] -= weight * cr[r]
                    for (c in 0..2) {
                        val identity = if (r == c) 1.0 else 0.0
                        h[(a + r) * n + a + c] += weight * (identity - ua[r] * ua[c])
                        h[(b + r) * n + b + c] += weight * (identity - va[r] * va[c])
                        h[(a + r) * n + b + c] += weight * (va[r] * ua[c] - dot * identity)
                        h[(b + c) * n + a + r] += weight * (va[r] * ua[c] - dot * identity)
                    }
                }
            }
            for (i in frames.indices) {
                val delta = (frames[i].capture.rotation * frames[i].rotation.inverse()).normalized()
                val sign = if (delta.w < 0) -1.0 else 1.0
                val prior =
                    doubleArrayOf(delta.x * 2 * sign, delta.y * 2 * sign, delta.z * 2 * sign)
                for (c in 0..2) {
                    val j = i * 3 + c
                    h[j * n + j] += (if (c == 1) .006 else .201)
                    rhs[j] += (if (c == 1) .005 else .2) * prior[c]
                }
            }
            h[fIndex * n + fIndex] += if (refineFocal) .05 else 1e9
            rhs[fIndex] -= .05 * logF
            val hm = Mat(n, n, CvType.CV_64F)
            val bm = Mat(n, 1, CvType.CV_64F)
            val result = Mat()
            try {
                hm.put(0, 0, *h)
                bm.put(0, 0, *rhs)
                if (!Core.solve(hm, bm, result, Core.DECOMP_CHOLESKY)) return exp(logF)
                val step = DoubleArray(n)
                result.get(0, 0, step)
                logF = (logF + step[fIndex].coerceIn(-.025, .025)).coerceIn(ln(.8), ln(1.3))
                var maximum = abs(step[fIndex])
                for (i in frames.indices) {
                    var v = V3(step[i * 3], step[i * 3 + 1], step[i * 3 + 2])
                    val length = v.length()
                    maximum = max(maximum, length)
                    if (length > .07) v = v * (.07 / length)
                    val q = (Q.axis(v) * frames[i].rotation).normalized()
                    if (q.angle(frames[i].capture.rotation) < 25.0) frames[i].rotation = q
                }
                if (maximum < 1e-5) return exp(logF)
            } finally {
                hm.release()
                bm.release()
                result.release()
            }
        }
        return exp(logF)
    }
}
