package app.hdri.processing

import app.hdri.core.ExrWriter
import java.io.File
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.imgcodecs.Imgcodecs

object ExrExport {
    fun write(
        source: File,
        destination: File,
        progress: (Float) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        checkCancelled()
        check(OpenCVLoader.initLocal()) { "The HDR image reader could not load." }
        val hdr = Imgcodecs.imread(source.path, Imgcodecs.IMREAD_UNCHANGED)
        try {
            check(!hdr.empty() && hdr.type() == CvType.CV_32FC3) {
                "The saved HDR environment could not be read."
            }
            ExrWriter.write(
                destination,
                hdr.cols(),
                hdr.rows(),
                { y, pixels -> hdr.get(y, 0, pixels) },
                progress,
                checkCancelled,
            )
        } finally {
            hdr.release()
        }
    }
}
