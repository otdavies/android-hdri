package app.hdri

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hdri.capture.*
import app.hdri.data.*
import app.hdri.processing.ProcessingService
import app.hdri.ui.*
import com.google.ar.core.ArCoreApk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            LumaTheme {
                val vm: AppViewModel = viewModel()
                LumaApp(vm)
            }
        }
    }
}

@Composable
private fun LumaApp(vm: AppViewModel) {
    val app by vm.state.collectAsStateWithLifecycle()
    val processing by ProcessingService.status.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as ComponentActivity
    var quality by remember { mutableStateOf(Quality.DETAIL) }
    var resume by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            pendingExport?.let { (id, name) -> if (uri != null) vm.export(id, name, uri) }
            pendingExport = null
        }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun launchCapture() {
        try {
            if (
                ArCoreApk.getInstance().requestInstall(activity, true) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED
            ) {
                vm.error(
                    "Finish installing Google Play Services for AR, then tap Start capture again."
                )
                return
            }
            if (resume) vm.navigate(Screen.CAPTURE) else vm.create(quality)
        } catch (e: Exception) {
            vm.error(
                "Camera services could not start: ${e.message}. Check that Google Play Services for AR is installed."
            )
        }
    }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCapture()
            else
                vm.error(
                    "Camera permission is needed to capture a photosphere. Enable it in Android Settings → Apps → Luma Sphere → Permissions."
                )
        }
    fun startCapture(isResume: Boolean) {
        resume = isResume
        if (
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
            launchCapture()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    fun process(id: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.process(id)
    }
    fun export(p: Project, name: String) {
        pendingExport = p.id to name
        save.launch(
            if (name.endsWith(".zip")) "luma-capture-${p.id.take(8)}.zip"
            else "luma-${p.id.take(8)}-${name}"
        )
    }
    val project = app.projects.firstOrNull { it.id == app.selected }
    BackHandler(app.screen != Screen.HOME) {
        vm.navigate(
            if (app.screen == Screen.VIEWER || app.screen == Screen.CAPTURE) Screen.DETAIL
            else Screen.HOME
        )
        vm.refresh()
    }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        when (app.screen) {
            Screen.HOME ->
                Home(
                    app,
                    processing.running,
                    { vm.navigate(Screen.SETUP) },
                    vm::open,
                    { vm.create(Quality.QUICK, true) },
                )
            Screen.SETUP ->
                Setup(
                    quality,
                    { quality = it },
                    { vm.navigate(Screen.HOME) },
                    { startCapture(false) },
                    processing.running,
                )
            Screen.CAPTURE ->
                if (project != null)
                    CaptureScreen(
                        project,
                        vm.store,
                        { vm.open(project.id) },
                        { process(project.id) },
                    )
            Screen.DETAIL ->
                if (project != null)
                    Details(
                        project,
                        processing.running && processing.id == project.id,
                        vm,
                        { vm.navigate(Screen.HOME) },
                        { startCapture(true) },
                        { process(project.id) },
                        { name -> export(project, name) },
                    )
            Screen.VIEWER ->
                if (project != null)
                    ViewerScreen(File(vm.store.dir(project.id), "preview.jpg")) {
                        vm.navigate(Screen.DETAIL)
                    }
        }
        if (app.loading)
            Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Reading your captures…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
    }
    if (app.error != null)
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("A little attention needed") },
            text = { Text(app.error!!) },
            confirmButton = { TextButton(vm::clearError) { Text("Got it") } },
        )
    if (app.operation != null)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(app.operation!!) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { app.operationProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(app.operationProgress*100).roundToInt()}%")
                }
            },
            confirmButton = { TextButton(vm::cancelExport) { Text("Cancel") } },
        )
}

@Composable
private fun Header(
    title: String,
    back: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        else
            Icon(
                Icons.Outlined.Public,
                null,
                Modifier.padding(end = 10.dp).size(24.dp),
                tint = Lime,
            )
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        trailing?.invoke()
    }
}

