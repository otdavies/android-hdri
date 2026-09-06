package app.hdri

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hdri.data.*
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
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SessionStore(app)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var exportJob: Job? = null

    init {
        refresh()
        viewModelScope.launch { ProcessingService.status.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val projects =
                        store.list().map { p ->
                            if (p.state == "processing" && !ProcessingService.status.value.running)
                                store.update(p.id) {
                                    it.copy(
                                        state = "paused",
                                        stage = "Processing was interrupted · ready to resume",
                                    )
                                }
                            else p
                        }
                    mutable.update { it.copy(projects = projects, loading = false) }
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
            withContext(Dispatchers.IO) { store.delete(id) }
            mutable.update { it.copy(screen = Screen.HOME, selected = null) }
            refresh()
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun export(id: String, name: String, uri: Uri) {
        exportJob =
            viewModelScope.launch {
                mutable.update { it.copy(operation = "Saving $name", operationProgress = 0f) }
                try {
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        val dir = store.dir(id)
                        val output =
                            getApplication<Application>()
                                .contentResolver
                                .openOutputStream(uri, "wt")
                                ?: kotlin.error("Could not open the selected file.")
                        output.buffered().use { out ->
                            if (name.endsWith(".zip")) {
                                val files =
                                    dir.walkTopDown()
                                        .filter {
                                            it.isFile &&
                                                !it.name.contains(".part") &&
                                                it.parentFile?.name != "processed"
                                        }
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
                                                        operationProgress = copied.toFloat() / total
                                                    )
                                                }
                                            }
                                        }
                                        zip.closeEntry()
                                    }
                                }
                            } else {
                                val file = File(dir, name)
                                val total = file.length().coerceAtLeast(1)
                                var copied = 0L
                                file.inputStream().use { input ->
                                    val buffer = ByteArray(128 * 1024)
                                    while (true) {
                                        context.ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        copied += n
                                        mutable.update {
                                            it.copy(operationProgress = copied.toFloat() / total)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            android.provider.DocumentsContract.deleteDocument(
                                getApplication<Application>().contentResolver,
                                uri,
                            )
                        }
                    }
                } catch (e: Exception) {
                    mutable.update {
                        it.copy(
                            error =
                                "Could not save the export: ${e.message}. The original remains on your phone."
                        )
                    }
                } finally {
                    mutable.update { it.copy(operation = null) }
                }
            }
    }
}
