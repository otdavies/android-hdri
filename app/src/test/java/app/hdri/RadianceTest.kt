package app.hdri

import app.hdri.core.*
import app.hdri.data.*
import java.io.ByteArrayOutputStream
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class RadianceTest {
    @Test
    fun exposureMergeRecoversLargeRadianceRange() {
        val values = doubleArrayOf(.02, .2, 2.0, 20.0, 1000.0)
        val times = doubleArrayOf(.0001, .004, .04, .25)
        val photos =
            times.map { t ->
                ByteArray(values.size * 3) { i ->
                    (Radiance.srgb((values[i / 3] * t).coerceIn(0.0, 1.0)) * 255)
                        .roundToInt()
                        .toByte()
                }
            }
        val merged = Radiance.merge(photos, times, Radiance.response())
        values.forEachIndexed { i, expected ->
            assertEquals(expected, merged.rgb[i * 3].toDouble(), expected * .09)
        }
        assertTrue(merged.rgb.last() / merged.rgb.first() > 40000)
    }

    @Test
    fun warmHdrGradientHasNoHardExposureSelectionContours() {
        val times = doubleArrayOf(.004, .016, .064, .256, 1.0)
        val images =
            times.mapIndexed { exposure, t ->
                ByteArray(768 * 3) { i ->
                    val x = (i / 3) / 767.0
                    val light =
                        when (i % 3) {
                            0 -> .03 + x * .5
                            1 -> .8 + x
                            else -> 2.0 + x * 3
                        }
                    // Model exposure-dependent ISP disagreement in an underexposed channel.
                    val bias = if (i % 3 == 0 && exposure < 2) 2.0 else 1.0
                    (Radiance.srgb((light * t * bias).coerceIn(0.0, 1.0)) * 255)
                        .roundToInt()
                        .toByte()
                }
            }
        val result = Radiance.merge(images, times, Radiance.response()).rgb
        for (c in 0..2) {
            val peak = (0 until 768).maxOf { result[it * 3 + c] }
            val jump = (1 until 768).maxOf { abs(result[it * 3 + c] - result[(it - 1) * 3 + c]) }
            assertTrue("Channel $c contour: $jump / $peak", jump < peak * .025)
        }
    }

    @Test
    fun rgbePreservesHdrAndChannelOrder() {
        val values =
            listOf(
                floatArrayOf(.001f, .002f, .003f),
                floatArrayOf(500f, 1000f, 2000f),
                floatArrayOf(0f, 0f, 0f),
            )
        for (v in values) {
            val bytes = Radiance.rgbe(v[0], v[1], v[2])
            val factor = 2.0.pow((bytes[3].toInt() and 255) - 128) / 256
            for (c in 0..2) assertEquals(
                v[c].toDouble(),
                (bytes[c].toInt() and 255) * factor,
                max(.0001, v.max() * 0.008),
            )
        }
        val out = ByteArrayOutputStream()
        HdrWriter(out, 8, 1).use { it.row(FloatArray(24) { if (it % 3 == 2) 100f else 1f }) }
        val bytes = out.toByteArray()
        val marker = "-Y 1 +X 8\n".toByteArray()
        val start =
            bytes.indices.first { i ->
                i + marker.size <= bytes.size && marker.indices.all { bytes[i + it] == marker[it] }
            } + marker.size
        assertEquals(2, bytes[start].toInt())
        assertEquals(8, bytes[start + 3].toInt())
        assertEquals(8, bytes[start + 4].toInt())
        assertEquals(4 + 4 * (1 + 8), bytes.size - start)
    }

    @Test
    fun manifestRetainsExactExposureTimestampsAndOrientation() {
        val p =
            Project(
                "12345678-1234-1234-1234-123456789abc",
                1234,
                "Test",
                Quality.DETAIL,
                targets = listOf(Target(0, 0.0, 0.0)),
                coverageVersion = CoveragePlanner.VERSION,
                captures =
                    listOf(
                        Capture(
                            0,
                            Q.look(45.0, 20.0),
                            V3.ZERO,
                            Lens(800, 600, 500.0, 500.0, 400.0, 300.0),
                            listOf(Exposure("one.jpg", 123456789L, 125, 987654321234567L)),
                            poseSource = "game_rotation_vector_fixed_pivot",
                        )
                    ),
            )
        val restored = SessionStore.decode(SessionStore.encode(p))
        assertEquals(p.captures[0].exposures, restored.captures[0].exposures)
        assertTrue(p.captures[0].rotation.angle(restored.captures[0].rotation) < 1e-5)
        assertEquals(p.targets, restored.targets)
        assertEquals(p.coverageVersion, restored.coverageVersion)
        assertEquals(p.captures[0].poseSource, restored.captures[0].poseSource)
        val legacy = SessionStore.encode(p)
        legacy.remove("coverageVersion")
        legacy.getJSONArray("captures").getJSONObject(0).remove("poseSource")
        val old = SessionStore.decode(legacy)
        assertEquals(0, old.coverageVersion)
        assertEquals("arcore", old.captures[0].poseSource)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownManifestVersionFailsClearly() {
        SessionStore.decode(
            SessionStore.encode(
                    Project("12345678-1234-1234-1234-123456789abc", 1, "Test", Quality.QUICK)
                )
                .put("schema", 99)
        )
    }
}
