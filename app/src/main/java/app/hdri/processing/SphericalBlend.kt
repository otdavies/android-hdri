package app.hdri.processing

import android.graphics.Bitmap
import app.hdri.core.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal data class SeamMap(
    val width: Int,
    val height: Int,
    val labels: IntArray,
    val exposure: Double,
    val uncovered: List<V3>,
)

/**
 * Spherical inverse warping avoids pole singularities and includes longitude wrap in every tile.
 */
internal data class RenderReport(val decodes: Int, val cachePeakBytes: Long)

internal object SphericalBlend {

    private data class Warp(val image: Mat, val validity: Mat)

    private fun warp(
        frame: Prepared,
        image: Mat,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        outW: Int,
        outH: Int,
    ): Warp {
        val lens = frame.lens.scaled(image.cols(), image.rows())
        val q = frame.rotation.inverse()
        val rx = q.rotate(V3(1.0, 0.0, 0.0))
        val ry = q.rotate(V3(0.0, 1.0, 0.0))
        val rz = q.rotate(V3(0.0, 0.0, 1.0))
        val mx = FloatArray(w * h) { -1f }
        val my = FloatArray(w * h) { -1f }
        val valid = FloatArray(w * h)
        val displacement = DoubleArray(2)
        val sx = DoubleArray(w) { sin(((x0 + it + .5) / outW - .5) * 2 * PI) }
        val cx = DoubleArray(w) { cos(((x0 + it + .5) / outW - .5) * 2 * PI) }
        for (y in 0 until h) {
            val pitch = (.5 - (y0 + y + .5) / outH) * PI
            val sp = sin(pitch)
            val cp = cos(pitch)
            for (x in 0 until w) {
                val wx = sx[x] * cp
                val wz = -cx[x] * cp
                val ax = rx.x * wx + ry.x * sp + rz.x * wz
                val ay = rx.y * wx + ry.y * sp + rz.y * wz
                val az = rx.z * wx + ry.z * sp + rz.z * wz
                if (az >= -.01) continue
                var px = lens.cx - lens.fx * ax / az
                var py = lens.cy + lens.fy * ay / az
                frame.mesh?.let { mesh ->
                    mesh.offset(px / lens.width, py / lens.height, displacement)
                    px += displacement[0] * lens.width
                    py += displacement[1] * lens.height
                }
                // Extend the actual image edge into the pyramid halo. Invalid (-1,-1)
                // everywhere used to smear the source's top-left color into every seam.
                val i = y * w + x
                mx[i] = px.toFloat()
                my[i] = py.toFloat()
                val edge =
                    min(
                        min(px / lens.width, 1 - px / lens.width),
                        min(py / lens.height, 1 - py / lens.height),
                    )
                if (edge < .045) continue
                valid[i] = (min(1.0, (edge - .045) * 5).pow(2)).toFloat()
            }
        }
        val mapX = Mat(h, w, CvType.CV_32FC1)
        val mapY = Mat(h, w, CvType.CV_32FC1)
        val mask = Mat(h, w, CvType.CV_32FC1)
        mapX.put(0, 0, mx)
        mapY.put(0, 0, my)
        mask.put(0, 0, valid)
        val warped = Mat()
        Imgproc.remap(
            image,
            warped,
            mapX,
            mapY,
            Imgproc.INTER_LINEAR,
            Core.BORDER_REPLICATE,
            Scalar.all(0.0),
        )
        mapX.release()
        mapY.release()
        return Warp(warped, mask)
    }

