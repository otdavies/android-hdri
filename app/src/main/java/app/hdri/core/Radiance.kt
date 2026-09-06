package app.hdri.core

import java.io.OutputStream
import kotlin.math.*

object Radiance {
    fun srgb(x: Double) = if (x <= .0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - .055

    fun linear(x: Double) = if (x <= .04045) x / 12.92 else ((x + .055) / 1.055).pow(2.4)

    fun response() = FloatArray(256 * 3) { linear((it / 3) / 255.0).toFloat() }

    data class Merge(val rgb: FloatArray, val clipped: Int, val moving: Int)

    /** Images and response are BGR; output is linear BGR. Normalize with measured shutter * ISO. */
    fun merge(images: List<ByteArray>, times: DoubleArray, response: FloatArray): Merge {
        require(
            images.size == times.size &&
                images.isNotEmpty() &&
                times.all { it > 0 && it.isFinite() }
        )
        require(images.all { it.size == images[0].size })
        require(response.size == 768)
        val out = FloatArray(images[0].size)
        var clipped = 0
        var moving = 0
        val shortest = times.indices.minBy { times[it] }
        val longest = times.indices.maxBy { times[it] }
        val radiance = times.map { time -> DoubleArray(768) { response[it] / time } }
        val threshold = exp(.55)
        val weights = DoubleArray(images.size)
        val luminance = DoubleArray(images.size)
        var p = 0
        while (p < out.size) {
            var total = 0.0
            var reference = 0
            var best = 0.0
            for (i in images.indices) {
                var peak = 0
                var light = 0.0
                for (c in 0..2) {
                    val z = images[i][p + c].toInt() and 255
                    peak = max(peak, z)
                    light += radiance[i][z * 3 + c]
                }
                // JPEG color becomes unreliable when ANY channel clips (often before
                // white balance in the ISP). Use one continuous RGB weight, based on the
                // brightest channel. A naturally dark blue channel must not block HDR.
                val w = min(peak, 255 - peak).toDouble()
                weights[i] = w * w
                total += weights[i]
                luminance[i] = light + 1e-8
                if (weights[i] > best) {
                    best = weights[i]
                    reference = i
                }
            }
            var moved = false
            for (i in images.indices) if (weights[i] > best * .1 && best > 324 && i != reference) {
                val light = luminance[i]
                val ref = luminance[reference]
                if (light > ref * threshold || light * threshold < ref) moved = true
            }
            for (c in 0..2) {
                var sum = 0.0
                for (i in images.indices) sum +=
                    radiance[i][(images[i][p + c].toInt() and 255) * 3 + c] * weights[i]
                if (total > 0) out[p + c] = (sum / total).toFloat()
                else {
                    val i =
                        if ((0..2).any { (images[shortest][p + it].toInt() and 255) > 251 })
                            shortest
                        else longest
                    out[p + c] = radiance[i][(images[i][p + c].toInt() and 255) * 3 + c].toFloat()
                }
                if (!out[p + c].isFinite() || out[p + c] < 0) out[p + c] = 0f
            }
            if ((0..2).any { (images[shortest][p + it].toInt() and 255) > 251 }) clipped++
            if (moved) moving++
            p += 3
        }
        return Merge(out, clipped, moving)
    }

    fun rgbe(r: Float, g: Float, b: Float): ByteArray {
        require(r.isFinite() && g.isFinite() && b.isFinite())
        val maximum = max(r, max(g, b)).toDouble()
        if (maximum < 1e-32) return ByteArray(4)
        val e = floor(log2(maximum)).toInt() + 1
        val scale = 256.0 / 2.0.pow(e)
        return byteArrayOf(
            (r * scale).toInt().coerceIn(0, 255).toByte(),
            (g * scale).toInt().coerceIn(0, 255).toByte(),
            (b * scale).toInt().coerceIn(0, 255).toByte(),
            (e + 128).coerceIn(0, 255).toByte(),
        )
    }
}

/** Standard Radiance RGBE, row-wise RLE; only a single scanline is retained. */
class HdrWriter(private val stream: OutputStream, private val width: Int, height: Int) :
    AutoCloseable {
    init {
        require(width in 8..32767 && height > 0)
        stream.write(
            "#?RADIANCE\n# sphere - relative scene radiance, linear RGB / D65\nFORMAT=32-bit_rle_rgbe\n\n-Y $height +X $width\n"
                .toByteArray(Charsets.US_ASCII)
        )
    }

    private val channels = Array(4) { ByteArray(width) }
    private val marker = byteArrayOf(2, 2, (width shr 8).toByte(), width.toByte())

    fun row(bgr: FloatArray, offset: Int = 0) {
        require(bgr.size - offset >= width * 3)
        stream.write(marker)
        for (x in 0 until width) {
            val p = offset + x * 3
            val r = bgr[p + 2]
            val g = bgr[p + 1]
            val b = bgr[p]
            require(r.isFinite() && g.isFinite() && b.isFinite())
            val maximum = max(r, max(g, b))
            if (maximum < 1e-32f) {
                for (c in 0..3) channels[c][x] = 0
                continue
            }
            val exponent = Math.getExponent(maximum) + 1
            val scale = Math.scalb(256.0, -exponent)
            channels[0][x] = (r * scale).toInt().coerceIn(0, 255).toByte()
            channels[1][x] = (g * scale).toInt().coerceIn(0, 255).toByte()
            channels[2][x] = (b * scale).toInt().coerceIn(0, 255).toByte()
            channels[3][x] = (exponent + 128).coerceIn(0, 255).toByte()
        }
        channels.forEach { channel ->
            var x = 0
            while (x < width) {
                val n = min(128, width - x)
                stream.write(n)
                stream.write(channel, x, n)
                x += n
            }
        }
    }

    override fun close() {
        stream.close()
    }
}
