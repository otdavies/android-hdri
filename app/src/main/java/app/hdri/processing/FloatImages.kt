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
        val buffer = ByteBuffer.allocate(file.length().toInt()).order(ByteOrder.LITTLE_ENDIAN)
        FileInputStream(file).use { input ->
            while (buffer.hasRemaining()) check(input.channel.read(buffer) > 0) {
                "A float checkpoint is incomplete."
            }
        }
        buffer.flip()
        require(buffer.int == MAGIC) { "The float checkpoint format is invalid." }
        val width = buffer.int
        val height = buffer.int
        val channels = buffer.int
        require(
            width in 1..8192 &&
                height in 1..8192 &&
                channels == 3 &&
                buffer.remaining().toLong() == width.toLong() * height * 12
        ) {
            "The float checkpoint dimensions are invalid."
        }
        val pixels = FloatArray(width * height * 3)
        buffer.asFloatBuffer().get(pixels)
        val mat = Mat(height, width, CvType.CV_32FC3)
        try {
            mat.put(0, 0, pixels)
            return mat
        } catch (e: Exception) {
            mat.release()
            throw e
        }
    }
}
