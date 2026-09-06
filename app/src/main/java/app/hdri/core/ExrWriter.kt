package app.hdri.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Single-part, scanline OpenEXR v2: lossless ZIP, full 32-bit float B/G/R.
 * https://openexr.com/en/latest/OpenEXRFileLayout.html Input is linear BGR, top to bottom. Only one
 * 16-row block is held on the JVM heap.
 */
object ExrWriter {
    fun write(
        file: File,
        width: Int,
        height: Int,
        row: (Int, FloatArray) -> Unit,
        progress: (Float) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        require(width in 1..16384 && height in 1..16384)
        val channels =
            ByteArrayOutputStream()
                .apply {
                    for (name in listOf("B", "G", "R")) {
                        write((name + "\u0000").toByteArray())
                        write(ints(2, 0, 1, 1)) // FLOAT, pLinear + reserved, unit sampling
                    }
                    write(0)
                }
                .toByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ints(20000630, 2))
                fun attr(name: String, type: String, value: ByteArray) {
                    out.write((name + "\u0000" + type + "\u0000").toByteArray(Charsets.US_ASCII))
                    out.write(ints(value.size))
                    out.write(value)
                }
                attr("channels", "chlist", channels)
                attr("compression", "compression", byteArrayOf(3)) // ZIP, 16 rows
                attr("dataWindow", "box2i", ints(0, 0, width - 1, height - 1))
                attr("displayWindow", "box2i", ints(0, 0, width - 1, height - 1))
                attr("lineOrder", "lineOrder", byteArrayOf(0))
                attr("pixelAspectRatio", "float", floats(1f))
                attr("screenWindowCenter", "v2f", floats(0f, 0f))
                attr("screenWindowWidth", "float", floats(1f))
                attr(
                    "chromaticities",
                    "chromaticities",
                    floats(.64f, .33f, .30f, .60f, .15f, .06f, .3127f, .3290f),
                )
                attr("envmap", "envmap", byteArrayOf(0)) // latitude-longitude
                attr(
                    "comments",
                    "string",
                    "sphere: relative linear RGB, Rec.709 primaries / D65; no tone mapping or exposure adjustment."
                        .toByteArray(),
                )
                out.write(0)
                val table = out.filePointer
                val offsets = LongArray((height + 15) / 16)
                repeat(offsets.size) { out.writeLong(0) }
                val pixels = FloatArray(width * 3)
                val raw = ByteArray(width * 12 * 16)
                val reordered = ByteArray(raw.size)
                val packed = ByteArray(raw.size + 1024)
                for (y in 0 until height step 16) {
                    checkCancelled()
                    offsets[y / 16] = out.filePointer
                    val count = minOf(16, height - y)
                    val bytes = count * width * 12
                    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(count) { dy ->
                        row(y + dy, pixels)
                        for (c in 0..2) for (x in 0 until width) buffer.putFloat(pixels[x * 3 + c])
                    }
                    // OpenEXR ZIP byte shuffle, then modulo-256 delta prediction.
                    var first = 0
                    var second = (bytes + 1) / 2
                    for (i in 0 until bytes) {
                        if (i % 2 == 0) reordered[first++] = raw[i]
                        else reordered[second++] = raw[i]
                    }
                    var previous = reordered[0].toInt() and 255
                    for (i in 1 until bytes) {
                        val current = reordered[i].toInt() and 255
                        reordered[i] = (current - previous + 128).toByte()
                        previous = current
                    }
                    deflater.reset()
                    deflater.setInput(reordered, 0, bytes)
                    deflater.finish()
                    var n = 0
                    while (!deflater.finished() && n < bytes) {
                        checkCancelled()
                        n += deflater.deflate(packed, n, packed.size - n)
                    }
                    val compressed = deflater.finished() && n < bytes
                    out.write(ints(y, if (compressed) n else bytes))
                    out.write(if (compressed) packed else raw, 0, if (compressed) n else bytes)
                    progress((y + count).toFloat() / height)
                }
                checkCancelled()
                out.seek(table)
                val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                offsets.forEach {
                    buffer.clear()
                    out.write(buffer.putLong(it).array())
                }
            }
        } finally {
            deflater.end()
        }
    }

    private fun ints(vararg values: Int) =
        ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }
            .array()

    private fun floats(vararg values: Float) = ints(*values.map { it.toRawBits() }.toIntArray())
}
