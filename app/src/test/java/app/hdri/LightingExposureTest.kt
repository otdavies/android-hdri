package app.hdri

import app.hdri.core.*
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class LightingExposureTest {
    private fun uniform(value: Float) = LightingMap(16, 8, FloatArray(16 * 8 * 3) { value })

    @Test
    fun sceneReferenceIsInvariantToRadianceUnitsAndNeverTreatsLargeValuesAsDisplayWhite() {
        for (radiance in listOf(.001f, 1f, 100f, 100_000f)) {
            val map = uniform(radiance)
            assertEquals(.18, (radiance * LightingExposure.scene(map)).toDouble(), 1e-6)
            assertEquals(.18 * 2.0.pow(-.25), (.18 * radiance * LightingExposure.grey(map)), 1e-6)
        }
        assertTrue(LightingExposure.scene(uniform(0f)).isFinite())
    }

    @Test
    fun captureReferenceRejectsMissingOrInvalidExposureMetadata() {
        for (value in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) assertNull(
            LightingExposure.validCapture(value)
        )
        assertEquals(.003f, LightingExposure.validCapture(.003f)!!, 0f)
    }
}
