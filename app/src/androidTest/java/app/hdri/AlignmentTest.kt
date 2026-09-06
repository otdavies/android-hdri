package app.hdri

import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.*
import app.hdri.data.Capture
import app.hdri.processing.*
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

class AlignmentTest {
    private fun texture(): Mat {
        val image = Mat(900, 1200, CvType.CV_8UC3, Scalar.all(95.0))
        val random = Random(812)
        repeat(1400) {
            val color = Scalar.all(random.nextDouble(15.0, 240.0))
            Imgproc.circle(
                image,
                Point(random.nextDouble(0.0, 1200.0), random.nextDouble(0.0, 900.0)),
                random.nextInt(3, 22),
                color,
                -1,
            )
        }
        return image
    }

    @Test
    fun jointRegistrationRecoversDriftAndSmallViewpointOffsets() {
        assertTrue(OpenCVLoader.initLocal())
        Core.setNumThreads(2)
        val dir =
            File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "alignment-fixture",
                )
                .apply { mkdirs() }
        val texture = texture()
        val frames = mutableListOf<Prepared>()
        val actual = Lens(600, 450, 440.0, 440.0, 300.0, 225.0)
        val recorded = actual.copy(fx = 400.0, fy = 400.0)
        try {
            for (i in 0 until 9) {
                val truth = Q.look((i % 3 - 1) * 22.0, (i / 3 - 1) * 17.0)
                val prior =
                    Q.axis(
                        V3(
                            Math.toRadians(sin(i.toDouble())),
                            Math.toRadians((i % 3 - 1) * 9.0),
                            0.0,
                        )
                    ) * truth
                val origin = V3(sin(i * 1.3) * .035, cos(i * 1.1) * .025, 0.0)
                val mx = FloatArray(actual.width * actual.height)
                val my = FloatArray(mx.size)
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    val ray = truth.rotate(actual.ray(x.toDouble(), y.toDouble()))
                    val point = origin + ray * ((-4 - origin.z) / ray.z)
                    mx[y * actual.width + x] = ((point.x + 4) / 8 * texture.cols()).toFloat()
                    my[y * actual.width + x] = ((3 - point.y) / 6 * texture.rows()).toFloat()
                }
                val mapX = Mat(actual.height, actual.width, CvType.CV_32F)
                val mapY = Mat(actual.height, actual.width, CvType.CV_32F)
                val image = Mat()
                try {
                    mapX.put(0, 0, mx)
                    mapY.put(0, 0, my)
                    Imgproc.remap(
                        texture,
                        image,
                        mapX,
                        mapY,
                        Imgproc.INTER_LINEAR,
                        Core.BORDER_REFLECT,
                    )
                    val file = File(dir, "$i.jpg")
                    assertTrue(Imgcodecs.imwrite(file.path, image))
                    val hdr = File(dir, "$i.f32")
                    val linear = Mat()
                    try {
                        image.convertTo(linear, CvType.CV_32FC3, 1.0 / 255)
                        val pixels = FloatArray(actual.width * actual.height * 3)
                        linear.get(0, 0, pixels)
                        FloatImages.write(hdr, actual.width, actual.height, pixels)
                    } finally {
                        linear.release()
                    }
                    frames +=
                        Prepared(
                            Capture(i, prior, V3.ZERO, recorded, emptyList()),
                            recorded,
                            hdr,
                            file,
                        )
                } finally {
                    mapX.release()
                    mapY.release()
                    image.release()
                }
            }
            val report = Registration.analyze(frames, { _, _ -> }, {})
            assertTrue("Insufficient real image links: $report", report.pairs >= 12)
            assertEquals(0, report.isolated)
            assertTrue("Fixture did not exercise drift: $report", report.beforeDegrees > 2)
            assertTrue(
                "Registration failed: $report",
                report.afterDegrees < .8 && report.afterDegrees < report.beforeDegrees * .25,
            )
            assertTrue("Local warp missing: $report", report.meshes >= 6)
        } finally {
            texture.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun localMatchingHandlesEmptyInputAndEarlyCancellationWithoutNativeCrash() {
        assertTrue(OpenCVLoader.initLocal())
        assertTrue(LocalAlignment.matches(emptyList(), {}, {}).isEmpty())
        val lens = Lens(600, 450, 440.0, 440.0, 300.0, 225.0)
        val frame =
            Prepared(
                Capture(0, Q.look(0.0, 0.0), V3.ZERO, lens, emptyList()),
                lens,
                File("unused.f32"),
                File("unused.jpg"),
            )
        assertThrows(CancellationException::class.java) {
            LocalAlignment.matches(listOf(frame), {}, { throw CancellationException() })
        }
    }

    @Test
    fun bracketAlignmentCorrectsSubpixelRotationAcrossExposures() {
        assertTrue(OpenCVLoader.initLocal())
        val source = texture()
        val reference = Mat()
        val shifted = Mat()
        val transform = Imgproc.getRotationMatrix2D(Point(160.0, 120.0), 1.3, 1.0)
        val raw = mutableListOf<Mat>()
        var aligned = emptyList<Mat>()
        try {
            Imgproc.resize(source, reference, Size(320.0, 240.0))
            val values = DoubleArray(6)
            transform.get(0, 0, values)
            values[2] += 3.4
            values[5] -= 2.7
            transform.put(0, 0, *values)
            Imgproc.warpAffine(
                reference,
                shifted,
                transform,
                reference.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REFLECT,
            )
            shifted.convertTo(shifted, -1, .65)
            raw += shifted.clone()
            raw += reference.clone()
            raw += shifted.clone()
            aligned = BracketAlignment.align(raw, {}, {})
            val before = Mat()
            val after = Mat()
            val ref = reference.submat(Rect(25, 25, 270, 190))
            val a = aligned[0].submat(Rect(25, 25, 270, 190))
            val b = shifted.submat(Rect(25, 25, 270, 190))
            try {
                a.convertTo(after, CvType.CV_32F, 1 / .65)
                b.convertTo(before, CvType.CV_32F, 1 / .65)
                ref.convertTo(ref, CvType.CV_32F)
                val e0 = Core.norm(before, ref, Core.NORM_L1)
                val e1 = Core.norm(after, ref, Core.NORM_L1)
                assertTrue("Alignment error $e1 versus $e0", e1 < e0 * .35)
            } finally {
                before.release()
                after.release()
                ref.release()
                a.release()
                b.release()
            }
        } finally {
            source.release()
            reference.release()
            shifted.release()
            transform.release()
            raw.forEach { it.release() }
            aligned.forEach { it.release() }
        }
    }

    @Test
    fun nativeMergeMatchesScalarReferenceAndLosslessCheckpoints() {
        assertTrue(OpenCVLoader.initLocal())
        val random = Random(81)
        val bytes =
            List(5) { exposure ->
                ByteArray(32 * 24 * 3) { i ->
                    when (i / 3) {
                        0 -> 0
                        1 -> 255.toByte()
                        2 -> if (exposure < 2) 0 else 255.toByte()
                        else -> random.nextInt(256).toByte()
                    }
                }
            }
        val times = doubleArrayOf(.000001, .0001, .001, .01, .1)
        val response =
            Radiance.response().also { values ->
                for (i in values.indices) values[i] *= if (i % 3 == 0) .8f else 1.2f
            }
        val images = bytes.map { b -> Mat(24, 32, CvType.CV_8UC3).also { it.put(0, 0, b) } }
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "float-roundtrip.f32",
            )
        try {
            val expected = Radiance.merge(bytes, times, response)
            val result = NativeRadiance.merge(images, times, response)
            assertEquals(expected.clipped, result.clipped)
            for (i in expected.rgb.indices) assertEquals(
                expected.rgb[i].toDouble(),
                result.rgb[i].toDouble(),
                max(1e-6, abs(expected.rgb[i]) * 2e-6),
            )
            FloatImages.write(file, 32, 24, result.rgb)
            val decoded = FloatImages.read(file)
            try {
                val roundtrip = FloatArray(result.rgb.size)
                decoded.get(0, 0, roundtrip)
                assertArrayEquals(result.rgb, roundtrip, 0f)
                assertTrue(roundtrip.max() > 65504f)
            } finally {
                decoded.release()
            }
        } finally {
            images.forEach { it.release() }
            file.delete()
        }
    }

    @Test
    fun graphCutRoutesAroundDisagreeingObjectsAndKeepsValidSources() {
        val w = 128
        val h = 64
        val n = w * h
        val a = SeamLayer(FloatArray(n * 3), FloatArray(n) { if (it % w < 108) 1f else 0f })
        val b = SeamLayer(FloatArray(n * 3), FloatArray(n) { if (it % w > 20) 1f else 0f })
        for (y in 18..46) for (x in 58..78) for (c in 0..2) b.rgb[(y * w + x) * 3 + c] = 2f
        val labels = IntArray(n) { if (it % w < 64) 0 else 1 }
        fun disagreement(): Int {
            var count = 0
            for (y in 0 until h) for (x in 1 until w) {
                val p = y * w + x
                if (labels[p] != labels[p - 1] && (b.rgb[p * 3] > 0 || b.rgb[(p - 1) * 3] > 0))
                    count++
            }
            return count
        }
        val before = disagreement()
        assertTrue(before > 20)
        SeamOptimizer.optimize(listOf(a, b), labels, w, h, {}, {})
        assertEquals(0, disagreement())
        for (i in 0 until n) assertTrue((if (labels[i] == 0) a else b).weights[i] > 0)
    }
}
