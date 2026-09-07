package app.hdri

import app.hdri.core.PreviewTexture
import org.junit.Assert.*
import org.junit.Test

class PreviewTextureTest {
    @Test
    fun producerQuarterTurnsDoNotRotateSensorCoordinatesTwiceAndKeepOffCentreCrop() {
        // GLConsumer-style vertical flip with each buffer orientation. These map a unit
        // square into the same off-centre sampling rectangle, with different corner order.
        val transforms =
            listOf(
                floatArrayOf(1f, 0f, 0f, -1f, 0f, 1f),
                floatArrayOf(0f, -1f, -1f, 0f, 1f, 1f),
                floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f),
                floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f),
            )
        for (t in transforms) {
            val m = FloatArray(16)
            m[0] = t[0] * .81f
            m[4] = t[2] * .81f
            m[12] = .08f + t[4] * .81f
            m[1] = t[1] * .67f
            m[5] = t[3] * .67f
            m[13] = .17f + t[5] * .67f
            m[10] = 1f
            m[15] = 1f
            val texture = PreviewTexture(m)
            for (u in listOf(0f, .2f, .7f, 1f)) for (v in listOf(0f, .3f, .8f, 1f)) {
                val actual = texture.sample(u, v)
                assertEquals(.08f + u * .81f, actual.first, 1e-6f)
                assertEquals(.17f + v * .67f, actual.second, 1e-6f)
            }
        }
    }

    @Test
    fun invalidOrMissingTextureMatricesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PreviewTexture(FloatArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { PreviewTexture(FloatArray(16)) }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewTexture(FloatArray(16) { Float.NaN })
        }
    }
}