    fun seams(
        frames: List<Prepared>,
        progress: (String, Double) -> Unit,
        check: () -> Unit,
    ): SeamMap {
        val w = 512
        val h = 256
        val n = w * h
        val labels = IntArray(n) { -1 }
        val scores = FloatArray(n)
        val layers =
            frames.mapIndexed { index, frame ->
                check()
                progress(
                    "Choosing clean overlaps · ${index+1}/${frames.size}",
                    .8 * index / frames.size,
                )
                val image = FloatImages.read(frame.hdr)
                val small = Mat()
                Imgproc.resize(image, small, Size(480.0, 480.0 * image.rows() / image.cols()))
                image.release()
                val warp = warp(frame, small, 0, 0, w, h, w, h)
                small.release()
                val rgb = FloatArray(n * 3)
                val weights = FloatArray(n)
                warp.image.get(0, 0, rgb)
                warp.validity.get(0, 0, weights)
                warp.image.release()
                warp.validity.release()
                for (i in 0 until n) {
                    if (weights[i] > scores[i]) {
                        scores[i] = weights[i]
                        labels[i] = index
                    }
                    for (c in 0..2) rgb[i * 3 + c] = ln(1 + max(0f, rgb[i * 3 + c])).toFloat()
                }
                SeamLayer(rgb, weights)
            }
        SeamOptimizer.optimize(
            layers,
            labels,
            w,
            h,
            { p -> progress("Finding continuous seams", .8 + .2 * p) },
            check,
        )
        var logLum = 0.0
        var count = 0
        val holes = mutableListOf<V3>()
        for (i in 0 until n) {
            val label = labels[i]
            if (label >= 0) {
                val l = layers[label]
                val lum =
                    .0722 * expm1(l.rgb[i * 3].toDouble()) +
                        .7152 * expm1(l.rgb[i * 3 + 1].toDouble()) +
                        .2126 * expm1(l.rgb[i * 3 + 2].toDouble())
                logLum += ln(max(1e-6, lum))
                count++
            } else if (i % 5 == 0) {
                val ray = Sphere.ray((i % w) + .5, (i / w) + .5, w, h)
                if (holes.size < 16 && holes.all { it.angle(ray) > 18 }) holes += ray
            }
        }
        return SeamMap(w, h, labels, .18 / exp(logLum / max(1, count)), holes)
    }

    private fun intersects(
        frame: Prepared,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        outW: Int,
        outH: Int,
    ): Boolean {
        val center = Sphere.ray(x + w / 2.0, y + h / 2.0, outW, outH)
        val radius = w * 180.0 / outW + h * 90.0 / outH
        val fov = hypot(frame.lens.fovX, frame.lens.fovY) * .55
        return center.angle(frame.rotation.rotate(V3.FORWARD)) < radius + fov
    }

    private fun pyramid(source: Mat, levels: Int): List<Mat> {
        val out = mutableListOf(source.clone())
        repeat(levels - 1) {
            val next = Mat()
            Imgproc.pyrDown(out.last(), next)
            out += next
        }
        return out
    }

    private fun blendTile(
        frames: List<Prepared>,
        seams: SeamMap,
        cache: HdrCache,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        outW: Int,
        outH: Int,
        check: () -> Unit,
    ): Mat {
        val levels = 5
        val sums = mutableListOf<Mat>()
        val denominators = mutableListOf<Mat>()
        var lw = w
        var lh = h
        repeat(levels) {
            sums += Mat.zeros(lh, lw, CvType.CV_32FC3)
            denominators += Mat.zeros(lh, lw, CvType.CV_32FC1)
            lw = (lw + 1) / 2
            lh = (lh + 1) / 2
        }
        try {
            frames.forEachIndexed { index, frame ->
                if (!intersects(frame, x, y, w, h, outW, outH)) return@forEachIndexed
                check()
                val maskData = FloatArray(w * h)
                var has = false
                for (yy in 0 until h) for (xx in 0 until w) {
                    val sx =
                        floor((x + xx + .5) / outW * seams.width).toInt().let {
                            ((it % seams.width) + seams.width) % seams.width
                        }
                    val sy =
                        floor((y + yy + .5) / outH * seams.height)
                            .toInt()
                            .coerceIn(0, seams.height - 1)
                    if (seams.labels[sy * seams.width + sx] == index) {
                        maskData[yy * w + xx] = 1f
                        has = true
                    }
                }
                if (!has) return@forEachIndexed
                val source = cache.get(frame.hdr)
                val warp = warp(frame, source, x, y, w, h, outW, outH)
                val mask = Mat(h, w, CvType.CV_32FC1)
                mask.put(0, 0, maskData)
                Core.multiply(mask, warp.validity, mask)
                warp.validity.release()
                val gp = pyramid(warp.image, levels)
                val wp = pyramid(mask, levels)
                warp.image.release()
                mask.release()
                try {
                    for (level in 0 until levels) {
                        val band = gp[level].clone()
                        if (level < levels - 1) {
                            val up = Mat()
                            Imgproc.pyrUp(gp[level + 1], up, gp[level].size())
                            Core.subtract(band, up, band)
                            up.release()
                        }
                        val weight3 = Mat()
                        Imgproc.cvtColor(wp[level], weight3, Imgproc.COLOR_GRAY2BGR)
                        Core.multiply(band, weight3, band)
                        Core.add(sums[level], band, sums[level])
                        Core.add(denominators[level], wp[level], denominators[level])
                        band.release()
                        weight3.release()
                    }
                } finally {
                    gp.forEach { it.release() }
                    wp.forEach { it.release() }
                }
            }
            for (level in 0 until levels) {
                Core.max(denominators[level], Scalar.all(1e-7), denominators[level])
                val weight3 = Mat()
                Imgproc.cvtColor(denominators[level], weight3, Imgproc.COLOR_GRAY2BGR)
                Core.divide(sums[level], weight3, sums[level])
                weight3.release()
            }
            var result = sums.last().clone()
            for (level in levels - 2 downTo 0) {
                val up = Mat()
                Imgproc.pyrUp(result, up, sums[level].size())
                result.release()
                Core.add(up, sums[level], up)
                result = up
            }
            Core.max(result, Scalar.all(0.0), result)
            return result
        } finally {
            sums.forEach { it.release() }
            denominators.forEach { it.release() }
        }
    }

