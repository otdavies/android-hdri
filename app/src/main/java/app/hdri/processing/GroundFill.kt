package app.hdri.processing

import app.hdri.core.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Approximate nadir on a tangent plane, so texture does not stretch into polar stripes. */
internal object GroundFill {
    const val MIN_PITCH = -55.0
    private const val N = 288
    private const val EXTENT = 1.5
    private val radius = tan(Math.toRadians(90 + MIN_PITCH))

    fun apply(image: Mat, progress: (Double) -> Unit, check: () -> Unit) {
        require(image.type() == CvType.CV_32FC3 && image.cols() == image.rows() * 2)
        val mx = Mat(N, N, CvType.CV_32F)
        val my = Mat(N, N, CvType.CV_32F)
        val plane = Mat()
        val xmap = FloatArray(N * N)
        val ymap = FloatArray(N * N)
        try {
            for (y in 0 until N) for (x in 0 until N) {
                val uv = Sphere.uv(V3(coordinate(x), -1.0, coordinate(y)))
                xmap[y * N + x] = (uv.first * image.cols() - .5).toFloat()
                ymap[y * N + x] = (uv.second * image.rows() - .5).toFloat()
            }
            mx.put(0, 0, xmap)
            my.put(0, 0, ymap)
            Imgproc.remap(image, plane, mx, my, Imgproc.INTER_LINEAR, Core.BORDER_WRAP)
            val rgb = FloatArray(N * N * 3)
            plane.get(0, 0, rgb)
            for (i in rgb.indices) rgb[i] = ln(max(1e-8f, rgb[i]))
            progress(.1)
            // Choose a quiet, measured patch outside the excluded cap. No pixels in the
            // cap (including feet or a tripod) participate in either colour or texture.
            val patch = 32
            val origins =
                (0 until 24).map { i ->
                    val a = i * PI / 12
                    ((1.02 * cos(a) / EXTENT + 1) * N / 2).roundToInt() - patch / 2 to
                        ((1.02 * sin(a) / EXTENT + 1) * N / 2).roundToInt() - patch / 2
                }
            fun variance(origin: Pair<Int, Int>): Double {
                val sum = DoubleArray(3)
                val square = DoubleArray(3)
                for (y in 0 until patch) for (x in 0 until patch) for (c in 0..2) {
                    val v = rgb[((origin.second + y) * N + origin.first + x) * 3 + c].toDouble()
                    if (v < ln(1e-7)) return Double.POSITIVE_INFINITY
                    sum[c] += v
                    square[c] += v * v
                }
                return (0..2).sumOf {
                    square[it] / (patch * patch) - (sum[it] / (patch * patch)).pow(2)
                }
            }
            val chosen = origins.minBy { variance(it) }
            require(variance(chosen).isFinite()) {
                "Not enough measured ground surrounds the fill area. Capture the ground normally."
            }
            val textureMat = Mat(patch, patch, CvType.CV_32FC3)
            val smooth = Mat()
            val texture = FloatArray(patch * patch * 3)
            try {
                for (y in 0 until patch) for (x in 0 until patch) for (c in 0..2) texture[
                    (y * patch + x) * 3 + c] =
                    rgb[((chosen.second + y) * N + chosen.first + x) * 3 + c]
                textureMat.put(0, 0, texture)
                Imgproc.GaussianBlur(textureMat, smooth, Size(0.0, 0.0), 3.0)
                val low = FloatArray(texture.size)
                smooth.get(0, 0, low)
                for (i in texture.indices) texture[i] = (texture[i] - low[i]).coerceIn(-.5f, .5f)
            } finally {
                textureMat.release()
                smooth.release()
            }
            // Solve smooth log-lighting from an annulus on a small plane, independently of
            // the patch detail. Harmonic interpolation is bounded by measured colours.
            val side = 96
            val field = FloatArray(side * side * 3)
            val hole = BooleanArray(side * side)
            val mean = DoubleArray(3)
            var count = 0
            for (y in 0 until side) for (x in 0 until side) {
                val r =
                    hypot(
                        (x + .5) * 2 * EXTENT / side - EXTENT,
                        (y + .5) * 2 * EXTENT / side - EXTENT,
                    )
                val i = y * side + x
                hole[i] = r < radius + .025
                for (c in 0..2) field[i * 3 + c] = rgb[((y * 3 + 1) * N + x * 3 + 1) * 3 + c]
                if (r in (radius + .025)..(radius + .12)) {
                    count++
                    for (c in 0..2) mean[c] += field[i * 3 + c]
                }
            }
            for (i in hole.indices) if (hole[i])
                for (c in 0..2) field[i * 3 + c] = (mean[c] / max(1, count)).toFloat()
            repeat(650) { iteration ->
                if (iteration % 25 == 0) {
                    check()
                    progress(.15 + .6 * iteration / 650)
                }
                for (y in 1 until side - 1) for (x in 1 until side - 1) {
                    val i = y * side + x
                    if (hole[i])
                        for (c in 0..2) field[i * 3 + c] =
                            (field[(i - 1) * 3 + c] +
                                field[(i + 1) * 3 + c] +
                                field[(i - side) * 3 + c] +
                                field[(i + side) * 3 + c]) * .25f
                }
            }
            val small = Mat(side, side, CvType.CV_32FC3)
            val large = Mat()
            try {
                small.put(0, 0, field)
                Imgproc.resize(
                    small,
                    large,
                    Size(N.toDouble(), N.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR,
                )
                large.get(0, 0, rgb)
            } finally {
                small.release()
                large.release()
            }
            fun mirror(i: Int): Int =
                (i % (2 * patch)).let { if (it < patch) it else 2 * patch - 1 - it }
            for (y in 0 until N) for (x in 0 until N) for (c in 0..2) {
                val i = (y * N + x) * 3 + c
                rgb[i] = exp(rgb[i] + texture[(mirror(y) * patch + mirror(x)) * 3 + c] * .7f)
            }
            plane.put(0, 0, rgb)
            // Directly sample the same tangent plane at every longitude, including wrap and
            // the pole. Blend only inside the declared synthetic cap; measured pixels stay put.
            val first = ceil((.5 - MIN_PITCH / 180) * image.rows() - .5).toInt()
            val row = FloatArray(image.cols() * 3)
            for (y in first until image.rows()) {
                check()
                image.get(y, 0, row)
                for (x in 0 until image.cols()) {
                    val ray = Sphere.ray(x + .5, y + .5, image.cols(), image.rows())
                    val px = ray.x / -ray.y
                    val py = ray.z / -ray.y
                    val fx = ((px / EXTENT + 1) * N / 2 - .5).coerceIn(0.0, N - 1.001)
                    val fy = ((py / EXTENT + 1) * N / 2 - .5).coerceIn(0.0, N - 1.001)
                    val ix = fx.toInt()
                    val iy = fy.toInt()
                    val dx = fx - ix
                    val dy = fy - iy
                    val t = ((radius - hypot(px, py)) / .075).coerceIn(0.0, 1.0)
                    val blend = t * t * (3 - 2 * t)
                    for (c in 0..2) {
                        val a =
                            rgb[(iy * N + ix) * 3 + c] * (1 - dx) +
                                rgb[(iy * N + ix + 1) * 3 + c] * dx
                        val b =
                            rgb[((iy + 1) * N + ix) * 3 + c] * (1 - dx) +
                                rgb[((iy + 1) * N + ix + 1) * 3 + c] * dx
                        val fill = a * (1 - dy) + b * dy
                        val original = row[x * 3 + c]
                        row[x * 3 + c] =
                            (if (original <= 1e-8f) fill else original * (1 - blend) + fill * blend)
                                .toFloat()
                    }
                }
                image.put(y, 0, row)
                progress(.75 + .25 * (y - first + 1) / (image.rows() - first))
            }
        } finally {
            mx.release()
            my.release()
            plane.release()
        }
    }

    private fun coordinate(i: Int) = (i + .5) * 2 * EXTENT / N - EXTENT

    fun rewrite(
        hdr: File,
        jpeg: File,
        exposure: Double,
        progress: (Double) -> Unit,
        check: () -> Unit,
    ) {
        val image = EnvironmentIO.read(hdr, check)
        try {
            apply(image, { progress(it * .8) }, check)
            HdrWriter(
                    FileOutputStream(hdr).buffered(128 * 1024),
                    image.cols(),
                    image.rows(),
                    "Ground below -55 degrees is synthesized; not measured scene detail.",
                )
                .use { writer ->
                    val row = FloatArray(image.cols() * 3)
                    for (y in 0 until image.rows()) {
                        check()
                        image.get(y, 0, row)
                        writer.row(row, 0)
                    }
                }
            val preview = Mat(image.rows(), image.cols(), CvType.CV_8UC3)
            try {
                val row = FloatArray(image.cols() * 3)
                val out = ByteArray(row.size)
                for (y in 0 until image.rows()) {
                    check()
                    image.get(y, 0, row)
                    for (i in row.indices) {
                        val v = max(0.0, row[i] * exposure)
                        out[i] =
                            (Radiance.srgb(v / (1 + v)) * 255)
                                .roundToInt()
                                .coerceIn(0, 255)
                                .toByte()
                    }
                    preview.put(y, 0, out)
                }
                require(Imgcodecs.imwrite(jpeg.path, preview)) {
                    "Could not save the ground-fill preview."
                }
            } finally {
                preview.release()
            }
            progress(1.0)
        } finally {
            image.release()
        }
    }
}