@Composable
private fun Home(
    app: AppState,
    busy: Boolean,
    new: () -> Unit,
    open: (String) -> Unit,
    sample: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Header(
                "Luma Sphere",
                trailing = {
                    Text("PREVIEW", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("CAPTURE THE LIGHT AROUND YOU")
                Text(
                    "One place.\nEvery direction.",
                    fontSize = 42.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1.5).sp,
                )
                Text(
                    "Turn a moment into a 360° HDR environment. Captured and stitched on your phone.",
                    color = Muted,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().height(258.dp).background(Panel, RoundedCornerShape(28.dp))
            ) {
                SphereArt(Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Eyebrow("360° × 180°")
                    Text("LOCAL CAPTURE", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryAction("New photosphere", new, enabled = !busy)
                TextButton(sample, Modifier.fillMaxWidth(), enabled = !busy) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Explore a sample capture")
                }
                if (busy)
                    Text(
                        "A sphere is processing. Open it below to see progress.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Your captures", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text("${app.projects.size}", color = Muted)
            }
        }
        if (app.projects.isEmpty())
            item {
                Text(
                    "Your first light study starts here.\nNo account. No uploads.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        items(app.projects, key = { it.id }) { p -> CaptureRow(p) { open(p.id) } }
    }
}

@Composable
private fun CaptureRow(p: Project, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .clickable(onClick = open)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SphereArt(Modifier.size(64.dp), if (p.state == "processing") p.progress.toFloat() else 0f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                when (p.state) {
                    "ready" -> "Ready · ${p.quality.label}"
                    "review" -> "Ready for quality review"
                    "processing" -> "${(p.progress*100).roundToInt()}% · ${p.stage}"
                    "paused" -> "Paused · resume processing"
                    "failed" -> "Needs attention"
                    else -> "${p.captures.size} directions saved"
                },
                color = if (p.state == "failed") Amber else Muted,
                fontSize = 12.sp,
                maxLines = 2,
            )
            if (p.state == "processing")
                LinearProgressIndicator(
                    progress = { p.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            "Open capture",
            Modifier.size(20.dp),
            tint = Muted,
        )
    }
}

@Composable
private fun Setup(
    quality: Quality,
    select: (Quality) -> Unit,
    back: () -> Unit,
    start: () -> Unit,
    busy: Boolean,
) {
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header("New photosphere", back)
        Eyebrow("A LITTLE STILLNESS GOES A LONG WAY")
        Text(
            "Keep the lens\nin one place.",
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Medium,
        )
        InfoCard(
            "01  Pivot, don’t walk",
            "Rotate the phone around its main camera. Keep nearby objects at least a metre away to reduce double edges.",
        )
        InfoCard(
            "02  Meet each dot",
            "Hold the phone upright, then follow the dots. Gyro guidance works on blank sky. Each stop automatically captures an HDR bracket.",
        )
        InfoCard(
            "03  Let the sphere develop",
            "Stitching stays on your phone. Every stage has visible progress, and you can pause it and return later.",
        )
        Text("Capture quality", fontWeight = FontWeight.Medium)
        Quality.entries.forEach { q ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (q == quality) Lime.copy(alpha = .1f) else Panel)
                    .clickable { select(q) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(q == quality, { select(q) })
                Column(Modifier.padding(start = 8.dp)) {
                    Text(
                        if (q == Quality.DETAIL) "Detailed environment" else "Quick light study",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(q.label, color = Muted, fontSize = 13.sp)
                }
            }
        }
        Text(
            "Use a still scene and leave at least 1 GB free. HDR exports contain relative lighting values. Camera preview needs Google Play Services for AR installed. Gyro guidance and stitching work offline.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        PrimaryAction(
            "Start capture",
            start,
            enabled = !busy,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun CaptureScreen(
    p: Project,
    store: SessionStore,
    leave: () -> Unit,
    complete: () -> Unit,
) {
    val activity = LocalActivity.current as ComponentActivity
    val engine = remember(p.id) { CaptureEngine(activity, store, p) }
    val state by engine.ui.collectAsStateWithLifecycle()
    var gl by remember { mutableStateOf<GLSurfaceView?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(engine, owner) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) {
                engine.close()
                gl?.onPause()
                leave()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            engine.close()
            gl?.onPause()
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(state.complete) {
        if (state.complete) {
            engine.close()
            complete()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    preserveEGLContextOnPause = true
                    setRenderer(engine)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    gl = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        CaptureOverlay(
            state = state,
            leave = leave,
            captureNow = engine::captureNow,
            resumeHere = engine::resumeHere,
            reference =
                if (state.needsAnchor && p.captures.isNotEmpty()) {
                    {
                        Photo(
                            File(
                                store.dir(p.id),
                                p.captures
                                    .first()
                                    .exposures[p.captures.first().exposures.size / 2]
                                    .file,
                            ),
                            Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Text(
                            "Reference photo · match this framing",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                } else null,
        )
        if (state.error != null)
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Capture paused") },
                text = { Text(state.error!!) },
                confirmButton = { TextButton(engine::retry) { Text("Retry direction") } },
                dismissButton = { TextButton(leave) { Text("Save and leave") } },
            )
    }
}

@Composable
private fun Details(
    p: Project,
    running: Boolean,
    vm: AppViewModel,
    back: () -> Unit,
    resume: () -> Unit,
    process: () -> Unit,
    export: (String) -> Unit,
) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var name by remember(p.name) { mutableStateOf(p.name) }
    val dir = vm.store.dir(p.id)
    val ready = p.state in listOf("ready", "review")
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(
            "Light study",
            back,
            trailing = {
                IconButton({ renaming = true }) { Icon(Icons.Outlined.Edit, "Rename capture") }
            },
        )
        Text(p.name, fontSize = 32.sp, fontWeight = FontWeight.Medium, lineHeight = 36.sp)
        Text(
            SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(p.created)) +
                " · " +
                p.quality.label,
            color = Muted,
            fontSize = 13.sp,
        )
        if (ready) {
            Box(
                Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp)).clickable {
                    vm.navigate(Screen.VIEWER)
                }
            ) {
                Photo(File(dir, "preview.jpg"), Modifier.fillMaxSize())
                Text(
                    "Explore sphere ↗",
                    Modifier.align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(Ink.copy(alpha = .85f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Lime,
                    fontSize = 13.sp,
                )
            }
            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Eyebrow("RESOLUTION")
                    Text(
                        "${p.quality.outputWidth} × ${p.quality.outputWidth/2}",
                        Modifier.padding(top = 8.dp),
                    )
                }
                Column {
                    Eyebrow("CAPTURE")
                    Text("${p.captures.size} directions", Modifier.padding(top = 8.dp))
                }
            }
            if (p.state == "review") {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Panel, RoundedCornerShape(20.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Quality review",
                        color = Amber,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    p.warnings.forEach {
                        Text("• $it", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                    }
                    OutlinedButton({ vm.navigate(Screen.VIEWER) }, Modifier.fillMaxWidth()) {
                        Text("Inspect sphere")
                    }
                    TextButton({ vm.reviewed(p.id) }) { Text("Mark as reviewed") }
                }
            }
            PrimaryAction("Save HDR environment", { export("environment.hdr") })
            OutlinedButton(
                { export("preview.jpg") },
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Save photosphere JPEG")
            }
            OutlinedButton(
                {
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            File(dir, "environment.hdr"),
                        )
                    val intent =
                        Intent(Intent.ACTION_SEND)
                            .setType("application/octet-stream")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(intent, "Share HDR environment"))
                },
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share HDR")
            }
            Text(
                "The .hdr file holds relative scene radiance for lighting. The JPEG is a tone-mapped preview with photosphere metadata. Keep the original exposures for future processing.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        } else if (running || p.state in listOf("processing", "paused", "failed")) {
            SphereArt(Modifier.fillMaxWidth().height(220.dp), p.progress.toFloat())
            Eyebrow(if (running) "DEVELOPING ON YOUR PHONE" else "YOUR PHOTOS ARE SAVED")
            Text(p.stage, fontSize = 22.sp, lineHeight = 28.sp)
            LinearProgressIndicator(
                progress = { p.progress.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Text("${(p.progress*100).roundToInt()}% complete", color = Lime)
            listOf(
                    "Merge exposure brackets" to .05,
                    "Align overlapping views" to .35,
                    "Find clean seams" to .51,
                    "Blend and export HDR" to .61,
                )
                .forEach { (label, threshold) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (p.progress > threshold) Icons.Outlined.CheckCircle
                            else Icons.Outlined.RadioButtonUnchecked,
                            null,
                            Modifier.size(18.dp),
                            tint = if (p.progress > threshold) Lime else Muted,
                        )
                        Text(
                            label,
                            Modifier.padding(start = 12.dp),
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    }
                }
            if (p.error != null) InfoCard("What needs attention", p.error)
            if (running) {
                PrimaryAction("Pause processing", { ProcessingService.pause(context) })
                Text(
                    "You can leave this screen. Progress stays visible in the notification.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            } else {
                PrimaryAction("Resume processing", process)
                if (!p.sample)
                    OutlinedButton(resume, Modifier.fillMaxWidth()) { Text("Return to capture") }
            }
        } else {
            SphereArt(
                Modifier.fillMaxWidth().height(220.dp),
                p.captures.size.toFloat() / max(1, p.targets.size),
            )
            Text("${p.captures.size} of ${p.targets.size} directions saved", fontSize = 21.sp)
            LinearProgressIndicator(
                progress = { p.captures.size.toFloat() / max(1, p.targets.size) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Resume by matching the reference photo to your camera view. Stay at the same physical position.",
                color = Muted,
                lineHeight = 22.sp,
            )
            PrimaryAction("Continue capture", resume)
            if (p.targets.isNotEmpty() && p.captures.size >= p.targets.size)
                OutlinedButton(process, Modifier.fillMaxWidth()) { Text("Build HDR sphere") }
        }
        if (p.captures.isNotEmpty() && !running)
            TextButton({ export("capture.zip") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export original capture bundle")
            }
        if (!running)
            TextButton({ deleting = true }, Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete capture", color = Muted)
            }
    }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this capture?") },
            text = {
                Text(
                    "This removes its photos and exports from this phone. Copies you saved elsewhere are kept."
                )
            },
            confirmButton = {
                TextButton({
                    deleting = false
                    vm.delete(p.id)
                }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton({ deleting = false }) { Text("Keep") } },
        )
    if (renaming)
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name your light study") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton({
                    renaming = false
                    vm.rename(p.id, name)
                }) {
                    Text("Save")
                }
            },
        )
}

@Composable
private fun Photo(file: File, modifier: Modifier = Modifier) {
    val bitmap by
        produceState<ImageBitmap?>(null, file.path, file.lastModified()) {
            value =
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(
                            file.path,
                            BitmapFactory.Options().apply { inSampleSize = 2 },
                        )
                        ?.asImageBitmap()
                }
        }
    if (bitmap != null)
        Image(bitmap!!, "Captured photosphere", modifier, contentScale = ContentScale.Crop)
    else
        Box(modifier.background(Panel), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
}

@Composable
private fun ViewerScreen(file: File, back: () -> Unit) {
    var view by remember { mutableStateOf<SphereViewer?>(null) }
    var ready by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) view?.onPause()
            else if (e == Lifecycle.Event.ON_RESUME) view?.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view?.onPause()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { SphereViewer(it, file) { ready = true }.also { v -> view = v } },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.fillMaxWidth()
                .background(Ink.copy(alpha = .7f))
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Explore the sphere", Modifier.weight(1f))
            IconButton({ view?.reset() }) { Icon(Icons.Outlined.Refresh, "Reset view") }
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp)
                .background(Ink.copy(alpha = .85f), RoundedCornerShape(24.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Drag to look around · JPEG preview", color = Muted, fontSize = 13.sp)
            Row {
                IconButton({ view?.look(-20.0, 0.0) }) {
                    Icon(Icons.Outlined.ChevronLeft, "Look left")
                }
                IconButton({ view?.look(0.0, 20.0) }) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Look up")
                }
                IconButton({ view?.look(0.0, -20.0) }) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Look down")
                }
                IconButton({ view?.look(20.0, 0.0) }) {
                    Icon(Icons.Outlined.ChevronRight, "Look right")
                }
            }
        }
        if (!ready)
            LinearProgressIndicator(Modifier.align(Alignment.Center).fillMaxWidth().padding(40.dp))
    }
}
