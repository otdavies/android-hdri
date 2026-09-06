package app.hdri.processing

import app.hdri.core.*
import app.hdri.data.*
import java.io.File
import kotlin.math.*
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs

/**
 * An analytic HDR light stage exercises the real merge, registration, seams and export path
 * offline.
 */
object SampleCapture {
    fun light(ray: V3): V3 {
        val grid = .65 + .35 * sin(ray.x * 63 + sin(ray.y * 47)) * cos(ray.z * 57)
        val base = .18 + max(0.0, ray.y) * .8
        val window =
            exp(-((ray.x - .5).pow(2) + (ray.y - .25).pow(2) + (ray.z + .82).pow(2)) * 170) * 30
        val floor = if (ray.y < -.35) .08 * (1 + sin(ray.x * 80) * sin(ray.z * 70)) else 0.0
        return V3(
            (base * 1.15 + floor) * grid + window,
            (base + floor) * grid + window * .93,
            (base * .8 + floor) * grid + window * .75,
        )
    }

    fun create(
        store: SessionStore,
        p: Project,
        progress: (String, Double) -> Unit,
        check: () -> Unit,
    ): Project {
        kotlin.check(OpenCVLoader.initLocal()) { "Image engine unavailable." }
        val w = 320
        val h = 240
        val f = w / (2 * tan(Math.toRadians(50.0)))
        val lens = Lens(w, h, f, f, w / 2.0, h / 2.0)
        val targets = Sphere.targets(min(lens.fovX, lens.fovY))
        store.update(p.id) { it.copy(targets = targets) }
        val captures =
            targets.mapIndexed { i, t ->
                check()
                progress(
                    "Preparing sample exposures · ${i+1}/${targets.size}",
                    i.toDouble() / targets.size,
                )
                val q = Q.look(t.yaw, t.pitch)
                val times = doubleArrayOf(.004, .032, .256)
                val exposures =
                    times.mapIndexed { n, time ->
                        val bytes = ByteArray(w * h * 3)
                        for (y in 0 until h) for (x in 0 until w) {
                            val rgb = light(q.rotate(lens.ray(x + .5, y + .5)))
                            val j = (y * w + x) * 3
                            val channels = doubleArrayOf(rgb.z, rgb.y, rgb.x)
                            for (c in 0..2) bytes[j + c] =
                                (Radiance.srgb((channels[c] * time).coerceIn(0.0, 1.0)) * 255)
                                    .roundToInt()
                                    .coerceIn(0, 255)
                                    .toByte()
                        }
                        val mat = Mat(h, w, CvType.CV_8UC3)
                        mat.put(0, 0, bytes)
                        val name = "sample-${t.id}-$n.jpg"
                        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 99)
                        try {
                            kotlin.check(
                                Imgcodecs.imwrite(File(store.dir(p.id), name).path, mat, params)
                            )
                        } finally {
                            mat.release()
                            params.release()
                        }
                        Exposure(name, (time * 1e9).toLong(), 100, (i * 3 + n).toLong() + 1)
                    }
                Capture(t.id, q, V3.ZERO, lens, exposures)
            }
        return store.update(p.id) { it.copy(captures = captures, targets = targets) }
    }
}
