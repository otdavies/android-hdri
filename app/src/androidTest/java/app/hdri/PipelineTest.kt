package app.hdri

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.*
import app.hdri.data.*
import app.hdri.processing.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun radianceExportDecodesAsFloatHdrWithCorrectChannels() {
        assertTrue(OpenCVLoader.initLocal())
        val file = File(context.cacheDir, "roundtrip.hdr")
        try {
            HdrWriter(file.outputStream(), 16, 8).use { writer ->
                repeat(8) {
                    writer.row(
                        FloatArray(48) {
                            when (it % 3) {
                                0 -> 2f
                                1 -> 20f
                                else -> 200f
                            }
                        }
                    )
                }
            }
            val decoded = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_UNCHANGED)
            try {
                assertEquals(16, decoded.cols())
                assertEquals(8, decoded.rows())
                assertEquals(CvType.CV_32FC3, decoded.type())
                val pixel = decoded.get(4, 8)
                assertEquals(2.0, pixel[0], 1.0)
                assertEquals(20.0, pixel[1], 1.0)
                assertEquals(200.0, pixel[2], 1.0)
            } finally {
                decoded.release()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun completeSampleProducesCoveredHdrAndPhotoSphereMetadata() {
        val store = SessionStore(context)
        val initial = store.create(Quality.QUICK, true)
        val p = SampleCapture.create(store, initial, { _, _ -> }, {})
        val stages = mutableListOf<String>()
        val start = System.currentTimeMillis()
        HdrPipeline(store, p, { stage, _ -> if (stages.lastOrNull() != stage) stages += stage }, {})
            .run()
        val result = store.read(p.id)
        assertTrue(result.state in listOf("ready", "review"))
        assertEquals(1.0, result.progress, 1e-6)
        assertEquals(0L, store.storage(result).processing)
        store.requireSources(p.id)
        val report = JSONObject(File(store.dir(p.id), "report.json").readText())
        assertTrue(report.getDouble("coverage") > .998)
        val hdr =
            Imgcodecs.imread(
                File(store.dir(p.id), "environment.hdr").path,
                Imgcodecs.IMREAD_UNCHANGED,
            )
        try {
            assertEquals(2048, hdr.cols())
            assertEquals(1024, hdr.rows())
            assertTrue(Core.checkRange(hdr))
            val ratios = mutableListOf<Double>()
            val errors = mutableListOf<Pair<Double, Double>>()
            for (y in 30 until hdr.rows() - 30 step 39) for (x in
                10 until hdr.cols() - 10 step 41) {
                val expected =
                    SampleCapture.light(Sphere.ray(x + .5, y + .5, hdr.cols(), hdr.rows())).y
                val actual = hdr.get(y, x)[1]
                if (expected > .05) {
                    ratios += actual / expected
                    errors += actual to expected
                }
            }
            val scale = ratios.sorted()[ratios.size / 2]
            assertTrue(scale > 0 && scale.isFinite())
            val relative = errors.map { abs(it.first / scale - it.second) / it.second }.sorted()
            assertTrue(
                "Median radiometric error ${relative[relative.size/2]}",
                relative[relative.size / 2] < .35,
            )
            assertTrue(stages.any { it.startsWith("Blending sphere") })
            val jpeg =
                File(store.dir(p.id), "preview.jpg").readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(jpeg.contains("GPano:ProjectionType=\"equirectangular\""))
            val evidence =
                JSONObject()
                    .put("durationSeconds", (System.currentTimeMillis() - start) / 1000.0)
                    .put("coverage", report.getDouble("coverage"))
                    .put("medianRelativeRadianceError", relative[relative.size / 2])
                    .put("sessionId", p.id)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation
                .executeShellCommand("mkdir -p /data/local/tmp/hdri")
                .close()
            val dst = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
            File(dst, "pipeline.json").writeText(evidence.toString(2))
            File(store.dir(p.id), "preview.jpg").copyTo(File(dst, "sample.jpg"), overwrite = true)
        } finally {
            hdr.release()
        }
        // Retain this small sample session so screenshots/relaunch tests can inspect the result.
    }

    @Test
    fun cancellationKeepsOriginalsAndNeverPublishesPartialPanorama() {
        val store = SessionStore(context)
        val initial = store.create(Quality.QUICK, true)
        val p = SampleCapture.create(store, initial, { _, _ -> }, {})
        // A genuinely clipped bracket must still report its limitation after a paused
        // process reuses that bracket's checkpoint. Other views provide calibration.
        val clipped = Mat(240, 320, CvType.CV_8UC3, Scalar.all(255.0))
        try {
            p.captures.first().exposures.forEach {
                assertTrue(Imgcodecs.imwrite(File(store.dir(p.id), it.file).path, clipped))
            }
        } finally {
            clipped.release()
        }
        var cancel = false
        try {
            HdrPipeline(
                    store,
                    p,
                    { stage, _ ->
                        if (stage.startsWith("Aligning and merging HDR · 2")) cancel = true
                    },
                    { if (cancel) throw CancellationException("test") },
                )
                .run()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(p.captures.size, store.read(p.id).captures.size)
            assertTrue(
                p.captures.all { c ->
                    c.exposures.all { File(store.dir(p.id), it.file).length() > 0 }
                }
            )
            assertFalse(File(store.dir(p.id), "environment.hdr").exists())
            assertTrue(
                File(store.dir(p.id), "processed/${p.captures.first().targetId}.f32").exists()
            )
            val checkpoint = File(store.dir(p.id), "processed/${p.captures.first().targetId}.f32")
            val savedAt = checkpoint.lastModified()
            var verifiedReuse = false
            HdrPipeline(
                    store,
                    store.read(p.id),
                    { stage, _ ->
                        if (stage.startsWith("Aligning and merging HDR · 2")) {
                            assertEquals(
                                "The first HDR checkpoint should be reused",
                                savedAt,
                                checkpoint.lastModified(),
                            )
                            verifiedReuse = true
                        }
                    },
                    {},
                )
                .run()
            assertTrue(verifiedReuse)
            assertFalse("Completed checkpoints should be cleared", checkpoint.exists())
            assertTrue(
                "Clipping findings must survive resume",
                store.read(p.id).warnings.any {
                    it.startsWith("Direction 1:") && it.contains("lower bound")
                },
            )
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun manifestReopensWithExactCaptureMetadata() {
        val store = SessionStore(context)
        val p = store.create(Quality.DETAIL)
        try {
            val exposure = Exposure("test.jpg", 1234567, 125, 9876543210123)
            val capture =
                Capture(
                    0,
                    Q.look(32.0, 20.0),
                    V3(.01, .02, .03),
                    Lens(100, 80, 60.0, 60.0, 50.0, 40.0),
                    listOf(exposure),
                )
            store.update(p.id) { it.copy(captures = listOf(capture)) }
            val restored = SessionStore(context).read(p.id)
            assertEquals(listOf(exposure), restored.captures.single().exposures)
            assertTrue(capture.rotation.angle(restored.captures.single().rotation) < 1e-5)
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun smoothRadianceFieldHasNoHorizontalBandsOrLongitudeSeam() {
        assertTrue(OpenCVLoader.initLocal())
        val directory = File(context.cacheDir, "smooth-sphere-test").apply { mkdirs() }
        fun field(ray: V3) =
            V3(
                .7 + .16 * ray.y + .06 * ray.x,
                1 + .25 * ray.y + .08 * ray.z,
                1.5 + .2 * ray.y - .1 * ray.x,
            )
        try {
            val lens =
                Lens(
                    160,
                    120,
                    160 / (2 * tan(Math.toRadians(50.0))),
                    160 / (2 * tan(Math.toRadians(50.0))),
                    80.0,
                    60.0,
                )
            val frames =
                CoveragePlanner.targets(lens).map { target ->
                    val q = Q.look(target.yaw, target.pitch)
                    val pixels = FloatArray(lens.width * lens.height * 3)
                    for (y in 0 until lens.height) for (x in 0 until lens.width) {
                        val rgb = field(q.rotate(lens.ray(x + .5, y + .5)))
                        val i = (y * lens.width + x) * 3
                        pixels[i] = rgb.z.toFloat()
                        pixels[i + 1] = rgb.y.toFloat()
                        pixels[i + 2] = rgb.x.toFloat()
                    }
                    val mat = Mat(lens.height, lens.width, CvType.CV_32FC3)
                    val file = File(directory, "${target.id}.hdr")
                    try {
                        mat.put(0, 0, pixels)
                        assertTrue(Imgcodecs.imwrite(file.path, mat))
                    } finally {
                        mat.release()
                    }
                    Prepared(Capture(target.id, q, V3.ZERO, lens, emptyList()), lens, file, file)
                }
            val seams = SphericalBlend.seams(frames, { _, _ -> }, {})
            assertTrue(seams.labels.all { it >= 0 })
            val result = File(directory, "smooth.hdr")
            val jpeg = File(directory, "smooth.jpg")
            SphericalBlend.render(frames, seams, 2048, result, jpeg, { _, _ -> }, {})
            val hdr = Imgcodecs.imread(result.path, Imgcodecs.IMREAD_UNCHANGED)
            try {
                val pixels = FloatArray((hdr.total() * 3).toInt())
                hdr.get(0, 0, pixels)
                var previous = 0.0
                var horizontalJump = 0.0
                var longitudeJump = 0.0
                var rowBias = 0.0
                for (y in 2 until hdr.rows() - 2) {
                    var residual = 0.0
                    var count = 0
                    for (x in 0 until hdr.cols() step 8) {
                        val expected = field(Sphere.ray(x + .5, y + .5, hdr.cols(), hdr.rows())).y
                        residual += pixels[(y * hdr.cols() + x) * 3 + 1] / expected - 1
                        count++
                    }
                    residual /= count
                    rowBias = max(rowBias, abs(residual))
                    if (y > 2) horizontalJump = max(horizontalJump, abs(residual - previous))
                    previous = residual
                    val left = pixels[(y * hdr.cols()) * 3 + 1]
                    val right = pixels[(y * hdr.cols() + hdr.cols() - 1) * 3 + 1]
                    longitudeJump =
                        max(longitudeJump, abs(left - right).toDouble() / max(left, right))
                }
                assertTrue("Artificial horizontal band: $horizontalJump", horizontalJump < .008)
                assertTrue("Longitude seam: $longitudeJump", longitudeJump < .012)
                assertTrue("A dark or bright zone was introduced: $rowBias", rowBias < .035)
                val evidence =
                    File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
                File(evidence, "smooth-field.json")
                    .writeText(
                        JSONObject()
                            .put("maxAdjacentRowRelativeErrorChange", horizontalJump)
                            .put("maxLongitudeRelativeDifference", longitudeJump)
                            .put("maxRowRelativeBias", rowBias)
                            .toString(2)
                    )
                jpeg.copyTo(File(evidence, "smooth-field.jpg"), overwrite = true)
            } finally {
                hdr.release()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
