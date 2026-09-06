package app.hdri.core

import kotlin.math.*

/** Linear RGB equirectangular light. All integration happens before display mapping. */
data class LightingMap(val width: Int, val height: Int, val rgb: FloatArray) {
    init {
        require(width > 0 && height > 0 && rgb.size == width * height * 3)
        require(rgb.all { it.isFinite() && it >= 0f }) { "HDR contains invalid radiance." }
    }

    fun solidAngle(y: Int): Double =
        2 * PI / width * (cos(PI * y / height) - cos(PI * (y + 1) / height))

    fun meanLuminance(): Double {
        var sum = 0.0
        for (y in 0 until height) {
            val weight = solidAngle(y)
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                sum += (.2126 * rgb[i] + .7152 * rgb[i + 1] + .0722 * rgb[i + 2]) * weight
            }
        }
        return sum / (4 * PI)
    }

    /** Exact solid-angle weighted binning keeps small bright sources and polar energy. */
    fun reduced(w: Int, h: Int): LightingMap {
        require(width % w == 0 && height % h == 0)
        val values = DoubleArray(w * h * 3)
        val weights = DoubleArray(h)
        for (y in 0 until height) {
            val row = y * h / height
            val weight = solidAngle(y)
            weights[row] += weight * (width / w)
            for (x in 0 until width) {
                val dst = (row * w + x * w / width) * 3
                val src = (y * width + x) * 3
                for (c in 0..2) values[dst + c] += rgb[src + c] * weight
            }
        }
        return LightingMap(
            w,
            h,
            FloatArray(values.size) { (values[it] / weights[it / 3 / w]).toFloat() },
        )
    }

    /** Lambertian outgoing radiance for unit reflectance: integral L(w) max(n.w,0) dw / pi. */
    fun diffuse(w: Int = 64, h: Int = 32, progress: (Float) -> Unit = {}): LightingMap {
        val count = width * height
        val directions =
            Array(count) { Sphere.ray(it % width + .5, it / width + .5, width, height) }
        val weights = DoubleArray(count) { solidAngle(it / width) / PI }
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            progress(y.toFloat() / h)
            for (x in 0 until w) {
                val n = Sphere.ray(x + .5, y + .5, w, h)
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (i in 0 until count) {
                    val weight = max(0.0, n.dot(directions[i])) * weights[i]
                    r += rgb[i * 3] * weight
                    g += rgb[i * 3 + 1] * weight
                    b += rgb[i * 3 + 2] * weight
                }
                val index = (y * w + x) * 3
                out[index] = r.toFloat()
                out[index + 1] = g.toFloat()
                out[index + 2] = b.toFloat()
            }
        }
        progress(1f)
        return LightingMap(w, h, out)
    }
}
