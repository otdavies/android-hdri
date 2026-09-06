package app.hdri.processing

import app.hdri.core.ExrReader
import app.hdri.core.ExrWriter
import app.hdri.core.HdrWriter
import java.io.File
import java.io.FileOutputStream
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat

/** One retained master; standard exports are converted only when requested. */
object EnvironmentIO {
    fun read(file: File, check: () -> Unit = {}): Mat {
        check(OpenCVLoader.initLocal()) { "The HDR reader could not start." }
        check()
        if (file.extension != "exr") return FloatImages.read(file)
        ExrReader(file).use { reader ->
            val image = Mat(reader.height, reader.width, CvType.CV_32FC3)
            try {
                val row = FloatArray(reader.width * 3)
                for (y in 0 until reader.height) {
                    check()
                    reader.row(y, row)
                    image.put(y, 0, row)
                }
                return image
            } catch (e: Throwable) {
                image.release()
                throw e
            }
        }
    }

    fun convert(
        source: File,
        destination: File,
        progress: (Float) -> Unit,
        check: () -> Unit,
        note: String = "",
    ) {
        val image = read(source, check)
        try {
            require(!image.empty() && image.type() == CvType.CV_32FC3)
            if (destination.extension == "exr") {
                ExrWriter.write(
                    destination,
                    image.cols(),
                    image.rows(),
                    { y, row -> image.get(y, 0, row) },
                    { progress(it * .8f) },
                    check,
                    note,
                )
                // Verify every stored float before a migration is allowed to discard its old
                // master.
                ExrReader(destination).use { verify ->
                    require(verify.width == image.cols() && verify.height == image.rows())
                    val expected = FloatArray(image.cols() * 3)
                    val actual = FloatArray(expected.size)
                    for (y in 0 until image.rows()) {
                        check()
                        image.get(y, 0, expected)
                        verify.row(y, actual)
                        require(expected.contentEquals(actual)) {
                            "EXR verification failed. Original retained."
                        }
                        progress(.8f + .2f * (y + 1) / image.rows())
                    }
                }
            } else {
                require(destination.extension == "hdr")
                FileOutputStream(destination).use { file ->
                    val out = file.buffered(128 * 1024)
                    val writer = HdrWriter(out, image.cols(), image.rows(), note)
                    val row = FloatArray(image.cols() * 3)
                    for (y in 0 until image.rows()) {
                        check()
                        image.get(y, 0, row)
                        writer.row(row, 0)
                        progress(.8f * (y + 1f) / image.rows())
                    }
                    out.flush()
                    file.fd.sync()
                }
                val verify = read(destination, check)
                try {
                    require(
                        verify.rows() == image.rows() &&
                            verify.cols() == image.cols() &&
                            verify.type() == image.type()
                    )
                    val expected = FloatArray(image.cols() * 3)
                    val actual = FloatArray(expected.size)
                    for (y in 0 until image.rows()) {
                        check()
                        image.get(y, 0, expected)
                        verify.get(y, 0, actual)
                        for (x in expected.indices step 3) {
                            val tolerance =
                                maxOf(expected[x], expected[x + 1], expected[x + 2]) / 128f + 1e-32f
                            for (c in 0..2) require(
                                actual[x + c].isFinite() &&
                                    kotlin.math.abs(actual[x + c] - expected[x + c]) <= tolerance
                            ) {
                                "HDR verification failed. Original retained."
                            }
                        }
                        progress(.8f + .2f * (y + 1) / image.rows())
                    }
                } finally {
                    verify.release()
                }
            }
        } finally {
            image.release()
        }
    }
}
