package app.hdri.core

import kotlin.math.*

/** Continuous synthetic radiance, including the sky/floor transition. */
object AnalyticLight {
    fun sample(ray: V3): V3 {
        val grid = .65 + .35 * sin(ray.x * 63 + sin(ray.y * 47)) * cos(ray.z * 57)
        val sky = (ray.y + sqrt(ray.y * ray.y + .0016)) * .5
        val base = .18 + sky * .8
        val window =
            exp(-((ray.x - .5).pow(2) + (ray.y - .25).pow(2) + (ray.z + .82).pow(2)) * 170) * 30
        val t = ((-.20 - ray.y) / .30).coerceIn(0.0, 1.0)
        val floor = t * t * (3 - 2 * t) * .08 * (1 + sin(ray.x * 80) * sin(ray.z * 70))
        return V3(
            (base * 1.15 + floor) * grid + window,
            (base + floor) * grid + window * .93,
            (base * .8 + floor) * grid + window * .75,
        )
    }
}
