package app.hdri.data

import android.content.Context
import android.util.AtomicFile
import app.hdri.core.*
import app.hdri.core.Target
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONArray
import org.json.JSONObject

enum class Quality(val outputWidth: Int, val bracketCount: Int, val label: String) {
    QUICK(2048, 3, "2K · 3 exposures"),
    DETAIL(4096, 5, "4K · 5 exposures"),
}

enum class MasterFormat(val filename: String, val label: String) {
    EXR("environment.exr", "OpenEXR · compressed"),
    HDR("environment.hdr", "Radiance HDR"),
}

enum class GroundMode(val label: String, val minPitch: Double) {
    CAPTURE("Capture the ground", -90.0),
    FILL("Fill below me", -55.0),
}

enum class CoverageDensity(val label: String, val margin: Double) {
    COMPACT("Compact", 6.75),
    STANDARD("More overlap", 10.0),
    EXTRA("Most overlap", 13.0),
}

data class Exposure(val file: String, val timeNs: Long, val iso: Int, val timestamp: Long) {
    val seconds
        get() = timeNs / 1e9 * iso / 100.0
}

data class Capture(
    val targetId: Int,
    val rotation: Q,
    val position: V3,
    val lens: Lens,
    val exposures: List<Exposure>,
    val warnings: List<String> = emptyList(),
    val poseSource: String = "arcore",
)

data class Project(
    val id: String,
    val created: Long,
    val name: String,
    val quality: Quality,
    val targets: List<Target> = emptyList(),
    val captures: List<Capture> = emptyList(),
    val state: String = "capture",
    val stage: String = "Ready to capture",
    val progress: Double = 0.0,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val sample: Boolean = false,
    val coverageVersion: Int = 0,
    val sourcesRemoved: Boolean = false,
    val masterFormat: MasterFormat = MasterFormat.HDR,
    val groundMode: GroundMode = GroundMode.CAPTURE,
    val cameraKey: String = "main",
    val density: CoverageDensity = CoverageDensity.COMPACT,
)

data class CaptureStorage(
    val sources: Long,
    val processing: Long,
    val other: Long,
    val environment: Long = 0,
    val preview: Long = 0,
) {
    val total
        get() = sources + processing + other
}

/**
 * Every update re-reads the latest manifest under one process-wide lock. Completed brackets are
 * atomic.
 */
class SessionStore(context: Context) {
    val root = File(context.filesDir, "sessions").apply { mkdirs() }

