package app.hdri.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hdri.core.ReflectionThumbnail
import app.hdri.data.Project
import app.hdri.data.SessionStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Regenerable cache, capped at 8 MiB. Neither source bundles nor HDR masters are duplicated. */
internal object ScanThumbnails {
    private val mutex = Mutex()

    suspend fun load(
        context: Context,
        store: SessionStore,
        project: Project,
        progress: (Float) -> Unit,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val job = currentCoroutineContext()
                store.withFiles(project.id) {
                    val master = store.masterFile(project)
                    check(master.isFile && master.length() > 0) { "HDR master is unavailable." }
                    val stamp =
                        "${ReflectionThumbnail.VERSION}:${master.name}:${master.length()}:${master.lastModified()}"
                    val hash =
                        MessageDigest.getInstance("SHA-256")
                            .digest(stamp.toByteArray())
                            .take(12)
                            .joinToString("") { "%02x".format(it) }
                    val cache = File(context.cacheDir, "sphere-thumbnails").apply { mkdirs() }
                    val output = File(cache, "${project.id}-$hash.png")
                    val saved = if (output.isFile) BitmapFactory.decodeFile(output.path) else null
                    if (
                        saved != null &&
                            saved.width == ReflectionThumbnail.SIZE &&
                            saved.height == ReflectionThumbnail.SIZE
                    ) {
                        output.setLastModified(System.currentTimeMillis())
                        progress(1f)
                        saved
                    } else {
                        saved?.recycle()
                        val map =
                            ReflectionThumbnail.load(
                                master,
                                { progress(.6f * it) },
                                { job.ensureActive() },
                            )
                        val pixels =
                            ReflectionThumbnail.render(
                                map,
                                progress = { progress(.6f + .38f * it) },
                                check = { job.ensureActive() },
                            )
                        val bitmap =
                            Bitmap.createBitmap(
                                pixels,
                                ReflectionThumbnail.SIZE,
                                ReflectionThumbnail.SIZE,
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            job.ensureActive()
                            val atomic = AtomicFile(output)
                            val stream = atomic.startWrite()
                            try {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                                atomic.finishWrite(stream)
                            } catch (e: Throwable) {
                                atomic.failWrite(stream)
                                throw e
                            }
                            cache
                                .listFiles()
                                ?.filter { it.name.startsWith("${project.id}-") && it != output }
                                ?.forEach { it.delete() }
                            var bytes = 0L
                            cache
                                .listFiles()
                                ?.filter { it.isFile }
                                ?.sortedByDescending { it.lastModified() }
                                ?.forEach { file ->
                                    bytes += file.length()
                                    if (bytes > 8 * 1024 * 1024 && file != output) file.delete()
                                }
                            progress(1f)
                            bitmap
                        } catch (e: Throwable) {
                            bitmap.recycle()
                            throw e
                        }
                    }
                }
            }
        }
}

@Composable
internal fun ScanThumbnail(project: Project, store: SessionStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var retry by remember(project.id) { mutableIntStateOf(0) }
    var progress by
        remember(project.id, project.state, project.masterFormat, retry) { mutableFloatStateOf(0f) }
    var failed by
        remember(project.id, project.state, project.masterFormat, retry) { mutableStateOf(false) }
    val finished = project.state == "ready" || project.state == "review"
    val bitmap by
        produceState<Bitmap?>(null, project.id, project.state, project.masterFormat, retry) {
            value = null
            if (finished)
                try {
                    value = ScanThumbnails.load(context, store, project) { progress = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null ->
                Image(
                    image.asImageBitmap(),
                    "Chrome preview of ${project.name}",
                    Modifier.fillMaxSize(),
                )
            !finished ->
                SphereArt(
                    Modifier.fillMaxSize(),
                    if (project.state == "processing") project.progress.toFloat() else 0f,
                )
            failed ->
                IconButton({ retry++ }) {
                    Icon(
                        Icons.Outlined.Refresh,
                        "Thumbnail unavailable. Retry preview for ${project.name}",
                        tint = Muted,
                    )
                }
            else ->
                CircularProgressIndicator(
                    progress = { progress },
                    modifier =
                        Modifier.size(32.dp).semantics {
                            contentDescription = "Rendering chrome preview of ${project.name}"
                        },
                    strokeWidth = 2.dp,
                )
        }
    }
}
