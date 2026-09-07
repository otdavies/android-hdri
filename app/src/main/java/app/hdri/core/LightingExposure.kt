package app.hdri.core

import kotlin.math.pow

/** Display references only: the saved radiance and the 18% material stay unchanged. */
object LightingExposure {
    // A quarter stop below the mathematical grey meter is a restrained viewing default.
    fun grey(diffuse: LightingMap): Float =
        (2.0.pow(-.25) / diffuse.geometricMeanLuminance().coerceAtLeast(1e-12)).toFloat()

    // Merged radiance is divided by effective exposure seconds, so numeric 1 is NOT a
    // display exposure. Meter the scene to middle grey before applying the user's EV.
    fun scene(reflection: LightingMap): Float =
        (.18 / reflection.geometricMeanLuminance().coerceAtLeast(1e-12)).toFloat()

    fun validCapture(value: Float?): Float? = value?.takeIf { it.isFinite() && it > 0f }
}
