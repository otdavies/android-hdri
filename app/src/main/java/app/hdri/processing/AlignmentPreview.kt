package app.hdri.processing

import java.io.File
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Local matching uses bracket detail in dark areas; coarse registration retains the camera image.
 */
internal object AlignmentPreview {
    fun read(file: File, maxEdge: Int): Mat {
        val source = FloatImages.read(file)
        val small = Mat()
        try {
            val scale = min(1.0, maxEdge.toDouble() / max(source.cols(), source.rows()))
            Imgproc.resize(
                source,
                small,
                Size(
                    (source.cols() * scale).roundToInt().toDouble(),
                    (source.rows() * scale).roundToInt().toDouble(),
                ),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            val bgr = FloatArray((small.total() * 3).toInt())
            small.get(0, 0, bgr)
            val light =
                FloatArray(small.total().toInt()) { p ->
                    ln(
                            max(
                                1e-8,
                                .0722 * bgr[p * 3] + .7152 * bgr[p * 3 + 1] + .2126 * bgr[p * 3 + 2],
                            )
                        )
                        .toFloat()
                }
            val sample = light.filterIndexed { i, _ -> i % 13 == 0 }.sorted()
            val low = sample[sample.size / 100]
            val high = sample[min(sample.lastIndex, sample.size * 99 / 100)]
            val gain = 255 / max(.3f, high - low)
            val bytes =
                ByteArray(light.size) {
                    ((light[it] - low) * gain).toInt().coerceIn(0, 255).toByte()
                }
            return Mat(small.rows(), small.cols(), CvType.CV_8UC1).also { it.put(0, 0, bytes) }
        } finally {
            source.release()
            small.release()
        }
    }
}
