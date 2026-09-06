package app.hdri

import app.hdri.core.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class LightingMapTest {
    @Test
    fun uniformLightProducesUniformLambertianRadianceIncludingPoles() {
        val map =
            LightingMap(128, 64, FloatArray(128 * 64 * 3) { floatArrayOf(2f, 3f, 5f)[it % 3] })
        val output = map.diffuse(16, 8)
        output.rgb.forEachIndexed { i, value ->
            assertEquals(floatArrayOf(2f, 3f, 5f)[i % 3], value, .004f)
        }
        assertEquals(4 * PI, (0 until map.height).sumOf { map.solidAngle(it) * map.width }, 1e-10)
        assertEquals(2 * .2126 + 3 * .7152 + 5 * .0722, map.meanLuminance(), 1e-6)
    }

    @Test
    fun smallBrightSourceConservesEnergyAndLightsTheCorrectHemisphere() {
        val values = FloatArray(128 * 64 * 3)
        val x = 64
        val y = 15
        values[(y * 128 + x) * 3] = 100_000f
        val map = LightingMap(128, 64, values)
        val small = map.reduced(32, 16)
        assertEquals(map.meanLuminance(), small.meanLuminance(), 1e-5)
        val diffuse = map.diffuse(16, 8)
        val direction = Sphere.ray(x + .5, y + .5, 128, 64)
        for (oy in 0 until 8) for (ox in 0 until 16) {
            val expected =
                100_000 * map.solidAngle(y) / PI *
                    max(0.0, direction.dot(Sphere.ray(ox + .5, oy + .5, 16, 8)))
            assertEquals(expected, diffuse.rgb[(oy * 16 + ox) * 3].toDouble(), .0001)
            assertEquals(0f, diffuse.rgb[(oy * 16 + ox) * 3 + 1], 0f)
        }
    }
}