    fun render(
        frames: List<Prepared>,
        seams: SeamMap,
        width: Int,
        hdrFile: File,
        jpegFile: File,
        progress: (String, Double) -> Unit,
        check: () -> Unit,
    ): RenderReport {
        val height = width / 2
        val halo = 64
        val tileW = 512
        val stripH = 256
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val cache = HdrCache()
        val tone =
            IntArray(65536) { (Radiance.srgb(it / 65535.0) * 255).roundToInt().coerceIn(0, 255) }
        try {
            HdrWriter(FileOutputStream(hdrFile).buffered(128 * 1024), width, height).use { writer ->
                for (y in 0 until height step stripH) {
                    val sh = min(stripH, height - y)
                    val strip = FloatArray(width * sh * 3)
                    for (x in 0 until width step tileW) {
                        check()
                        val tw = min(tileW, width - x)
                        progress(
                            "Blending sphere · row ${y+1} of $height",
                            (y * width + x * sh).toDouble() / (width * height),
                        )
                        val top = max(0, y - halo)
                        val bottom = min(height, y + sh + halo)
                        val tile =
                            blendTile(
                                frames,
                                seams,
                                cache,
                                x - halo,
                                top,
                                tw + halo * 2,
                                bottom - top,
                                width,
                                height,
                                check,
                            )
                        val region = tile.submat(Rect(halo, y - top, tw, sh))
                        val row = FloatArray(tw * 3)
                        for (yy in 0 until sh) {
                            region.get(yy, 0, row)
                            row.copyInto(strip, (yy * width + x) * 3)
                        }
                        region.release()
                        tile.release()
                    }
                    val pixels = IntArray(width * sh)
                    for (i in pixels.indices) {
                        val p = i * 3
                        fun channel(v: Float): Int {
                            val value = max(0.0, v * seams.exposure)
                            return tone[
                                (value / (1 + value) * 65535).roundToInt().coerceIn(0, 65535)]
                        }
                        pixels[i] =
                            (255 shl 24) or
                                (channel(strip[p + 2]) shl 16) or
                                (channel(strip[p + 1]) shl 8) or
                                channel(strip[p])
                    }
                    bitmap.setPixels(pixels, 0, width, 0, y, width, sh)
                    for (yy in 0 until sh) {
                        check()
                        writer.row(strip, yy * width * 3)
                    }
                }
            }
            progress("Saving photosphere preview", .99)
            check()
            FileOutputStream(jpegFile).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) {
                    "Could not save the photosphere preview."
                }
                it.fd.sync()
            }
            return RenderReport(cache.decodes, cache.peakBytes)
        } finally {
            cache.close()
            bitmap.recycle()
        }
    }
}
