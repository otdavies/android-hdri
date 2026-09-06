package app.hdri

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hdri.data.*
import app.hdri.processing.ExrExport
import app.hdri.processing.ProcessingService
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Screen {
    HOME,
    SETUP,
    CAPTURE,
    DETAIL,
    VIEWER,
}

data class AppState(
    val projects: List<Project> = emptyList(),
    val selected: String? = null,
    val screen: Screen = Screen.HOME,
    val loading: Boolean = true,
    val error: String? = null,
    val operation: String? = null,
    val operationProgress: Float = 0f,
    val operationCancellable: Boolean = false,
    val storage: Map<String, CaptureStorage> = emptyMap(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(app)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var exportJob: Job? = null
    private var refreshJob: Job? = null
    private var lastStorageRead = 0L

    init {
        refresh()
        viewModelScope.launch { ProcessingService.status.collect { refresh() } }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val projects =
                            store.list().map { p ->
                                if (
                                    p.state == "processing" &&
                                        !ProcessingService.status.value.running
                                )
                                    store.update(p.id) {
                                        it.copy(
                                            state = "paused",
                                            stage = "Processing was interrupted · ready to resume",
                                        )
                                    }
                                else p
                            }
                        val now = System.currentTimeMillis()
                        val usage =
                            if (
                                !ProcessingService.status.value.running ||
                                    now - lastStorageRead > 3_000
                            ) {
                                lastStorageRead = now
                                projects.associate { it.id to store.storage(it) }
                            } else mutable.value.storage
                        currentCoroutineContext().ensureActive()
                        mutable.update {
                            it.copy(projects = projects, loading = false, storage = usage)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update { it.copy(error = e.message, loading = false) }
                    }
                }
            }
    }

    fun navigate(screen: Screen) {
        mutable.update { it.copy(screen = screen) }
    }

    fun open(id: String) {
        mutable.update { it.copy(selected = id, screen = Screen.DETAIL) }
        refresh()
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun error(message: String) {
        mutable.update { it.copy(error = message) }
    }

    fun create(quality: Quality, sample: Boolean = false) {
        viewModelScope.launch {
            mutable.update { it.copy(operation = "Creating capture", operationProgress = 0f) }
            try {
                val p = withContext(Dispatchers.IO) { store.create(quality, sample) }
                mutable.update {
                    it.copy(
                        selected = p.id,
                        screen = if (sample) Screen.DETAIL else Screen.CAPTURE,
                        projects = listOf(p) + it.projects,
                        operation = null,
                    )
                }
                if (sample) process(p.id)
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message, operation = null) }
            }
        }
    }

    fun process(id: String) {
        if (mutable.value.operation != null) return
        if (mutable.value.projects.firstOrNull { it.id == id }?.sourcesRemoved == true) {
            error("Source photos were removed. This capture cannot be rebuilt.")
            return
        }
        if (ProcessingService.status.value.running) {
            error("One sphere is already processing. Wait for it to finish or pause it first.")
            return
        }
        try {
            ProcessingService.start(getApplication(), id)
            open(id)
        } catch (e: Exception) {
            error("Could not start processing: ${e.message}")
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.update(id) {
                    it.copy(name = name.trim().take(80).ifBlank { "Untitled sphere" })
                }
            }
            refresh()
        }
    }

    fun reviewed(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.update(id) { it.copy(state = "ready") } }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.delete(id) }
                mutable.update { it.copy(screen = Screen.HOME, selected = null) }
            } catch (e: Exception) {
                error(e.message ?: "Could not delete capture.")
            }
            refresh()
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun clearStorage(id: String, sources: Boolean) {
        if (mutable.value.operation != null) return
        mutable.update {
            it.copy(
                operation = if (sources) "Removing source photos" else "Clearing processing files",
                operationProgress = 0f,
                operationCancellable = true,
            )
        }
        exportJob =
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        val progress = { value: Float ->
                            mutable.update { it.copy(operationProgress = value) }
                            Unit
                        }
                        if (sources)
                            store.withFiles(id) {
                                store.clearProcessingFiles(id, { progress(it * .25f) }) {
                                    context.ensureActive()
                                }
                                store.removeSources(id, { progress(.25f + it * .75f) }) {
                                    context.ensureActive()
                                }
                            }
                        else store.clearProcessingFiles(id, progress) { context.ensureActive() }
                    }
                } catch (_: CancellationException) {
                    if (sources)
                        error(
                            "Removal stopped. Any remaining source photos can be removed from Storage. The finished HDR and JPEG are kept."
                        )
                } catch (e: Exception) {
                    error(e.message ?: "Could not clear files. Retry from Storage.")
                } finally {
                    mutable.update { it.copy(operation = null, operationCancellable = false) }
                    refresh()
                }
            }
    }

    fun export(id: String, name: String, uri: Uri) {
        if (mutable.value.operation != null) return
        mutable.update {
            it.copy(
                operation = if (name.endsWith(".exr")) "Preparing OpenEXR" else "Saving $name",
                operationProgress = 0f,
                operationCancellable = true,
            )
        }
        exportJob =
            viewModelScope.launch {
                var temporary: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        store.withFiles(id) {
                            val dir = store.dir(id)
                            if (name.endsWith(".zip")) store.requireSources(id)
                            val source =
                                if (name.endsWith(".exr")) {
                                    val cache = getApplication<Application>().cacheDir
                                    // Recover abandoned exports from a previous process, without
                                    // touching a recent export or the persistent source capture.
                                    cache
                                        .listFiles()
                                        ?.filter {
                                            it.name.startsWith("sphere-export-") &&
                                                System.currentTimeMillis() - it.lastModified() >
                                                    86_400_000
                                        }
                                        ?.forEach { it.delete() }
                                    check(cache.usableSpace > 110_000_000) {
                                        "Free at least 110 MB to prepare the OpenEXR export."
                                    }
                                    File.createTempFile("sphere-export-", ".exr", cache).also { file
                                        ->
                                        temporary = file
                                        ExrExport.write(
                                            File(dir, "environment.hdr"),
                                            file,
                                            { value ->
                                                mutable.update {
                                                    it.copy(operationProgress = value * .8f)
                                                }
                                            },
                                            { context.ensureActive() },
                                        )
                                        mutable.update { it.copy(operation = "Saving OpenEXR") }
                                    }
                                } else File(dir, name)
                            val output =
                                getApplication<Application>()
                                    .contentResolver
                                    .openOutputStream(uri, "wt")
                                    ?: kotlin.error("Could not open the selected file.")
                            output.buffered().use { out ->
                                if (name.endsWith(".zip")) {
                                    val files =
                                        dir.walkTopDown()
                                            .onEnter { it.name != "processed" }
                                            .filter { it.isFile && !it.name.contains(".part") }
                                            .toList()
                                    val total = files.sumOf { it.length() }.coerceAtLeast(1)
                                    var copied = 0L
                                    ZipOutputStream(out).use { zip ->
                                        zip.setLevel(0)
                                        files.forEach { file ->
                                            context.ensureActive()
                                            zip.putNextEntry(ZipEntry(file.relativeTo(dir).path))
                                            file.inputStream().use { input ->
                                                val buffer = ByteArray(128 * 1024)
                                                while (true) {
                                                    context.ensureActive()
                                                    val n = input.read(buffer)
                                                    if (n < 0) break
                                                    zip.write(buffer, 0, n)
                                                    copied += n
                                                    mutable.update {
                                                        it.copy(
                                                            operationProgress =
                                                                copied.toFloat() / total
                                                        )
                                                    }
                                                }
                                            }
                                            zip.closeEntry()
                                        }
                                    }
                                } else {
                                    val total = source.length().coerceAtLeast(1)
                                    var copied = 0L
                                    source.inputStream().use { input ->
                                        val buffer = ByteArray(128 * 1024)
                                        while (true) {
                                            context.ensureActive()
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            out.write(buffer, 0, n)
                                            copied += n
                                            val fraction = copied.toFloat() / total
                                            mutable.update {
                                                it.copy(
                                                    operationProgress =
                                                        if (temporary != null) .8f + fraction * .2f
                                                        else fraction
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    removePartialExport(uri)
                } catch (e: Exception) {
                    removePartialExport(uri)
                    error(
                        "Could not save the export: ${e.message}. The original remains on your phone."
                    )
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                    mutable.update { it.copy(operation = null, operationCancellable = false) }
                }
            }
    }

    private suspend fun removePartialExport(uri: Uri) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(
                    getApplication<Application>().contentResolver,
                    uri,
                )
            }
        }
    }
}
