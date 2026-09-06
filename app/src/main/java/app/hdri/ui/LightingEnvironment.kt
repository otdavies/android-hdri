package app.hdri.ui

import app.hdri.core.LightingMap
import java.io.File
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

internal data class LightingEnvironment(
    val reflection: LightingMap,
    val diffuse: LightingMap,
    val scale: Float,
) {
    companion object {
        fun load(file: File, progress: (String, Float) -> Unit): LightingEnvironment {
            progress("Reading HDR radiance", .05f)
            check(OpenCVLoader.initLocal()) { "The HDR reader could not start." }
            val source = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_UNCHANGED)
            val reduced = Mat()
            try {
                check(
                    !source.empty() &&
                        source.type() == CvType.CV_32FC3 &&
                        source.cols() == 2 * source.rows()
                ) {
                    "The HDR environment could not be read. Rebuild this capture from its saved photos."
                }
                progress("Preparing reflections", .25f)
                // Keep the saved panorama's detail when looking around. Only diffuse
                // integration needs a small map; shrinking reflections also softens the viewer.
                val width =
                    listOf(128, 256, 512, 1024, 2048, 4096).lastOrNull { it <= source.cols() }
                        ?: 128
                val pixels =
                    if (source.cols() == width) source
                    else {
                        Imgproc.resize(
                            source,
                            reduced,
                            Size(width.toDouble(), width / 2.0),
                            0.0,
                            0.0,
                            Imgproc.INTER_AREA,
                        )
                        reduced
                    }
                val data = FloatArray(width * width / 2 * 3)
                pixels.get(0, 0, data)
                source.release()
                reduced.release()
                for (i in data.indices step 3) {
                    val blue = data[i]
                    data[i] = data[i + 2]
                    data[i + 2] = blue
                }
                val map = LightingMap(width, width / 2, data)
                val diffuse =
                    map.reduced(128, 64).diffuse {
                        progress("Integrating diffuse light", .35f + .6f * it)
                    }
                return LightingEnvironment(
                    map,
                    diffuse,
                    (1.0 / map.meanLuminance().coerceAtLeast(1e-12)).toFloat(),
                )
            } finally {
                source.release()
                reduced.release()
            }
        }
    }
}
