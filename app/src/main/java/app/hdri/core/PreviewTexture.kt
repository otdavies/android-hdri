package app.hdri.core

import kotlin.math.hypot

/** Maps unrotated, top-left-origin camera image coordinates into an external texture. */
class PreviewTexture(transform: FloatArray) {
    init {
        require(transform.size == 16 && transform.all { it.isFinite() })
    }

    // Camera2's SurfaceTexture matrix includes the sensor-to-natural-display rotation,
    // in addition to the GL Y flip and buffer crop. Guidance already rotates sensor
    // coordinates. Remove the matrix's orthogonal orientation about its centre, retaining
    // the actual sampling rectangle (including padding and the filtering texel inset).
    private val centreU = transform[12] + .5f * (transform[0] + transform[4])
    private val centreV = transform[13] + .5f * (transform[1] + transform[5])
    private val spanU = hypot(transform[0], transform[4])
    private val spanV = hypot(transform[1], transform[5])

    init {
        require(spanU > 0f && spanV > 0f) { "Invalid camera texture transform." }
    }

    fun sample(u: Float, v: Float): Pair<Float, Float> =
        (centreU + (u - .5f) * spanU) to (centreV + (v - .5f) * spanV)
}
