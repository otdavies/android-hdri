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

class HdrPipeline(
    private val store: SessionStore,
    private val project: Project,
    private val progress: (String, Double) -> Unit,
    private val checkCancelled: () -> Unit,
) {
    private val dir = store.dir(project.id)
    private val cache = File(dir, "processed").apply { mkdirs() }
    private val warnings =
        project.captures
            .flatMap { c -> c.warnings.map { "Direction ${c.targetId+1}: $it" } }
            .toMutableList()
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

    private fun align(images: List<Mat>): List<Mat> =
        BracketAlignment.align(images, checkCancelled) { warnings += it }

    private fun response(): FloatArray {
        progress("Reading camera tone response", .02)
        checkCancelled()
        // A single three-exposure bracket does not constrain a free 256-entry response.
        // Its monotonic envelope can flatten whole tonal ranges. Camera2 already uses
        // our fixed, identical RGB curve: invert that curve instead of guessing it again.
        return if (project.sample) Radiance.response() else Radiance.cameraResponse()
    }

    fun run() = store.withFiles(project.id) { runWithFiles() }

    private fun runWithFiles() {
        store.requireSources(project.id)
        val started = System.nanoTime()
        var stageStart = started
        val timings = JSONObject()
        val hdrSteps = JSONObject()
        fun recordHdr(name: String, start: Long) {
            hdrSteps.put(name, hdrSteps.optDouble(name, 0.0) + (System.nanoTime() - start) / 1e9)
        }
        fun finishStage(name: String) {
            val now = System.nanoTime()
            timings.put(name, (now - stageStart) / 1e9)
            stageStart = now
        }
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
        finishStage("calibration")
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
                val hdr = File(cache, "${capture.targetId}.f32")
                val preview = File(cache, "${capture.targetId}.jpg")
                val stamp = File(cache, "${capture.targetId}.stamp")
                val key = "v11-$maxEdge-$responseHash-${capture.exposures}"
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
                var substage = System.nanoTime()
                val raw = load(capture)
                recordHdr("decode", substage)
                substage = System.nanoTime()
                val aligned =
                    try {
                        align(raw)
                    } catch (e: Exception) {
                        raw.forEach { it.release() }
                        throw e
                    }
                recordHdr("align", substage)
                substage = System.nanoTime()
                try {
                    val result =
                        NativeRadiance.merge(
                            aligned,
                            capture.exposures.map { it.seconds }.toDoubleArray(),
                            curve,
                        )
                    recordHdr("merge", substage)
                    substage = System.nanoTime()
                    val rows = aligned[0].rows()
                    val cols = aligned[0].cols()
                    val pixels = rows * cols
                    if (result.clipped > pixels * .003)
                        warnings +=
                            "Direction ${capture.targetId+1}: bright sources exceed the shortest exposure; their radiance is a lower bound."
                    if (result.moving > pixels * .1)
                        warnings +=
                            "Direction ${capture.targetId+1}: movement was detected within the bracket; inspect for ghosting."
                    run {
                        val temp = File(cache, "${capture.targetId}.tmp.f32")
                        FloatImages.write(temp, cols, rows, result.rgb)
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
                    }
                    File(cache, "${capture.targetId}.hdr").delete()
                    recordHdr("checkpoint", substage)
                    Prepared(capture, capture.lens.scaled(cols, rows), hdr, preview)
                } finally {
                    raw.forEach { it.release() }
                    aligned.forEach { it.release() }
                }
            }
        finishStage("hdrMerge")
        val registration =
            Registration.analyze(
                frames,
                { stage, p -> progress(stage, .35 + .15 * p) },
                checkCancelled,
            )
        warnings += registration.warnings
        finishStage("registration")
        progress("Checking full sphere coverage", .51)
        val seams =
            SphericalBlend.seams(
                frames,
                { stage, p -> progress(stage, .51 + .09 * p) },
                checkCancelled,
                project.groundMode.minPitch,
            )
        finishStage("seams")
        val measured =
            seams.labels.indices.filter { i ->
                Sphere.ray(
                        (i % seams.width) + .5,
                        (i / seams.width) + .5,
                        seams.width,
                        seams.height,
                    )
                    .y >= sin(Math.toRadians(project.groundMode.minPitch))
            }
        val missing = measured.count { seams.labels[it] < 0 }.toDouble() / measured.size
        if (missing > .002) {
            if (!project.sample) {
                val extra =
                    seams.uncovered.mapIndexed { i, v ->
                        val uv = Sphere.uv(v)
                        Target(
                            (project.targets.maxOfOrNull { it.id } ?: -1) + 1 + i,
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
        val tempExr = File(dir, "environment.partial.exr")
        try {
            val render =
                SphericalBlend.render(
                    frames,
                    seams,
                    project.quality.outputWidth,
                    tempHdr,
                    tempJpg,
                    { stage, p -> progress(stage, .61 + .36 * p) },
                    checkCancelled,
                )
            finishStage("render")
            if (project.groundMode == GroundMode.FILL) {
                GroundFill.rewrite(
                    tempHdr,
                    tempJpg,
                    seams.exposure,
                    { p ->
                        progress(
                            "Filling the ground · approximate colour and texture",
                            .97 + .009 * p,
                        )
                    },
                    checkCancelled,
                )
                finishStage("groundFill")
            }
            progress("Writing export metadata", .98)
            checkCancelled()
            addPhotoSphereMetadata(
                tempJpg,
                project.quality.outputWidth,
                project.quality.outputWidth / 2,
            )
            val master =
                if (project.masterFormat == app.hdri.data.MasterFormat.EXR) {
                    EnvironmentIO.convert(
                        tempHdr,
                        tempExr,
                        { p ->
                            progress("Compressing and verifying the HDR master", .98 + .009 * p)
                        },
                        checkCancelled,
                        store.groundNote(project),
                    )
                    tempExr
                } else tempHdr
            check(
                master.renameTo(store.masterFile(project)) &&
                    tempJpg.renameTo(File(dir, "preview.jpg"))
            ) {
                "Could not finalize HDR exports. Free storage and retry."
            }
            File(dir, "report.json")
                .writeText(
                    JSONObject()
                        .put("pipelineVersion", 3)
                        .put("masterFormat", project.masterFormat.name)
                        .put("groundMode", project.groundMode.name)
                        .put(
                            "syntheticGroundBelowPitch",
                            if (project.groundMode == GroundMode.FILL) project.groundMode.minPitch
                            else JSONObject.NULL,
                        )
                        .put("previewExposure", seams.exposure)
                        .put(
                            "cameraResponse",
                            if (project.sample) "inverse sRGB (sample)"
                            else "inverse fixed Camera2 contrast curve",
                        )
                        .put("stageSeconds", timings)
                        .put(
                            "photometry",
                            seams.photometry
                                .json()
                                .put(
                                    "viewGainBgrEv",
                                    JSONArray(frames.map { f -> f.logGain.map { it / ln(2.0) } }),
                                ),
                        )
                        .put("hdrStepSeconds", hdrSteps)
                        .put(
                            "render",
                            JSONObject()
                                .put("hdrDecodes", render.decodes)
                                .put("cachePeakBytes", render.cachePeakBytes),
                        )
                        .put(
                            "registration",
                            JSONObject()
                                .put("focalScale", registration.focalScale)
                                .put("localMatches", registration.localMatches)
                                .put("pairs", registration.pairs)
                                .put("matches", registration.observations)
                                .put("isolatedDirections", registration.isolated)
                                .put("medianBeforeDegrees", registration.beforeDegrees)
                                .put("medianAfterDegrees", registration.afterDegrees)
                                .put("p90AfterDegrees", registration.p90Degrees)
                                .put("localMeshes", registration.meshes)
                                .put("meshBeforeDegrees", registration.meshBeforeDegrees)
                                .put("meshAfterDegrees", registration.meshAfterDegrees)
                                .put("meshP90Degrees", registration.meshP90Degrees),
                        )
                        .put("width", project.quality.outputWidth)
                        .put("height", project.quality.outputWidth / 2)
                        .put(
                            "radiance",
                            "relative linear RGB, D65; JPEG camera-response estimate, not absolute photometry",
                        )
                        .put("coverage", 1 - missing)
                        .put("durationSeconds", (System.nanoTime() - started) / 1e9)
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
                    stage = "Clearing processing files",
                    progress = .99,
                    warnings = warnings.distinct(),
                    error = null,
                )
            }
            // Outputs and completion state are committed before pruning. A cleanup
            // failure must not turn a finished panorama into a failed build.
            try {
                app.hdri.data.MasterFormat.entries
                    .filter { it != project.masterFormat }
                    .forEach {
                        val old = File(dir, it.filename)
                        check(!old.exists() || old.delete()) {
                            "An older master could not be removed."
                        }
                    }
                store.clearProcessingFiles(
                    project.id,
                    { progress("Clearing processing files", .99 + .009 * it) },
                )
            } catch (e: Exception) {
                warnings +=
                    "Temporary processing files remain. Clear them from Storage when convenient."
            }
            store.update(project.id) {
                it.copy(stage = "HDR sphere ready", progress = 1.0, warnings = warnings.distinct())
            }
            progress("HDR sphere ready", 1.0)
        } finally {
            tempHdr.delete()
            tempJpg.delete()
            tempExr.delete()
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
