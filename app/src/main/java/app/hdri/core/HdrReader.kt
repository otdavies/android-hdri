package app.hdri.core

import java.io.Closeable
import java.io.File

/** Streaming reader for sphere's Radiance RGBE masters (standard planar RLE, -Y +X). */
class HdrReader(file: File) : Closeable {
    private val input = file.inputStream().buffered(128 * 1024)
    val width: Int
    val height: Int
    private val channels: Array<ByteArray>
    private var rows = 0

    init {
        try {
            require(line() in listOf("#?RADIANCE", "#?RGBE"))
            var format = false
            var count = 0
            while (true) {
                val text = line()
                if (text.isEmpty()) break
                require(++count < 128)
                if (text == "FORMAT=32-bit_rle_rgbe") format = true
            }
            require(format)
            val size =
                Regex("-Y (\\d+) \\+X (\\d+)").matchEntire(line())
                    ?: error("Unsupported HDR orientation.")
            height = size.groupValues[1].toInt()
            width = size.groupValues[2].toInt()
            require(width in 8..8192 && height in 1..8192 && width.toLong() * height <= 33_554_432)
            channels = Array(4) { ByteArray(width) }
        } catch (e: Throwable) {
            input.close()
            throw e
        }
    }

    fun row(bgr: FloatArray) {
        require(rows < height && bgr.size >= width * 3)
        require(byte() == 2 && byte() == 2 && (byte() shl 8 or byte()) == width)
        for (channel in channels) {
            var x = 0
            while (x < width) {
                val code = byte()
                val count = if (code > 128) code - 128 else code
                require(count > 0 && x + count <= width) { "Invalid HDR run." }
                if (code > 128) channel.fill(byte().toByte(), x, x + count)
                else repeat(count) { channel[x + it] = byte().toByte() }
                x += count
            }
        }
        for (x in 0 until width) {
            val exponent = channels[3][x].toInt() and 255
            val scale = if (exponent == 0) 0f else Math.scalb(1f, exponent - 136)
            for (c in 0..2) {
                val value = (channels[2 - c][x].toInt() and 255) * scale
                require(value.isFinite()) { "Invalid HDR radiance." }
                bgr[x * 3 + c] = value
            }
        }
        rows++
    }

    private fun byte(): Int = input.read().also { require(it >= 0) { "Incomplete HDR master." } }

    private fun line(): String {
        val text = StringBuilder()
        repeat(4096) {
            val value = byte()
            if (value == 10) return text.toString().trimEnd('\r')
            require(value in 32..126 || value == 13)
            text.append(value.toChar())
        }
        error("HDR header is too long.")
    }

    override fun close() = input.close()
}
