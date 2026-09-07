package app.hdri

import app.hdri.core.*
import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ReflectionThumbnailTest {
    @Test
    fun alphaSilhouetteAndLinearMiddleGreyAreCorrect() {
        val map = LightingMap(64, 32, FloatArray(64 * 32 * 3) { .18f })
        val image = ReflectionThumbnail.render(map, 101, 1f)
        assertEquals(0, image[0])
        assertEquals(0, image[100])
        val center = image[50 * 101 + 50]
        assertEquals(255, center ushr 24)
        assertEquals(118, center and 255)
        assertEquals(118, center ushr 8 and 255)
        assertEquals(118, center ushr 16 and 255)
        assertTrue(image.any { it ushr 24 in 1..254 })
        val area = image.sumOf { (it ushr 24) / 255.0 }
        assertEquals(PI * (101 * .48).pow(2), area, 15.0)
    }

    @Test
    fun mirrorReflectsBackAtCenterAndDoesNotFlipTheVerticalAxis() {
        val data = FloatArray(256 * 128 * 3)
        for (y in 0 until 128) for (x in 0 until 256) {
            val d = Sphere.ray(x + .5, y + .5, 256, 128)
            val i = (y * 256 + x) * 3
            data[i] = ((d.x + 1) * .05).toFloat()
            data[i + 1] = ((d.y + 1) * .05).toFloat()
            data[i + 2] = ((d.z + 1) * .05).toFloat()
        }
        val image = ReflectionThumbnail.render(LightingMap(256, 128, data), 101, 1f)
        fun at(x: Int, y: Int) = image[y * 101 + x]
        assertEquals(
            89.0,
            (at(50, 50) and 255).toDouble(),
            1.0,
        ) // linear 0.1 -> sRGB, back of environment
        assertTrue((at(70, 50) ushr 16 and 255) > (at(30, 50) ushr 16 and 255))
        assertTrue((at(50, 30) ushr 8 and 255) > (at(50, 70) ushr 8 and 255))
        assertTrue((at(50, 50) and 255) > (at(96, 50) and 255))
    }

    @Test
    fun streamingBothMasterFormatsPreservesColorAndLightEnergy() {
        val exr = File.createTempFile("reflection-", ".exr")
        val hdr = File.createTempFile("reflection-", ".hdr")
        val w = 1024
        val h = 512
        fun row(y: Int, out: FloatArray) {
            for (x in 0 until w) {
                val d = Sphere.ray(x + .5, y + .5, w, h)
                val light = AnalyticLight.sample(d)
                out[x * 3] = light.z.toFloat()
                out[x * 3 + 1] = light.y.toFloat()
                out[x * 3 + 2] = light.x.toFloat()
            }
        }
        try {
            ExrWriter.write(exr, w, h, ::row, {}, {})
            HdrWriter(hdr.outputStream().buffered(), w, h).use { writer ->
                val pixels = FloatArray(w * 3)
                repeat(h) {
                    row(it, pixels)
                    writer.row(pixels)
                }
            }
            val a = ReflectionThumbnail.load(exr)
            val b = ReflectionThumbnail.load(hdr)
            assertEquals(512, a.width)
            assertEquals(256, a.height)
            for (i in a.rgb.indices step 3) {
                val tolerance = maxOf(a.rgb[i], a.rgb[i + 1], a.rgb[i + 2]) / 128 + .00001f
                for (c in 0..2) assertEquals(a.rgb[i + c], b.rgb[i + c], tolerance)
            }
            assertEquals(a.meanLuminance(), b.meanLuminance(), a.meanLuminance() * .008)
        } finally {
            exr.delete()
            hdr.delete()
        }
    }

    @Test
    fun radianceRunsAndTruncatedInputAreHandled() {
        val file = File.createTempFile("reflection-run-", ".hdr")
        try {
            file.outputStream().use { out ->
                out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 4 +X 8\n".toByteArray())
                repeat(4) {
                    out.write(byteArrayOf(2, 2, 0, 8))
                    listOf(128, 64, 32, 129).forEach {
                        out.write(136)
                        out.write(it)
                    }
                }
            }
            val map = ReflectionThumbnail.load(file)
            assertEquals(1f, map.rgb[0], 0f)
            assertEquals(.5f, map.rgb[1], 0f)
            assertEquals(.25f, map.rgb[2], 0f)
            file.writeBytes(file.readBytes().dropLast(1).toByteArray())
            assertThrows(IllegalArgumentException::class.java) { ReflectionThumbnail.load(file) }
        } finally {
            file.delete()
        }
    }
}
