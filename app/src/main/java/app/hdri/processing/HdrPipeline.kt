package app.hdri.processing

import android.os.Debug
import app.hdri.core.*
import app.hdri.core.Target
import app.hdri.data.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

class HdrPipeline(
    private val store: SessionStore,
    private val project: Project,
    private val progress: (String, Double) -> Unit,
    private val checkCancelled: () -> Unit,
) {
    private val dir = store.dir(project.id)
    private val cache = File(dir, "processed").apply { mkdirs() }
    private val warnings = mutableListOf<String>()
    private val maxEdge = if (project.quality == Quality.DETAIL) 1280 else 800

    private fun load(c: Capture): List<Mat> {
        val images = mutableListOf<Mat>()
        try {
            c.exposures.forEach { exposure ->
                checkCancelled()
                val source = File(dir, exposure.file)
                require(source.parentFile == dir && source.isFile) {
                    "A source exposure is missing. Capture that direction again."
                }
                val m = Imgcodecs.imread(source.path, Imgcodecs.IMREAD_REDUCED_COLOR_2)
                check(!m.empty()) {
                    "A source photo could not be decoded. Capture that direction again."
                }
                val scale = min(1.0, maxEdge.toDouble() / max(m.cols(), m.rows()))
                if (scale < 1)
                    Imgproc.resize(
                        m,
                        m,
                        Size(
                            (m.cols() * scale).roundToInt().toDouble(),
                            (m.rows() * scale).roundToInt().toDouble(),
                        ),
                        0.0,
                        0.0,
                        Imgproc.INTER_AREA,
                    )
                images += m
            }
            return images
        } catch (e: Exception) {
            images.forEach { it.release() }
            throw e
        }
    }

    private fun align(images: List<Mat>): List<Mat> {
        val aligned = mutableListOf<Mat>()
        val aligner = Photo.createAlignMTB(5, 4, false)
        val reference = Mat()
        Imgproc.cvtColor(images[images.size / 2], reference, Imgproc.COLOR_BGR2GRAY)
        try {
            // OpenCV's generated Java batch binding converts the output list as an input
            // vector and does not return newly allocated Mats. Use explicit output Mats.
            images.forEachIndexed { index, source ->
                checkCancelled()
                val gray = Mat()
                val output = Mat()
                try {
                    Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
                    var shift =
                        if (index == images.size / 2) Point()
                        else aligner.calculateShift(reference, gray)
                    val limit = min(source.cols(), source.rows()) * .04
                    if (abs(shift.x) > limit || abs(shift.y) > limit) {
                        // A very dark/flat exposure can give an arbitrary MTB displacement.
                        // Preserve its capture prior; never apply a shift beyond valid overlap.
                        shift = Point()
                        warnings +=
                            "Some exposures had too little detail for reliable bracket alignment. Inspect the sphere for ghosting."
                    }
                    aligner.shiftMat(source, output, shift)
                    aligned += output
                } catch (e: Exception) {
                    output.release()
                    throw e
                } finally {
                    gray.release()
                }
            }
            return aligned
        } catch (e: Exception) {
            aligned.forEach { it.release() }
            throw e
        } finally {
            reference.release()
            aligner.clear()
        }
    }

    private fun response(): FloatArray {
        progress("Measuring camera response", .02)
        checkCancelled()
        // Prefer the richest middle exposure, avoiding a blank sky calibration.
        val candidate =
            project.captures.maxBy { capture ->
                val image =
                    Imgcodecs.imread(
                        File(dir, capture.exposures[capture.exposures.size / 2].file).path,
                        Imgcodecs.IMREAD_REDUCED_GRAYSCALE_8,
                    )
                if (image.empty()) return@maxBy 0.0
                val mean = MatOfDouble()
                val std = MatOfDouble()
                Core.meanStdDev(image, mean, std)
                val score = std.toArray().first()
                image.release()
                mean.release()
                std.release()
                score
            }
        val raw = load(candidate)
        val images =
            try {
                align(raw)
            } catch (e: Exception) {
                raw.forEach { it.release() }
                throw e
            }
        val response = Mat()
        val times = Mat(candidate.exposures.size, 1, CvType.CV_32FC1)
        times.put(0, 0, candidate.exposures.map { it.seconds.toFloat() }.toFloatArray())
        val calibrator = Photo.createCalibrateDebevec(100, 20f, false)
        try {
            calibrator.process(images, response, times)
            val curve = FloatArray(768)
            response.get(0, 0, curve)
            check(curve.all { it.isFinite() && it > 0 }) {
                "The scene did not provide enough brightness variation to calibrate HDR. Include a textured, mid-brightness view and retry."
            }
            // A noisy response can wiggle slightly; large reversals indicate a failed calibration.
            val reversals =
                (1..254).count { z ->
                    (0..2).any { c -> curve[(z + 1) * 3 + c] < curve[z * 3 + c] * .8f }
                }
            check(reversals < 20) {
                "HDR response calibration was unstable. Try a static scene with varied brightness."
            }
            File(cache, "response.json")
                .writeText(
                    JSONObject()
                        .put("method", "Debevec-Malik")
                        .put("bgrResponse", JSONArray(curve.toList()))
                        .toString()
                )
            return curve
        } finally {
            raw.forEach { it.release() }
            images.forEach { it.release() }
            response.release()
            times.release()
            calibrator.clear()
        }
    }

    fun run() {
        val started = System.currentTimeMillis()
        check(OpenCVLoader.initLocal()) {
            "The on-device image engine could not load. Reinstall the APK for this phone."
        }
        Core.setNumThreads(2)
        require(
            project.captures.size >= 6 &&
                project.targets.all { t -> project.captures.any { it.targetId == t.id } }
        ) {
            "Finish capturing the remaining directions before building your sphere."
        }
        store.checkSpace(project.id, 700_000_000)
        progress("Checking saved exposures", .01)
        checkCancelled()
        val curve = response()
        val responseHash =
            MessageDigest.getInstance("SHA-256")
                .digest(curve.joinToString().toByteArray())
                .joinToString("") { "%02x".format(it) }
        val frames =
            project.captures.mapIndexed { index, capture ->
                checkCancelled()
                progress(
                    "Aligning and merging HDR · ${index+1}/${project.captures.size}",
                    .05 + .3 * index / project.captures.size,
                )
                val hdr = File(cache, "${capture.targetId}.hdr")
                val preview = File(cache, "${capture.targetId}.jpg")
                val stamp = File(cache, "${capture.targetId}.stamp")
                val key = "v2-$maxEdge-$responseHash-${capture.exposures}"
                val metadata = runCatching { JSONObject(stamp.readText()) }.getOrNull()
                if (hdr.isFile && preview.isFile && metadata?.optString("key") == key) {
                    val savedWarnings = metadata.getJSONArray("warnings")
                    for (i in 0 until savedWarnings.length()) warnings += savedWarnings.getString(i)
                    val thumb = Imgcodecs.imread(preview.path)
                    val lens = capture.lens.scaled(thumb.cols(), thumb.rows())
                    thumb.release()
                    return@mapIndexed Prepared(capture, lens, hdr, preview)
                }
                val warningStart = warnings.size
                val raw = load(capture)
                val aligned =
                    try {
                        align(raw)
                    } catch (e: Exception) {
                        raw.forEach { it.release() }
                        throw e
                    }
                try {
                    val bytes =
                        aligned.map { m ->
                            ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }
                        }
                    val result =
                        Radiance.merge(
                            bytes,
                            capture.exposures.map { it.seconds }.toDoubleArray(),
                            curve,
                        )
                    val rows = aligned[0].rows()
                    val cols = aligned[0].cols()
                    val pixels = rows * cols
                    if (result.clipped > pixels * .003)
                        warnings +=
                            "Direction ${capture.targetId+1}: bright sources exceed the shortest exposure; their radiance is a lower bound."
                    if (result.moving > pixels * .1)
                        warnings +=
                            "Direction ${capture.targetId+1}: movement was detected within the bracket; inspect for ghosting."
                    val linear = Mat(rows, cols, CvType.CV_32FC3)
                    linear.put(0, 0, result.rgb)
                    try {
                        val temp = File(cache, "${capture.targetId}.tmp.hdr")
                        check(Imgcodecs.imwrite(temp.path, linear)) {
                            "Could not checkpoint HDR data."
                        }
                        check(temp.renameTo(hdr)) { "Could not finish the HDR checkpoint." }
                        check(Imgcodecs.imwrite(preview.path, aligned[aligned.size / 2])) {
                            "Could not save alignment preview."
                        }
                        // Commit metadata last. A partial stamp cannot validate a checkpoint,
                        // and a reused checkpoint must retain the original quality findings.
                        stamp.writeText(
                            JSONObject()
                                .put("key", key)
                                .put("warnings", JSONArray(warnings.drop(warningStart)))
                                .toString()
                        )
                    } finally {
                        linear.release()
                    }
                    Prepared(capture, capture.lens.scaled(cols, rows), hdr, preview)
                } finally {
                    raw.forEach { it.release() }
                    aligned.forEach { it.release() }
                }
            }
        warnings +=
            Registration.refine(
                frames,
                { stage, p -> progress(stage, .35 + .15 * p) },
                checkCancelled,
            )
        progress("Checking full sphere coverage", .51)
        val seams =
            SphericalBlend.seams(
                frames,
                { stage, p -> progress(stage, .51 + .09 * p) },
                checkCancelled,
            )
        val missing = seams.labels.count { it < 0 }.toDouble() / seams.labels.size
        if (missing > .002) {
            if (!project.sample) {
                val extra =
                    seams.uncovered.mapIndexed { i, v ->
                        val uv = Sphere.uv(v)
                        Target(
                            project.targets.size + i,
                            (uv.first - .5) * 360,
                            (.5 - uv.second) * 180,
                        )
                    }
                store.update(project.id) { it.copy(targets = it.targets + extra) }
            }
            error(
                "${"%.1f".format(missing*100)}% of the sphere is uncovered. Return to capture and fill the added gap directions."
            )
        }
        if (missing > 0)
            warnings +=
                "A small unmeasured area (${String.format(java.util.Locale.getDefault(),"%.2f",missing*100)}%) remains. Inspect the poles before using this environment."
        val tempHdr = File(dir, "environment.partial.hdr")
        val tempJpg = File(dir, "preview.partial.jpg")
        try {
            SphericalBlend.render(
                frames,
                seams,
                project.quality.outputWidth,
                tempHdr,
                tempJpg,
                { stage, p -> progress(stage, .61 + .36 * p) },
                checkCancelled,
            )
            progress("Writing export metadata", .98)
            checkCancelled()
            addPhotoSphereMetadata(
                tempJpg,
                project.quality.outputWidth,
                project.quality.outputWidth / 2,
            )
            check(
                tempHdr.renameTo(File(dir, "environment.hdr")) &&
                    tempJpg.renameTo(File(dir, "preview.jpg"))
            ) {
                "Could not finalize HDR exports. Free storage and retry."
            }
            File(dir, "report.json")
                .writeText(
                    JSONObject()
                        .put("pipelineVersion", 1)
                        .put("width", project.quality.outputWidth)
                        .put("height", project.quality.outputWidth / 2)
                        .put(
                            "radiance",
                            "relative linear RGB, D65; JPEG camera-response estimate, not absolute photometry",
                        )
                        .put("coverage", 1 - missing)
                        .put("durationSeconds", (System.currentTimeMillis() - started) / 1000.0)
                        .put("nativeHeapBytesAtFinish", Debug.getNativeHeapAllocatedSize())
                        .put("warnings", JSONArray(warnings))
                        .put(
                            "rotations",
                            JSONArray(
                                frames.map {
                                    listOf(
                                        it.rotation.x,
                                        it.rotation.y,
                                        it.rotation.z,
                                        it.rotation.w,
                                    )
                                }
                            ),
                        )
                        .toString(2)
                )
            store.update(project.id) {
                it.copy(
                    state = if (warnings.isEmpty()) "ready" else "review",
                    stage = "Your HDR sphere is ready",
                    progress = 1.0,
                    warnings = warnings.distinct(),
                    error = null,
                )
            }
            progress("Your HDR sphere is ready", 1.0)
        } finally {
            tempHdr.delete()
            tempJpg.delete()
        }
    }

    private fun addPhotoSphereMetadata(file: File, width: Int, height: Int) {
        val xml =
            "http://ns.adobe.com/xap/1.0/\u0000" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" GPano:ProjectionType=\"equirectangular\" GPano:UsePanoramaViewer=\"True\" GPano:FullPanoWidthPixels=\"$width\" GPano:FullPanoHeightPixels=\"$height\" GPano:CroppedAreaImageWidthPixels=\"$width\" GPano:CroppedAreaImageHeightPixels=\"$height\" GPano:CroppedAreaLeftPixels=\"0\" GPano:CroppedAreaTopPixels=\"0\"/></rdf:RDF></x:xmpmeta>"
        val payload = xml.toByteArray()
        val data = file.readBytes()
        require(data[0] == (-1).toByte() && data[1] == 0xd8.toByte())
        file.outputStream().buffered().use { out ->
            out.write(data, 0, 2)
            out.write(0xff)
            out.write(0xe1)
            out.write((payload.size + 2) shr 8)
            out.write((payload.size + 2) and 255)
            out.write(payload)
            out.write(data, 2, data.size - 2)
        }
    }
}
