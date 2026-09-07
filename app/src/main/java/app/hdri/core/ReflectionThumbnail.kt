package app.hdri.core

import java.io.File
import kotlin.math.*

/** Small, transparent mirror-sphere preview. The HDR master is never tone-mapped or modified. */
object ReflectionThumbnail {
    const val SIZE = 192
    const val VERSION = 1

    fun load(file: File, progress: (Float) -> Unit = {}, check: () -> Unit = {}): LightingMap {
        return if (file.extension == "exr")
            ExrReader(file).use { source ->
                reduce(source.width, source.height, source::row, progress, check)
            }
        else
            HdrReader(file).use { source ->
                reduce(source.width, source.height, { _, row -> source.row(row) }, progress, check)
            }
    }

    /** Read one scanline at a time; retain at most a 512x256 linear reflection map. */
    private fun reduce(
        width: Int,
        height: Int,
        row: (Int, FloatArray) -> Unit,
        progress: (Float) -> Unit,
        check: () -> Unit,
    ): LightingMap {
        require(width == height * 2) { "Expected a full equirectangular HDR environment." }
        val w = min(512, width)
        val h = w / 2
        val sum = DoubleArray(w * h * 3)
        val weights = DoubleArray(w * h)
        val pixels = FloatArray(width * 3)
        for (y in 0 until height) {
            check()
            row(y, pixels)
            val weight = cos(PI * y / height) - cos(PI * (y + 1) / height)
            for (x in 0 until width) {
                val dst = y * h / height * w + x * w / width
                weights[dst] += weight
                for (c in 0..2) sum[dst * 3 + c] += pixels[x * 3 + 2 - c] * weight
            }
            if (y % 16 == 0) progress((y + 1f) / height)
        }
        progress(1f)
        return LightingMap(w, h, FloatArray(sum.size) { (sum[it] / weights[it / 3]).toFloat() })
    }

    fun render(
        map: LightingMap,
        size: Int = SIZE,
        gain: Float? = null,
        progress: (Float) -> Unit = {},
        check: () -> Unit = {},
    ): IntArray {
        require(size in 16..512)
        // Same diffuse-light meter as the viewer, at reduced angular resolution. Only
        // the exposure calculation is reduced; reflections keep the 512px light map.
        val scale =
            gain
                ?: run {
                    val w =
                        (1..min(64, map.width)).last {
                            map.width % it == 0 && it % 2 == 0 && map.height % (it / 2) == 0
                        }
                    LightingExposure.grey(
                        map.reduced(w, w / 2).diffuse(32, 16) {
                            check()
                            progress(.15f * it)
                        }
                    )
                }
        require(scale.isFinite() && scale >= 0f)
        val out = IntArray(size * size)
        val radius = size * .48
        for (y in 0 until size) {
            check()
            for (x in 0 until size) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var coverage = 0
                // Four subpixels give a clean silhouette with actual alpha, no baked panel.
                for (sy in 0..1) for (sx in 0..1) {
                    val nx = (x + .25 + sx * .5 - size / 2.0) / radius
                    val ny = (size / 2.0 - y - .25 - sy * .5) / radius
                    val rr = nx * nx + ny * ny
                    if (rr > 1) continue
                    val nz = sqrt(1 - rr)
                    val light =
                        sample(map, V3(2 * nx * nz, 2 * ny * nz, 2 * nz * nz - 1)) *
                            scale.toDouble()
                    val peak = max(light.x, max(light.y, light.z))
                    val shoulder =
                        if (peak > .25) (.25 + .75 * (peak - .25) / (peak + .5)) / peak else 1.0
                    r += srgb(light.x * shoulder)
                    g += srgb(light.y * shoulder)
                    b += srgb(light.z * shoulder)
                    coverage++
                }
                if (coverage > 0)
                    out[y * size + x] =
                        ((coverage * 255 / 4) shl 24) or
                            ((r / coverage * 255).roundToInt().coerceIn(0, 255) shl 16) or
                            ((g / coverage * 255).roundToInt().coerceIn(0, 255) shl 8) or
                            (b / coverage * 255).roundToInt().coerceIn(0, 255)
            }
            if (y % 8 == 0) progress(.15f + .85f * (y + 1) / size)
        }
        progress(1f)
        return out
    }

    private fun sample(map: LightingMap, direction: V3): V3 {
        val uv = Sphere.uv(direction)
        val x = uv.first * map.width - .5
        val y = uv.second * map.height - .5
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        fun pixel(px: Int, py: Int): V3 {
            val i =
                (py.coerceIn(0, map.height - 1) * map.width +
                    (px % map.width + map.width) % map.width) * 3
            return V3(map.rgb[i].toDouble(), map.rgb[i + 1].toDouble(), map.rgb[i + 2].toDouble())
        }
        return (pixel(ix, iy) * (1 - fx) + pixel(ix + 1, iy) * fx) * (1 - fy) +
            (pixel(ix, iy + 1) * (1 - fx) + pixel(ix + 1, iy + 1) * fx) * fy
    }

    private fun srgb(value: Double) =
        if (value <= .0031308) 12.92 * value else 1.055 * value.pow(1 / 2.4) - .055
}
