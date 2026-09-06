package app.hdri

import app.hdri.core.*
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class ExrReaderTest {
    @Test
    fun compressedAndUncompressedBlocksRoundTripEveryFloatAndPartialLastBlock() {
        for ((w, h) in listOf(1 to 1, 1 to 17, 97 to 35, 512 to 33)) {
            val file = File.createTempFile("sphere-float-", ".exr")
            try {
                fun value(x: Int, y: Int) =
                    when ((x + y) % 6) {
                        0 -> 0f
                        1 -> 1e-9f
                        2 -> 100_000f
                        else -> ((x * 3917L + y * 713L) % 1499).toFloat() / 13.7f
                    }
                ExrWriter.write(
                    file,
                    w,
                    h,
                    { y, row -> for (x in row.indices) row[x] = value(x, y) },
                    {},
                    {},
                )
                ExrReader(file).use { reader ->
                    assertEquals(w, reader.width)
                    assertEquals(h, reader.height)
                    val row = FloatArray(w * 3)
                    for (y in (0 until h).reversed()) {
                        reader.row(y, row)
                        row.forEachIndexed { x, v ->
                            assertEquals(value(x, y).toRawBits(), v.toRawBits())
                        }
                    }
                }
                RandomAccessFile(file, "rw").use { it.setLength(it.length() - 3) }
                assertThrows(Exception::class.java) {
                    ExrReader(file).use { it.row(h - 1, FloatArray(w * 3)) }
                }
            } finally {
                file.delete()
            }
        }
    }
}
