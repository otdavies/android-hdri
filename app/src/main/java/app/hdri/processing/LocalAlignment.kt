package app.hdri.processing

import app.hdri.core.*
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.DISOpticalFlow

/** Add local correspondences only where forward/backward flow and patch appearance agree. */
internal object LocalAlignment {
    fun matches(
        frames: List<Prepared>,
        progress: (Double) -> Unit,
        check: () -> Unit,
    ): List<Observation> {
        val images = mutableListOf<Mat>()
        val arrays = mutableListOf<ByteArray>()
        val lenses = mutableListOf<Lens>()
        val observations = mutableListOf<Observation>()
        val flow = DISOpticalFlow.create(DISOpticalFlow.PRESET_FAST)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        try {
            frames.forEach { f ->
                check()
                val image = Imgcodecs.imread(f.preview.path, Imgcodecs.IMREAD_GRAYSCALE)
                val scale = min(1.0, 320.0 / max(image.cols(), image.rows()))
                Imgproc.resize(image, image, Size(image.cols() * scale, image.rows() * scale))
                clahe.apply(image, image)
                images += image
                lenses += f.lens.scaled(image.cols(), image.rows())
                arrays += ByteArray(image.total().toInt()).also { image.get(0, 0, it) }
            }
            val pairs = buildList {
                for (a in frames.indices) for (b in a + 1 until frames.size) if (
                    frames[a]
                        .rotation
                        .rotate(V3.FORWARD)
                        .angle(frames[b].rotation.rotate(V3.FORWARD)) < 52.0
                )
                    add(a to b)
            }
            pairs.forEachIndexed { index, (a, b) ->
                check()
                progress(index.toDouble() / max(1, pairs.size))
                val la = lenses[a]
                val lb = lenses[b]
                val w = la.width
                val h = la.height
                val rotation = frames[b].rotation.inverse() * frames[a].rotation
                val mx = FloatArray(w * h) { -1f }
                val my = FloatArray(w * h) { -1f }
                var overlap = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val p =
                        lb.project(rotation.rotate(la.ray(x.toDouble(), y.toDouble()))) ?: continue
                    if (
                        p.first < 8 ||
                            p.second < 8 ||
                            p.first > lb.width - 9 ||
                            p.second > lb.height - 9
                    )
                        continue
                    mx[y * w + x] = p.first.toFloat()
                    my[y * w + x] = p.second.toFloat()
                    overlap++
                }
                if (overlap < w * h / 10) return@forEachIndexed
                val mapX = Mat(h, w, CvType.CV_32F)
                val mapY = Mat(h, w, CvType.CV_32F)
                val warped = Mat()
                val forward = Mat()
                val backward = Mat()
                try {
                    mapX.put(0, 0, mx)
                    mapY.put(0, 0, my)
                    Imgproc.remap(
                        images[b],
                        warped,
                        mapX,
                        mapY,
                        Imgproc.INTER_LINEAR,
                        Core.BORDER_REPLICATE,
                    )
                    // Fill invalid areas with the template so the flow pyramid has no black border.
                    val bytes = ByteArray(w * h)
                    warped.get(0, 0, bytes)
                    for (p in bytes.indices) if (mx[p] < 0) bytes[p] = arrays[a][p]
                    warped.put(0, 0, bytes)
                    flow.calc(images[a], warped, forward)
                    check()
                    flow.calc(warped, images[a], backward)
                    val ab = FloatArray(w * h * 2)
                    val ba = FloatArray(w * h * 2)
                    forward.get(0, 0, ab)
                    backward.get(0, 0, ba)
                    for (y in 12 until h - 12 step 12) for (x in 12 until w - 12 step 12) {
                        val p = y * w + x
                        val dx = ab[p * 2].toDouble()
                        val dy = ab[p * 2 + 1].toDouble()
                        if (!dx.isFinite() || !dy.isFinite() || hypot(dx, dy) > 16.0) continue
                        val xx = (x + dx).roundToInt()
                        val yy = (y + dy).roundToInt()
                        if (xx !in 10 until w - 10 || yy !in 10 until h - 10) continue
                        val q = yy * w + xx
                        if (
                            mx[p] < 0 || mx[q] < 0 || hypot(dx + ba[q * 2], dy + ba[q * 2 + 1]) > .8
                        )
                            continue
                        if (mx[(yy - 5) * w + xx - 5] < 0 || mx[(yy + 5) * w + xx + 5] < 0) continue
                        var sa = 0.0
                        var sb = 0.0
                        var aa = 0.0
                        var bb = 0.0
                        var product = 0.0
                        var count = 0
                        for (oy in -4..4) for (ox in -4..4) {
                            val av = (arrays[a][(y + oy) * w + x + ox].toInt() and 255).toDouble()
                            val bv = (bytes[(yy + oy) * w + xx + ox].toInt() and 255).toDouble()
                            sa += av
                            sb += bv
                            aa += av * av
                            bb += bv * bv
                            product += av * bv
                            count++
                        }
                        val va = aa - sa * sa / count
                        val vb = bb - sb * sb / count
                        if (
                            min(va, vb) / count < 35.0 ||
                                (product - sa * sb / count) / sqrt(va * vb) < .9
                        )
                            continue
                        val u = la.ray(x.toDouble(), y.toDouble())
                        val v = rotation.rotate(la.ray(x + dx, y + dy))
                        observations += Observation(a, b, u, v)
                    }
                } finally {
                    mapX.release()
                    mapY.release()
                    warped.release()
                    forward.release()
                    backward.release()
                }
            }
            return observations
        } finally {
            images.forEach { it.release() }
            flow.collectGarbage()
            flow.clear()
            clahe.collectGarbage()
            clahe.clear()
        }
    }
}
