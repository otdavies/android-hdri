package app.hdri.core

import kotlin.math.*

data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: V3) = V3(x + b.x, y + b.y, z + b.z)

    operator fun minus(b: V3) = V3(x - b.x, y - b.y, z - b.z)

    operator fun times(s: Double) = V3(x * s, y * s, z * s)

    fun dot(b: V3) = x * b.x + y * b.y + z * b.z

    fun cross(b: V3) = V3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)

    fun length() = sqrt(dot(this))

    fun unit() = this * (1.0 / length().coerceAtLeast(1e-12))

    fun angle(b: V3) = Math.toDegrees(acos(unit().dot(b.unit()).coerceIn(-1.0, 1.0)))

    companion object {
        val ZERO = V3(0.0, 0.0, 0.0)
        val FORWARD = V3(0.0, 0.0, -1.0)
    }
}

/** Camera-to-world quaternion. Camera axes are +X right, +Y up, -Z forward. */
data class Q(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0, val w: Double = 1.0) {
    operator fun times(b: Q) =
        Q(
            w * b.x + x * b.w + y * b.z - z * b.y,
            w * b.y - x * b.z + y * b.w + z * b.x,
            w * b.z + x * b.y - y * b.x + z * b.w,
            w * b.w - x * b.x - y * b.y - z * b.z,
        )

    fun inverse() = Q(-x, -y, -z, w)

    fun normalized(): Q {
        val s = 1.0 / sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-12)
        return Q(x * s, y * s, z * s, w * s)
    }

    fun rotate(v: V3): V3 {
        val t = V3(x, y, z).cross(v) * 2.0
        return v + t * w + V3(x, y, z).cross(t)
    }

    fun angle(b: Q) =
        Math.toDegrees(2 * acos(abs(x * b.x + y * b.y + z * b.z + w * b.w).coerceIn(0.0, 1.0)))

    companion object {
        fun axis(v: V3): Q {
            val a = v.length()
            if (a < 1e-10) return Q()
            val s = sin(a / 2) / a
            return Q(v.x * s, v.y * s, v.z * s, cos(a / 2))
        }

        fun look(yaw: Double, pitch: Double) =
            axis(V3(0.0, -Math.toRadians(yaw), 0.0)) * axis(V3(Math.toRadians(pitch), 0.0, 0.0))
    }
}

data class Lens(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
) {
    val fovX
        get() = Math.toDegrees(2 * atan(width / (2 * fx)))

    val fovY
        get() = Math.toDegrees(2 * atan(height / (2 * fy)))

    fun scaled(w: Int, h: Int) =
        Lens(w, h, fx * w / width, fy * h / height, cx * w / width, cy * h / height)

    fun ray(x: Double, y: Double) = V3((x - cx) / fx, -(y - cy) / fy, -1.0).unit()

    fun project(v: V3): Pair<Double, Double>? {
        if (v.z >= -1e-6) return null
        val x = cx - fx * v.x / v.z
        val y = cy + fy * v.y / v.z
        return if (x >= 0 && x < width - 1 && y >= 0 && y < height - 1) x to y else null
    }
}

data class Target(val id: Int, val yaw: Double, val pitch: Double) {
    val ray
        get() = Q.look(yaw, pitch).rotate(V3.FORWARD)
}

object Sphere {
    fun ray(x: Double, y: Double, width: Int, height: Int): V3 {
        val yaw = (x / width - .5) * 2 * PI
        val pitch = (.5 - y / height) * PI
        return V3(sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))
    }

    fun uv(v: V3): Pair<Double, Double> =
        (atan2(v.x, -v.z) / (2 * PI) + .5) to (.5 - asin(v.unit().y.coerceIn(-1.0, 1.0)) / PI)
}

enum class HoldReason {
    AIM,
    TRACKING,
    SETTLING,
    READY,
}

object CaptureTolerance {
    const val AIM_ENTER = 4.5
    const val AIM_EXIT = 6.0
}

/** Evaluate a short pose envelope, never the noisy derivative of individual sensor readings. */
class SteadyGate(private val dwellNanos: Long = 450_000_000) {
    private data class Sample(val time: Long, val rotation: Q)

    private val samples = ArrayDeque<Sample>()
    private var last = 0L
    private var credit = 0.0
    private var locked = false
    var reason = HoldReason.AIM
        private set

    var movementDegrees = 0.0
        private set

    @Synchronized
    fun reset() {
        last = 0L
        credit = 0.0
        locked = false
        samples.clear()
        movementDegrees = 0.0
        reason = HoldReason.AIM
    }

    @Synchronized
    fun update(now: Long, q: Q, aimDegrees: Double, tracking: Boolean): Double {
        if (last != 0L && now <= last) return credit
        if (last != 0L && now - last > 250_000_000) reset()
        val dt = if (last == 0L) 0.0 else (now - last).toDouble() / dwellNanos
        last = now
        locked =
            tracking &&
                aimDegrees <= if (locked) CaptureTolerance.AIM_EXIT else CaptureTolerance.AIM_ENTER
        val blocked =
            when {
                !tracking -> HoldReason.TRACKING
                !locked -> HoldReason.AIM
                else -> null
            }
        if (blocked != null) {
            credit = 0.0
            samples.clear()
            reason = blocked
            return 0.0
        }
        samples.addLast(Sample(now, q.normalized()))
        while (samples.size > 1 && now - samples.first().time > 450_000_000) samples.removeFirst()
        movementDegrees = samples.maxOf { q.angle(it.rotation) }
        // A broad pose envelope accepts slow pans. Fit signed rotation over the whole
        // window instead: oscillating hand tremor cancels, deliberate travel does not.
        val anchor = samples.first()
        val meanTime = samples.map { (it.time - anchor.time) / 1e9 }.average()
        var variance = 0.0
        var slope = V3.ZERO
        samples.forEach {
            val t = (it.time - anchor.time) / 1e9 - meanTime
            val relative = anchor.rotation.inverse() * it.rotation
            val sign = if (relative.w < 0) -1.0 else 1.0
            slope += V3(relative.x, relative.y, relative.z) * (2 * 180 / PI * sign * t)
            variance += t * t
        }
        val drift = slope.length() / variance.coerceAtLeast(1e-12)
        val settled = movementDegrees <= 1.1 && drift <= .85 && now - anchor.time >= 300_000_000
        // A moving phone cannot carry almost-complete shutter credit into a fresh stop.
        credit = if (settled) (credit + dt).coerceAtMost(1.0) else 0.0
        reason = if (credit >= 1.0) HoldReason.READY else HoldReason.SETTLING
        return credit
    }
}

object Brackets {
    fun times(base: Long, count: Int, min: Long, max: Long): List<Long> {
        require(count == 3 || count == 5)
        require(min > 0 && max > min)
        val ev = if (count == 5) listOf(-4, -2, 0, 2, 4) else listOf(-3, 0, 3)
        return ev.map { (base * 2.0.pow(it)).toLong().coerceIn(min, max) }.distinct()
    }
}
