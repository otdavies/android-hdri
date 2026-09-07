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
import app.hdri.core.LightingExposure
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*

@Composable
internal fun ViewerScreen(
    file: File,
    name: String,
    captureExposure: Float? = null,
    back: () -> Unit,
) {
    var view by remember { mutableStateOf<SphereViewer?>(null) }
    var environment by remember(file) { mutableStateOf<LightingEnvironment?>(null) }
    var ready by remember(file) { mutableStateOf(false) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("Reading HDR radiance") }
    var progress by remember { mutableFloatStateOf(0f) }
    var exposure by rememberSaveable(file.path) { mutableFloatStateOf(0f) }
    var probes by rememberSaveable(file.path) { mutableStateOf(true) }
    var linear by rememberSaveable(file.path) { mutableStateOf(false) }
    var reference by rememberSaveable(file.path) { mutableStateOf("Grey reference") }
    var backdrop by rememberSaveable(file.path) { mutableStateOf(false) }
    var zoom by rememberSaveable(file.path) { mutableFloatStateOf(1f) }
    var attempt by remember { mutableIntStateOf(0) }
    val cameraScale = LightingExposure.validCapture(captureExposure)
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
                Text("Lighting preview")
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
                                it.zoomChanged = { value -> zoom = value }
                                it.setViewZoom(zoom)
                            }
                        },
                        update = {
                            it.probes = probes
                            it.exposure = exposure
                            it.linear = linear
                            it.background = backdrop
                            it.exposureScale =
                                when (reference) {
                                    "Capture exposure" -> cameraScale
                                    "Scene 1×" -> light.sceneScale
                                    else -> null
                                }
                            if (it.zoomFactor != zoom) it.setViewZoom(zoom)
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
            Text(
                if (probes) "Drag to rotate the light" else "Drag to look around · pinch to zoom",
                color = Muted,
                fontSize = 12.sp,
            )
            if (!probes)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom", Modifier.weight(1f))
                    IconButton({ zoom = (zoom / 1.25f).coerceAtLeast(.5f) }, enabled = ready) {
                        Icon(Icons.Outlined.ZoomOut, "Zoom out")
                    }
                    Text(String.format(Locale.US, "%.1f×", zoom))
                    IconButton({ zoom = (zoom * 1.25f).coerceAtMost(4f) }, enabled = ready) {
                        Icon(Icons.Outlined.ZoomIn, "Zoom in")
                    }
                }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    reference == "Grey reference",
                    { reference = "Grey reference" },
                    label = { Text("Meter grey") },
                )
                FilterChip(
                    reference == "Scene 1×",
                    { reference = "Scene 1×" },
                    label = { Text("Scene 1×") },
                )
            }
            if (cameraScale != null)
                FilterChip(
                    reference == "Capture exposure",
                    { reference = "Capture exposure" },
                    label = { Text("Capture exposure") },
                )
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
                    Text("Smooth highlights", fontSize = 14.sp)
                    Text(
                        if (linear) "Reference sRGB · values above display white clip"
                        else "Soft highlight roll-off · preserves midtones and colour ratios",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                Switch(!linear, { linear = !it }, enabled = ready)
            }
            if (probes)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Environment background", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(backdrop, { backdrop = it }, enabled = ready)
                }
            Text(
                (when (reference) {
                    "Scene 1×" -> "Scene metered to middle grey."
                    "Capture exposure" -> "Median capture exposure."
                    else -> "Diffuse-light meter · −0.25 EV viewing adjustment."
                }) +
                    " Both spheres and the environment share one exposure. Grey is 18% reflectance. Display settings do not change the HDR export.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
