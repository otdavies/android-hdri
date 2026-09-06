package app.hdri.processing

import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import org.opencv.video.Video

/** Subpixel rotation/translation on exposure-invariant edges, with a conservative MTB fallback. */
internal object BracketAlignment {
    fun align(images: List<Mat>, check: () -> Unit, warning: (String) -> Unit): List<Mat> {
        val output = mutableListOf<Mat>()
        val small = mutableListOf<Mat>()
        val edges = mutableListOf<Mat>()
        val mtb = Photo.createAlignMTB(4, 4, false)
        val reference = images.size / 2
        val scale = min(1.0, 256.0 / max(images[0].cols(), images[0].rows()))
        try {
            images.forEach { image ->
                check()
                val gray = Mat()
                val edge = Mat()
                val gx = Mat()
                val gy = Mat()
                try {
                    Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
                    Imgproc.resize(
                        gray,
                        gray,
                        Size(image.cols() * scale, image.rows() * scale),
                        0.0,
                        0.0,
                        Imgproc.INTER_AREA,
                    )
                    small += gray
                    val smooth = Mat()
                    try {
                        Imgproc.GaussianBlur(gray, smooth, Size(3.0, 3.0), 0.0)
                        smooth.convertTo(smooth, CvType.CV_32F, 1.0 / 255)
                        Imgproc.Sobel(smooth, gx, CvType.CV_32F, 1, 0)
                        Imgproc.Sobel(smooth, gy, CvType.CV_32F, 0, 1)
                        Core.magnitude(gx, gy, edge)
                        Core.sqrt(edge, edge)
                    } finally {
                        smooth.release()
                    }
                    edges += edge
                } catch (e: Exception) {
                    if (gray !in small) gray.release()
                    edge.release()
                    throw e
                } finally {
                    gx.release()
                    gy.release()
                }
            }
            images.forEachIndexed { i, image ->
                check()
                if (i == reference) {
                    output += image.clone()
                    return@forEachIndexed
                }
                val shift = mtb.calculateShift(small[reference], small[i])
                val limit = min(small[i].cols(), small[i].rows()) * .04
                val validShift = abs(shift.x) <= limit && abs(shift.y) <= limit
                val warp = Mat.eye(2, 3, CvType.CV_32F)
                val mask = Mat()
                var values =
                    floatArrayOf(
                        1f,
                        0f,
                        if (validShift) -shift.x.toFloat() else 0f,
                        0f,
                        1f,
                        if (validShift) -shift.y.toFloat() else 0f,
                    )
                warp.put(0, 0, values)
                val aligned = Mat()
                try {
                    var accepted = false
                    try {
                        val score =
                            Video.findTransformECC(
                                edges[reference],
                                edges[i],
                                warp,
                                Video.MOTION_EUCLIDEAN,
                                TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 35, 1e-4),
                                mask,
                                3,
                            )
                        val candidate = FloatArray(6)
                        warp.get(0, 0, candidate)
                        val angle = abs(atan2(candidate[3].toDouble(), candidate[0].toDouble()))
                        if (
                            score > .5 &&
                                candidate.all { it.isFinite() } &&
                                angle < Math.toRadians(3.0) &&
                                abs(candidate[2]) < limit &&
                                abs(candidate[5]) < limit
                        ) {
                            values = candidate
                            accepted = true
                        }
                    } catch (_: CvException) {
                        /* A flat/clipped exposure may not constrain ECC. */
                    }
                    if (!accepted && !validShift)
                        warning(
                            "Some exposures lacked alignment detail; their captured position was retained."
                        )
                    values[2] /= scale.toFloat()
                    values[5] /= scale.toFloat()
                    warp.put(0, 0, values)
                    Imgproc.warpAffine(
                        image,
                        aligned,
                        warp,
                        image.size(),
                        Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
                        Core.BORDER_REPLICATE,
                    )
                    output += aligned
                } catch (e: Exception) {
                    aligned.release()
                    throw e
                } finally {
                    warp.release()
                    mask.release()
                }
            }
            return output
        } catch (e: Exception) {
            output.forEach { it.release() }
            throw e
        } finally {
            small.forEach { it.release() }
            edges.forEach { it.release() }
            mtb.clear()
        }
    }
}
