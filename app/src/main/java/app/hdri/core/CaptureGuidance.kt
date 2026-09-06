package app.hdri.core

import kotlin.math.*

/** Screen-relative corrections. Positive yaw = right, positive pitch = up. */
data class AimGuide(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val canLevel: Boolean = true,
) {
    val instruction: String
        get() {
            val horizontal = if (abs(yaw) < 3) null else if (yaw > 0) "Turn right" else "Turn left"
            val vertical = if (abs(pitch) < 3) null else if (pitch > 0) "tilt up" else "tilt down"
            return listOfNotNull(horizontal, vertical)
                .joinToString(" · ")
                .replaceFirstChar { it.uppercase() }
                .ifEmpty { "You're aligned" }
        }

    companion object {
        fun from(displayRotation: Q, target: V3): AimGuide {
            val inverse = displayRotation.inverse()
            val direction = inverse.rotate(target)
            val up = inverse.rotate(V3(0.0, 1.0, 0.0))
            return AimGuide(
                Math.toDegrees(atan2(direction.x, -direction.z)).toFloat(),
                Math.toDegrees(atan2(direction.y, hypot(direction.x, direction.z))).toFloat(),
                Math.toDegrees(atan2(up.x, up.y)).toFloat(),
                hypot(up.x, up.y) > .25,
            )
        }
    }
}

/** Bounded gyro history. Integrate signed 3D rotations so tremor does not accumulate as travel. */
class GyroHistory {
    private data class Sample(val time: Long, val q: Q)

    private val samples = ArrayDeque<Sample>()
    private var rotation = Q()
    private var previousTime = 0L

    @Synchronized
    fun add(time: Long, radiansPerSecond: V3) {
        if (time <= previousTime || !radiansPerSecond.length().isFinite()) return
        val dt = if (previousTime == 0L) 0.0 else (time - previousTime) / 1e9
        if (dt > .25) {
            samples.clear()
            rotation = Q()
        } else {
            rotation = (rotation * Q.axis(radiansPerSecond * dt)).normalized()
        }
        previousTime = time
        samples.addLast(Sample(time, rotation))
        while (samples.size > 2 && time - samples.first().time > 30_000_000_000L) samples
            .removeFirst()
    }

    /** Largest excursion during the given sensor-clock interval, including a return to start. */
    @Synchronized
    fun excursion(start: Long, end: Long): Double? {
        if (
            samples.size < 2 ||
                start > end ||
                start < samples.first().time ||
                end > samples.last().time
        )
            return null
        fun at(time: Long): Q {
            var before = samples.first()
            for (after in samples) {
                if (after.time >= time) {
                    val t =
                        if (after.time == before.time) 0.0
                        else (time - before.time).toDouble() / (after.time - before.time)
                    val a = before.q
                    val b = after.q
                    val sign = if (a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w < 0) -1.0 else 1.0
                    return Q(
                            a.x * (1 - t) + b.x * t * sign,
                            a.y * (1 - t) + b.y * t * sign,
                            a.z * (1 - t) + b.z * t * sign,
                            a.w * (1 - t) + b.w * t * sign,
                        )
                        .normalized()
                }
                before = after
            }
            return samples.last().q
        }
        val anchor = at(start)
        var maximum = anchor.angle(at(end))
        samples.forEach { if (it.time in start..end) maximum = max(maximum, anchor.angle(it.q)) }
        return maximum
    }
}
