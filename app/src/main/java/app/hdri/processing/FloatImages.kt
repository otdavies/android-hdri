package app.hdri.processing

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs

/** Lossless float checkpoints avoid repeated RGBE compression/decoding and mantissa loss. */
internal object FloatImages {
    private const val MAGIC = 0x4c534633

    fun write(file: File, width: Int, height: Int, pixels: FloatArray) {
        require(pixels.size == width * height * 3)
        val buffer = ByteBuffer.allocate(16 + pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(width).putInt(height).putInt(3)
        buffer.asFloatBuffer().put(pixels)
        buffer.position(0)
        FileOutputStream(file).use { out ->
            while (buffer.hasRemaining()) out.channel.write(buffer)
            out.fd.sync()
        }
    }

    fun read(file: File): Mat {
        if (file.extension != "f32") return Imgcodecs.imread(file.path, Imgcodecs.IMREAD_UNCHANGED)
        require(file.length() in 28..(256L * 1024 * 1024)) {
            "A float checkpoint is incomplete. Rebuild the sphere."
        }
        FileInputStream(file).use { input ->
            fun fill(buffer: ByteBuffer) {
                while (buffer.hasRemaining()) check(input.channel.read(buffer) > 0) {
                    "A float checkpoint is incomplete."
                }
                buffer.flip()
            }
            val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            fill(header)
            require(header.int == MAGIC) { "The float checkpoint format is invalid." }
            val width = header.int
            val height = header.int
            val channels = header.int
            require(
                width in 1..8192 &&
                    height in 1..8192 &&
                    channels == 3 &&
                    file.length() - 16 == width.toLong() * height * 12
            ) {
                "The float checkpoint dimensions are invalid."
            }
            // Keep two bounded row buffers instead of two whole-image JVM copies.
            // The decoded image itself lives in the native Mat/cache.
            val rows = minOf(64, height)
            val buffer = ByteBuffer.allocate(width * rows * 12).order(ByteOrder.LITTLE_ENDIAN)
            val pixels = FloatArray(width * rows * 3)
            val mat = Mat(height, width, CvType.CV_32FC3)
            try {
                var y = 0
                while (y < height) {
                    val count = width * minOf(rows, height - y) * 3
                    buffer.clear()
                    buffer.limit(count * 4)
                    fill(buffer)
                    buffer.asFloatBuffer().get(pixels, 0, count)
                    mat.put(y, 0, if (count == pixels.size) pixels else pixels.copyOf(count))
                    y += rows
                }
                return mat
            } catch (e: Throwable) {
                mat.release()
                throw e
            }
        }
    }
}
