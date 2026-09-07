package app.hdri.core

import kotlin.math.abs
import kotlin.math.roundToInt

data class RouteProgress(
    val title: String,
    val ring: Int,
    val rings: Int,
    val saved: Int,
    val total: Int,
)

/** A reproducible route, independent of where the user happens to point between shutters. */
class CaptureRoute(targets: List<Target>) {
    private val rings: List<List<Target>>
    val ordered: List<Target>

    init {
        // Horizon closes first. Then climb through upper rings, cap the sky, return to
        // the lower rings and finish at the nadir (if included by the coverage plan).
        val rows =
            targets
                .groupBy { (it.pitch * 1000).roundToInt() }
                .toSortedMap(
                    compareBy<Int> { if (it == 0) 0 else if (it > 0) 1 else 2 }.thenBy { abs(it) }
                )
        var heading = 0.0
        rings =
            rows.values.map { row ->
                val clockwise = row.sortedWith(compareBy<Target> { wrap(it.yaw) }.thenBy { it.id })
                val first =
                    clockwise.indices.minByOrNull {
                        val d = abs(wrap(clockwise[it].yaw - heading))
                        minOf(d, 360 - d)
                    } ?: 0
                val ordered = clockwise.drop(first) + clockwise.take(first)
                // A pole has no meaningful heading; keep the last ring's exit heading.
                if (abs(ordered.first().pitch) < 89) heading = ordered.last().yaw
                ordered
            }
        ordered = rings.flatten()
    }

    fun next(completed: Set<Int>): Target? = ordered.firstOrNull { it.id !in completed }

    fun progress(completed: Set<Int>): RouteProgress? {
        val next = next(completed) ?: return null
        val index = rings.indexOfFirst { row -> row.any { it.id == next.id } }
        val row = rings[index]
        val pitch = row.first().pitch
        val title =
            when {
                abs(pitch) < .01 -> "Horizon"
                pitch >= 89 -> "Top"
                pitch <= -89 -> "Ground"
                pitch > 0 -> "Upper ring · ${pitch.roundToInt()}° up"
                else -> "Lower ring · ${abs(pitch).roundToInt()}° down"
            }
        return RouteProgress(
            title,
            index + 1,
            rings.size,
            row.count { it.id in completed },
            row.size,
        )
    }

    private fun wrap(degrees: Double) = (degrees % 360 + 360) % 360
}
