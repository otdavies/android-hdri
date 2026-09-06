package app.hdri.processing

import java.io.File
import org.opencv.core.Mat

/** Render-scoped LRU of native float images; originals remain on disk. */
internal class HdrCache(private val budget: Long = 192L * 1024 * 1024) : AutoCloseable {
    private val images = LinkedHashMap<String, Mat>(32, .75f, true)
    private var bytes = 0L
    var decodes = 0
        private set

    var peakBytes = 0L
        private set

    fun get(file: File): Mat {
        images[file.path]?.let {
            return it
        }
        val image = FloatImages.read(file)
        check(!image.empty()) { "A processed HDR image is missing. Retry processing." }
        val size = image.total() * image.elemSize()
        val entries = images.entries.iterator()
        while (bytes + size > budget && entries.hasNext()) {
            val old = entries.next().value
            bytes -= old.total() * old.elemSize()
            old.release()
            entries.remove()
        }
        images[file.path] = image
        bytes += size
        decodes++
        peakBytes = maxOf(peakBytes, bytes)
        return image
    }

    override fun close() {
        images.values.forEach { it.release() }
        images.clear()
        bytes = 0
    }
}
