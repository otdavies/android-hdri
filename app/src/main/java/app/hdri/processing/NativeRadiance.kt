package app.hdri.processing

import app.hdri.core.Radiance
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** The reference RGB weighting, evaluated with vectorized native OpenCV kernels. */
internal object NativeRadiance {
    fun merge(images: List<Mat>, times: DoubleArray, response: FloatArray): Radiance.Merge {
        require(
            images.size == times.size &&
                images.isNotEmpty() &&
                times.all { it > 0 && it.isFinite() }
        )
        require(response.size == 768 && response.all { it.isFinite() && it >= 0 })
        val rows = images[0].rows()
        val cols = images[0].cols()
        require(
            images.all { it.rows() == rows && it.cols() == cols && it.type() == CvType.CV_8UC3 }
        )
        val sum = Mat.zeros(rows, cols, CvType.CV_32FC3)
        val denominator = Mat.zeros(rows, cols, CvType.CV_32F)
        val lut = Mat(1, 256, CvType.CV_32FC3)
        val peak = Mat()
        val inverse = Mat()
        val weight = Mat()
        val weight3 = Mat()
        val linear = Mat()
        val mask = Mat()
        val empty = Mat()
        val channels = mutableListOf<Mat>()
        val smallest = times.indices.minBy { times[it] }
        val largest = times.indices.maxBy { times[it] }
        val samples = mutableListOf<ByteArray>()
        var clipped = 0
        fun maxChannel(image: Mat) {
            channels.forEach { it.release() }
            channels.clear()
            Core.split(image, channels)
            Core.max(channels[0], channels[1], peak)
            Core.max(peak, channels[2], peak)
        }
        try {
            lut.put(0, 0, response)
            images.forEachIndexed { index, image ->
                maxChannel(image)
                if (index == smallest) {
                    Core.inRange(peak, Scalar.all(252.0), Scalar.all(255.0), mask)
                    clipped = Core.countNonZero(mask)
                }
                peak.convertTo(inverse, -1, -1.0, 255.0)
                Core.min(peak, inverse, peak)
                peak.convertTo(weight, CvType.CV_32F)
                Core.multiply(weight, weight, weight)
                Core.add(denominator, weight, denominator)
                Imgproc.cvtColor(weight, weight3, Imgproc.COLOR_GRAY2BGR)
                Core.LUT(image, lut, linear)
                Core.multiply(linear, weight3, linear, 1.0 / times[index])
                Core.add(sum, linear, sum)
                // Disagreement is diagnostic, not a hard, contour-producing pixel switch.
                val sample = Mat()
                try {
                    val scale = min(1.0, 128.0 / max(cols, rows))
                    Imgproc.resize(
                        image,
                        sample,
                        Size(cols * scale, rows * scale),
                        0.0,
                        0.0,
                        Imgproc.INTER_AREA,
                    )
                    samples += ByteArray((sample.total() * 3).toInt()).also { sample.get(0, 0, it) }
                } finally {
                    sample.release()
                }
            }
            Core.inRange(denominator, Scalar.all(0.0), Scalar.all(0.0), empty)
            Core.max(denominator, Scalar.all(1e-12), denominator)
            Imgproc.cvtColor(denominator, weight3, Imgproc.COLOR_GRAY2BGR)
            Core.divide(sum, weight3, sum)
            if (Core.countNonZero(empty) > 0) {
                Core.LUT(images[largest], lut, linear)
                linear.convertTo(linear, -1, 1.0 / times[largest])
                linear.copyTo(sum, empty)
                maxChannel(images[smallest])
                Core.inRange(peak, Scalar.all(252.0), Scalar.all(255.0), mask)
                Core.bitwise_and(mask, empty, mask)
                Core.LUT(images[smallest], lut, linear)
                linear.convertTo(linear, -1, 1.0 / times[smallest])
                linear.copyTo(sum, mask)
            }
            val rgb = FloatArray(rows * cols * 3)
            sum.get(0, 0, rgb)
            val diagnostic = Radiance.merge(samples, times, response)
            val moving =
                (diagnostic.moving.toDouble() / (samples[0].size / 3) * rows * cols).roundToInt()
            return Radiance.Merge(rgb, clipped, moving)
        } finally {
            channels.forEach { it.release() }
            listOf(sum, denominator, lut, peak, inverse, weight, weight3, linear, mask, empty)
                .forEach { it.release() }
        }
    }
}
