package app.hdri.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/** Bounded reader for sphere's lossless scanline FLOAT/ZIP master, independent of native codecs. */
class ExrReader(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    val width: Int
    val height: Int
    private val offsets: LongArray
    private var block = -1
    private var decoded = ByteArray(0)

    init {
        try {
            require(int() == 20000630 && int() == 2) { "Unsupported EXR master." }
            val attributes = mutableMapOf<String, Pair<String, ByteArray>>()
            while (true) {
                val name = string()
                if (name.isEmpty()) break
                val type = string()
                val size = int()
                require(size in 0..4096 && attributes.size < 40 && name !in attributes)
                attributes[name] = type to ByteArray(size).also { input.readFully(it) }
            }
            fun attr(name: String, type: String): ByteArray {
                val value = attributes[name] ?: error("EXR is missing $name.")
                require(value.first == type)
                return value.second
            }
            val window = buffer(attr("dataWindow", "box2i"))
            require(window.remaining() == 16 && window.int == 0 && window.int == 0)
            width = window.int + 1
            height = window.int + 1
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 33_554_432)
            require(attr("compression", "compression").contentEquals(byteArrayOf(3)))
            require(attr("lineOrder", "lineOrder").contentEquals(byteArrayOf(0)))
            val channels = buffer(attr("channels", "chlist"))
            for (name in byteArrayOf(66, 71, 82)) {
                require(
                    channels.remaining() >= 18 &&
                        channels.get() == name &&
                        channels.get() == 0.toByte()
                )
                require(channels.int == 2) { "The EXR master must contain FLOAT RGB channels." }
                channels.int // pLinear and reserved bytes
                require(channels.int == 1 && channels.int == 1)
            }
            require(channels.remaining() == 1 && channels.get() == 0.toByte())
            offsets =
                LongArray((height + 15) / 16) { java.lang.Long.reverseBytes(input.readLong()) }
            val first = input.filePointer
            require(offsets.all { it >= first && it < input.length() - 8 })
        } catch (e: Throwable) {
            input.close()
            throw e
        }
    }

    fun row(y: Int, pixels: FloatArray) {
        require(y in 0 until height && pixels.size >= width * 3)
        val wanted = y / 16
        if (block != wanted) {
            input.seek(offsets[wanted])
            require(int() == wanted * 16)
            val count = minOf(16, height - wanted * 16) * width * 12
            val size = int()
            require(size in 1..count && size <= input.length() - input.filePointer)
            val packed = ByteArray(size).also { input.readFully(it) }
            decoded =
                if (size == count) packed
                else {
                    val shuffled = ByteArray(count)
                    val inflater = Inflater()
                    try {
                        inflater.setInput(packed)
                        var n = 0
                        while (!inflater.finished() && n < count) {
                            val read = inflater.inflate(shuffled, n, count - n)
                            check(read > 0) { "Incomplete compressed EXR block." }
                            n += read
                        }
                        require(n == count && inflater.finished() && inflater.remaining == 0)
                    } finally {
                        inflater.end()
                    }
                    for (i in 1 until count) shuffled[i] =
                        ((shuffled[i - 1].toInt() and 255) + (shuffled[i].toInt() and 255) - 128)
                            .toByte()
                    ByteArray(count) { i ->
                        shuffled[i / 2 + if (i % 2 == 0) 0 else (count + 1) / 2]
                    }
                }
            block = wanted
        }
        val values = buffer(decoded)
        values.position((y % 16) * width * 12)
        for (c in 0..2) for (x in 0 until width) {
            val value = values.float
            require(value.isFinite() && value >= 0f) { "The EXR contains invalid radiance." }
            pixels[x * 3 + c] = value
        }
    }

    private fun int() = Integer.reverseBytes(input.readInt())

    private fun string(): String {
        val out = StringBuilder()
        repeat(256) {
            val b = input.readUnsignedByte()
            if (b == 0) return out.toString()
            require(b in 32..126)
            out.append(b.toChar())
        }
        error("Invalid EXR attribute.")
    }

    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    override fun close() = input.close()
}