    fun dir(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, id).apply { mkdirs() }
    }

    fun create(
        quality: Quality,
        sample: Boolean = false,
        masterFormat: MasterFormat = MasterFormat.EXR,
        groundMode: GroundMode = GroundMode.CAPTURE,
        cameraKey: String = "main",
        density: CoverageDensity = CoverageDensity.COMPACT,
    ): Project =
        synchronized(lock) {
            val p =
                Project(
                    UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    if (sample) "Sample photosphere" else "Untitled sphere",
                    quality,
                    sample = sample,
                    masterFormat = masterFormat,
                    groundMode = groundMode,
                    cameraKey = cameraKey,
                    density = density,
                )
            save(p)
            p
        }

    fun list(): List<Project> =
        synchronized(lock) {
            root
                .listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { runCatching { read(it.name) }.getOrNull() }
                ?.sortedByDescending { it.created } ?: emptyList()
        }

    fun read(id: String): Project =
        synchronized(lock) {
            decode(
                JSONObject(
                    AtomicFile(File(dir(id), "session.json")).openRead().bufferedReader().use {
                        it.readText()
                    }
                )
            )
        }

    fun update(id: String, change: (Project) -> Project): Project =
        synchronized(lock) { change(read(id)).also { save(it) } }

    fun save(p: Project) =
        synchronized(lock) {
            val a = AtomicFile(File(dir(p.id), "session.json"))
            val stream = a.startWrite()
            try {
                stream.write(encode(p).toString(2).toByteArray())
                a.finishWrite(stream)
            } catch (e: Exception) {
                a.failWrite(stream)
                throw e
            }
        }

    // File operations use a separate, reentrant lease. A manifest lock alone cannot
    // protect a multi-minute build or export from cleanup on another thread.
    fun <T> withFiles(id: String, action: () -> T): T {
        val lease = fileLocks.getOrPut(id) { ReentrantLock() }
        check(lease.tryLock()) {
            "This capture is in use. Wait for its current operation to finish."
        }
        try {
            return action()
        } finally {
            lease.unlock()
        }
    }

    fun delete(id: String) =
        withFiles(id) {
            check(read(id).state != "processing") {
                "Pause processing before deleting this capture."
            }
            check(dir(id).deleteRecursively()) {
                "Some files could not be deleted. Retry deleting this capture."
            }
        }

    private fun sourceFiles(p: Project): List<File> =
        p.captures
            .flatMap { it.exposures }
            .map {
                // Only declared, ordinary JPEG basenames may be removed. Never follow a
                // malformed manifest into an export, another capture, or a symlink target.
                require(
                    it.file.matches(Regex("[A-Za-z0-9_-]+\\.jpg")) && it.file != "preview.jpg"
                ) {
                    "Invalid source photo filename. No source photos were removed."
                }
                File(dir(p.id), it.file).also { f ->
                    require(!Files.isSymbolicLink(f.toPath())) { "Invalid source photo path." }
                }
            }
            .distinct()

    fun requireSources(id: String) {
        val p = read(id)
        check(!p.sourcesRemoved) {
            "Source photos were removed. The finished HDR and JPEG remain available, but this capture cannot be rebuilt."
        }
        check(sourceFiles(p).all { it.isFile && it.length() > 0 }) {
            "Some source photos are missing. This capture cannot be rebuilt or exported as an original bundle."
        }
    }

    fun storage(p: Project): CaptureStorage {
        val sourceNames = p.captures.flatMap { it.exposures }.map { it.file }.toSet()
        val directory = dir(p.id)
        var sources = 0L
        var processing = 0L
        var other = 0L
        directory
            .walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .forEach { f ->
                if (f.isFile && !Files.isSymbolicLink(f.toPath())) {
                    val name = f.relativeTo(directory).invariantSeparatorsPath
                    when {
                        name in sourceNames -> sources += f.length()
                        name.startsWith("processed/") || recoverable(p, name) ->
                            processing += f.length()
                        else -> other += f.length()
                    }
                }
            }
        return CaptureStorage(
            sources,
            processing,
            other,
            masterFile(p).length(),
            File(directory, "preview.jpg").length(),
        )
    }

    /** Commit a verified replacement before changing the manifest or removing the old master. */
    fun replaceMaster(
        id: String,
        format: MasterFormat,
        convert: (File, File) -> Unit,
        checkCancelled: () -> Unit = {},
    ) =
        withFiles(id) {
            val p = read(id)
            check(p.state in listOf("ready", "review")) { "Finish processing first." }
            if (p.masterFormat == format) return@withFiles
            val original = masterFile(p)
            val temp =
                File(dir(id), "environment.partial.${format.filename.substringAfterLast('.')}")
            val destination = File(dir(id), format.filename)
            require(
                !Files.isSymbolicLink(original.toPath()) &&
                    !Files.isSymbolicLink(temp.toPath()) &&
                    !Files.isSymbolicLink(destination.toPath())
            )
            checkSpace(
                id,
                p.quality.outputWidth.toLong() * p.quality.outputWidth / 2 * 13 + 2_000_000,
            )
            try {
                convert(original, temp)
                checkCancelled()
                check(temp.length() > 0 && temp.renameTo(destination)) {
                    "Could not commit the converted master. Original retained."
                }
                update(id) { it.copy(masterFormat = format) }
                check(!original.exists() || original.delete()) {
                    "Conversion succeeded; clear processing files to remove the old master."
                }
            } finally {
                temp.delete()
            }
        }

    fun groundNote(p: Project): String =
        if (p.groundMode == GroundMode.FILL)
            "Ground below -55 degrees is synthesized from nearby colour and texture; not measured scene detail."
        else ""

    fun masterFile(p: Project): File = File(dir(p.id), p.masterFormat.filename)

    fun bundleFiles(p: Project): List<File> =
        (sourceFiles(p) +
                listOf("session.json", "report.json", "preview.jpg", p.masterFormat.filename).map {
                    File(dir(p.id), it)
                })
            .filter { it.isFile }
            .onEach { require(!Files.isSymbolicLink(it.toPath())) }

    private fun recoverable(p: Project, name: String): Boolean {
        val declared = p.captures.flatMap { it.exposures }.any { it.file == name }
        return !declared &&
            (name.matches(Regex("[0-9]+-[0-9]{8,}\\.jpg(?:\\.part)?")) ||
                name in
                    listOf(
                        "environment.partial.hdr",
                        "environment.partial.exr",
                        "preview.partial.jpg",
                    ) ||
                (p.state in listOf("ready", "review") &&
                    name != p.masterFormat.filename &&
                    MasterFormat.entries.any { it.filename == name } &&
                    masterFile(p).length() > 0))
    }

    fun clearProcessingFiles(
        id: String,
        progress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ) =
        withFiles(id) {
            check(read(id).state in listOf("ready", "review", "paused", "failed")) {
                "Finish or pause processing before clearing its files."
            }
            val cache = File(dir(id), "processed")
            require(!Files.isSymbolicLink(cache.toPath())) { "Invalid processing directory." }
            val p = read(id)
            val files =
                cache
                    .walkTopDown()
                    .onEnter { !Files.isSymbolicLink(it.toPath()) }
                    .filter { it.isFile }
                    .toList() +
                    (dir(id).listFiles()?.filter {
                        it.isFile && !Files.isSymbolicLink(it.toPath()) && recoverable(p, it.name)
                    } ?: emptyList())
            removeFiles(files, progress, checkCancelled)
            cache
                .walkBottomUp()
                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                .forEach { it.delete() }
        }

    fun removeSources(id: String, progress: (Float) -> Unit = {}, checkCancelled: () -> Unit = {}) =
        withFiles(id) {
            val p = read(id)
            check(p.state in listOf("ready", "review")) {
                "Finish processing before removing source photos."
            }
            check(
                listOf(p.masterFormat.filename, "preview.jpg").all {
                    File(dir(id), it).let { f -> f.isFile && f.length() > 0 }
                }
            ) {
                "Keep source photos until both finished exports are saved successfully."
            }
            val files =
                sourceFiles(p) // Validate every name BEFORE persisting intent or deleting anything.
            checkCancelled()
            // Commit intent first: a crash or cancellation can leave photos behind, but
            // must never make an incomplete source set appear safe to rebuild/export.
            update(id) { it.copy(sourcesRemoved = true) }
            removeFiles(files, progress, checkCancelled)
        }

    private fun removeFiles(
        files: List<File>,
        progress: (Float) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        progress(0f)
        files.forEachIndexed { index, f ->
            checkCancelled()
            check(!f.exists() || (f.isFile && f.delete())) {
                "Could not remove ${f.name}. Retry to remove the remaining files."
            }
            progress((index + 1f) / files.size)
        }
        progress(1f)
    }

    fun checkSpace(id: String, bytes: Long) {
        check(dir(id).usableSpace > bytes) {
            "Free up at least ${bytes/1_000_000} MB of phone storage, then retry. Your captures are saved."
        }
    }

    companion object {
        private val lock = Any()
        private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

        private fun arr(values: List<*>) = JSONArray(values)

        private fun vector(v: V3) = arr(listOf(v.x, v.y, v.z))

        private fun quat(q: Q) = arr(listOf(q.x, q.y, q.z, q.w))

        private fun JSONArray.v() = V3(getDouble(0), getDouble(1), getDouble(2))

        private fun JSONArray.q() =
            Q(getDouble(0), getDouble(1), getDouble(2), getDouble(3)).normalized()

        private fun <T> JSONArray.mapItems(f: (JSONObject) -> T) =
            (0 until length()).map { f(getJSONObject(it)) }

        fun encode(p: Project): JSONObject =
            JSONObject()
                .put("schema", 1)
                .put("id", p.id)
                .put("created", p.created)
                .put("name", p.name)
                .put("quality", p.quality.name)
                .put("masterFormat", p.masterFormat.name)
                .put("groundMode", p.groundMode.name)
                .put("cameraKey", p.cameraKey)
                .put("density", p.density.name)
                .put("state", p.state)
                .put("stage", p.stage)
                .put("progress", p.progress)
                .put("sample", p.sample)
                .put("coverageVersion", p.coverageVersion)
                .put("sourcesRemoved", p.sourcesRemoved)
                .put("error", p.error ?: JSONObject.NULL)
                .put("warnings", arr(p.warnings))
                .put(
                    "targets",
                    arr(
                        p.targets.map {
                            JSONObject().put("id", it.id).put("yaw", it.yaw).put("pitch", it.pitch)
                        }
                    ),
                )
                .put(
                    "captures",
                    arr(
                        p.captures.map { c ->
                            JSONObject()
                                .put("target", c.targetId)
                                .put("rotation", quat(c.rotation))
                                .put("position", vector(c.position))
                                .put("poseSource", c.poseSource)
                                .put("warnings", arr(c.warnings))
                                .put(
                                    "lens",
                                    arr(
                                        listOf(
                                            c.lens.width,
                                            c.lens.height,
                                            c.lens.fx,
                                            c.lens.fy,
                                            c.lens.cx,
                                            c.lens.cy,
                                        )
                                    ),
                                )
                                .put(
                                    "exposures",
                                    arr(
                                        c.exposures.map {
                                            JSONObject()
                                                .put("file", it.file)
                                                .put("timeNs", it.timeNs)
                                                .put("iso", it.iso)
                                                .put("timestamp", it.timestamp)
                                        }
                                    ),
                                )
                        }
                    ),
                )

        fun decode(j: JSONObject): Project {
            require(j.getInt("schema") == 1) {
                "This capture uses a newer format. Install the latest sphere APK."
            }
            return Project(
                j.getString("id"),
                j.getLong("created"),
                j.getString("name"),
                Quality.valueOf(j.getString("quality")),
                j.getJSONArray("targets").mapItems {
                    Target(it.getInt("id"), it.getDouble("yaw"), it.getDouble("pitch"))
                },
                j.getJSONArray("captures").mapItems { c ->
                    val l = c.getJSONArray("lens")
                    Capture(
                        c.getInt("target"),
                        c.getJSONArray("rotation").q(),
                        c.getJSONArray("position").v(),
                        Lens(
                            l.getInt(0),
                            l.getInt(1),
                            l.getDouble(2),
                            l.getDouble(3),
                            l.getDouble(4),
                            l.getDouble(5),
                        ),
                        c.getJSONArray("exposures").mapItems {
                            Exposure(
                                it.getString("file"),
                                it.getLong("timeNs"),
                                it.getInt("iso"),
                                it.getLong("timestamp"),
                            )
                        },
                        c.optJSONArray("warnings")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        } ?: emptyList(),
                        c.optString("poseSource", "arcore"),
                    )
                },
                j.getString("state"),
                j.getString("stage"),
                j.getDouble("progress"),
                j.getJSONArray("warnings").let { a ->
                    (0 until a.length()).map { a.getString(it) }
                },
                if (j.isNull("error")) null else j.getString("error"),
                j.optBoolean("sample"),
                j.optInt("coverageVersion", 0),
                j.optBoolean("sourcesRemoved", false),
                MasterFormat.valueOf(j.optString("masterFormat", "HDR")),
                GroundMode.valueOf(j.optString("groundMode", "CAPTURE")),
                j.optString("cameraKey", "main"),
                CoverageDensity.valueOf(j.optString("density", "COMPACT")),
            )
        }
    }
}
