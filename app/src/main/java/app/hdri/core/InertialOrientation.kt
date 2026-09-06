package app.hdri.core

import kotlin.math.*

/**
 * One continuous, gravity-level orientation reference. There is deliberately no position or
 * visual-tracking input: sky, texture loss and AR relocalization cannot move this reference.
 * Android's game rotation vector fuses gyro + accelerometer without magnetic-north corrections.
 */
class InertialOrientation {
    data class Sample(val time: Long, val device: Q)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun add(time: Long, gameRotation: Q) {
        val q = gameRotation
        val norm = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w
        if (!norm.isFinite() || norm < .5 || time <= (samples.lastOrNull()?.time ?: 0L)) return
        // Android world has Z up. Our spherical world has Y up and -Z forward.
        samples.addLast(Sample(time, (Q.axis(V3(-PI / 2, 0.0, 0.0)) * q).normalized()))
        while (samples.size > 2 && time - samples.first().time > 30_000_000_000L) samples
            .removeFirst()
    }

    @Synchronized
    fun latest(now: Long): Sample? =
        samples.lastOrNull()?.takeIf { now - it.time in 0..250_000_000L }

    /** Interpolate on the sensor clock, never at delayed JPEG callback delivery time. */
    @Synchronized
    fun at(time: Long): Q? {
        if (samples.isEmpty() || time < samples.first().time || time > samples.last().time)
            return null
        var before = samples.first()
        for (after in samples) {
            if (after.time >= time) {
                if (after.time == before.time) return before.device
                if (after.time - before.time > 250_000_000L) return null
                val t = (time - before.time).toDouble() / (after.time - before.time)
                val a = before.device
                val b = after.device
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
        return null
    }

    companion object {
        /** Unrotated rear-camera JPEG axes -> Android's natural device axes. */
        fun cameraInDevice(sensorOrientation: Int): Q =
            Q.axis(V3(0.0, 0.0, -Math.toRadians(sensorOrientation.toDouble())))

        fun displayInDevice(displayDegrees: Int): Q =
            Q.axis(V3(0.0, 0.0, Math.toRadians(displayDegrees.toDouble())))

        /** Set an arbitrary zero heading once; keep gravity vertical, even if started at a pole. */
        fun zeroHeading(device: Q): Q {
            val forward = device.rotate(V3.FORWARD)
            val right = device.rotate(V3(1.0, 0.0, 0.0))
            val yaw =
                if (hypot(forward.x, forward.z) > .1) atan2(forward.x, -forward.z)
                else atan2(right.z, right.x)
            return Q.axis(V3(0.0, yaw, 0.0))
        }
    }
}
