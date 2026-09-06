package app.hdri

import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.*
import app.hdri.processing.GroundFill
import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs

class GroundFillTest {
    @Test
    fun fillRemovesNadirObjectPreservesMeasuredRegionAndHasNoPolarStripe() {
        assertTrue(OpenCVLoader.initLocal())
        val w = 1024
        val h = 512
        val pixels = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val ray = Sphere.ray(x + .5, y + .5, w, h)
            val px = ray.x / max(.1, -ray.y)
            val py = ray.z / max(.1, -ray.y)
            val floor = .6 + .04 * sin(px * 28) * cos(py * 31)
            val foot = ray.y < sin(Math.toRadians(-66.0))
            for (c in 0..2) pixels[(y * w + x) * 3 + c] =
                (if (foot) if (c == 2) 50.0 else .01 else floor * listOf(.8, 1.0, 1.2)[c]).toFloat()
        }
        val image = Mat(h, w, CvType.CV_32FC3)
        try {
            image.put(0, 0, pixels)
            var last = 0.0
            GroundFill.apply(
                image,
                {
                    assertTrue(it >= last)
                    last = it
                },
                {},
            )
            assertEquals(1.0, last, 1e-9)
            assertTrue(Core.checkRange(image))
            val output = FloatArray(pixels.size)
            image.get(0, 0, output)
            for (y in 0 until h) for (x in 0 until w) {
                if (y < h * 145 / 180)
                    for (c in 0..2) assertEquals(
                        pixels[(y * w + x) * 3 + c],
                        output[(y * w + x) * 3 + c],
                        0f,
                    )
                if (y > h * 160 / 180) {
                    val p = (y * w + x) * 3
                    assertTrue(output[p + 2] < 1f)
                    assertEquals(1.5, (output[p + 2] / output[p]).toDouble(), .03)
                }
            }
            for (c in 0..2) {
                val pole = (0 until w).map { output[((h - 1) * w + it) * 3 + c] }
                assertTrue(pole.max() - pole.min() < .015f)
                for (y in h * 160 / 180 until h) assertEquals(
                    output[y * w * 3 + c],
                    output[(y * w + w - 1) * 3 + c],
                    .03f,
                )
            }
            val preview = Mat()
            image.convertTo(preview, CvType.CV_8UC3, 180.0)
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dir = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
                assertTrue(Imgcodecs.imwrite(File(dir, "ground-fill.png").path, preview))
            } finally {
                preview.release()
            }
        } finally {
            image.release()
        }
    }
}
