package app.hdri.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

@Composable
internal fun ViewerScreen(file: File, name: String, back: () -> Unit) {
    var view by remember { mutableStateOf<SphereViewer?>(null) }
    var environment by remember(file) { mutableStateOf<LightingEnvironment?>(null) }
    var ready by remember(file) { mutableStateOf(false) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("Reading HDR radiance") }
    var progress by remember { mutableFloatStateOf(0f) }
    var exposure by rememberSaveable(file.path) { mutableFloatStateOf(0f) }
    var probes by rememberSaveable(file.path) { mutableStateOf(true) }
    var linear by rememberSaveable(file.path) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(file, attempt) {
        failure = null
        ready = false
        try {
            val job = currentCoroutineContext()
            environment =
                withContext(Dispatchers.Default) {
                    LightingEnvironment.load(file) { label, value ->
                        job.ensureActive()
                        stage = label
                        progress = value
                    }
                }
            stage = "Drawing lighting spheres"
            progress = .98f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: "The HDR environment could not be opened."
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, view) {
        val current = view
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) current?.onPause()
            if (e == Lifecycle.Event.ON_RESUME) current?.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            current?.onPause()
        }
    }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Light check")
                Text(name, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton({
                view?.reset()
                exposure = 0f
            }) {
                Icon(Icons.Outlined.Refresh, "Reset view and exposure")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterChip(probes, { probes = true }, label = { Text("Lighting spheres") })
            FilterChip(!probes, { probes = false }, label = { Text("Explore HDR") })
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            environment?.let { light ->
                key(light, attempt) {
                    AndroidView(
                        factory = { context ->
                            SphereViewer(context, light, { ready = true }, { failure = it }).also {
                                view = it
                            }
                        },
                        update = {
                            it.probes = probes
                            it.exposure = exposure
                            it.linear = linear
                            it.requestRender()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!ready && failure == null)
                Column(
                    Modifier.fillMaxWidth().background(Ink).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stage)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(progress*100).toInt()}% · on your phone",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            if (failure != null)
                Column(Modifier.fillMaxWidth().background(Ink).padding(24.dp)) {
                    Text(failure!!)
                    TextButton({
                        environment = null
                        attempt++
                    }) {
                        Text("Retry lighting view")
                    }
                }
        }
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 310.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (probes)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Text("Chrome · ideal mirror", color = Muted, fontSize = 12.sp)
                    Text("Grey · 18% diffuse", color = Muted, fontSize = 12.sp)
                }
            Text("Drag to rotate the light", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Exposure", Modifier.weight(1f))
                IconButton({ exposure = (exposure - 1).coerceAtLeast(-8f) }, enabled = ready) {
                    Icon(Icons.Outlined.Remove, "Reduce exposure one stop")
                }
                Text(String.format(Locale.US, "%+.1f EV", exposure))
                IconButton({ exposure = (exposure + 1).coerceAtMost(8f) }, enabled = ready) {
                    Icon(Icons.Outlined.Add, "Increase exposure one stop")
                }
            }
            Slider(exposure, { exposure = it }, valueRange = -8f..8f, steps = 63, enabled = ready)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Linear display", fontSize = 14.sp)
                    Text(
                        if (linear) "sRGB · highlights clip at display white"
                        else "Reinhard · gentle highlight roll-off",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Switch(linear, { linear = it }, enabled = ready)
            }
            Text(
                "Preview only · HDR export stays unchanged. 0 EV normalizes mean light; values are relative, not a light-meter reading.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
