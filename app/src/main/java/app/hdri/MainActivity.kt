package app.hdri

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
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
import app.hdri.core.CoveragePlanner
import app.hdri.core.InertialOrientation
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
            SphereTheme {
                val vm: AppViewModel = viewModel()
                SphereApp(vm)
            }
        }
    }
}

@Composable
private fun SphereApp(vm: AppViewModel) {
    // Capture one immutable snapshot for dialog subcompositions. A delegated read
    // can turn null between the outer condition and the dialog title redraw.
    val app = vm.state.collectAsStateWithLifecycle().value
    val processing by ProcessingService.status.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as ComponentActivity
    var quality by remember { mutableStateOf(Quality.DETAIL) }
    var masterFormat by remember { mutableStateOf(MasterFormat.EXR) }
    var groundMode by remember { mutableStateOf(GroundMode.CAPTURE) }
    var cameraKey by remember { mutableStateOf("main") }
    var density by remember { mutableStateOf(CoverageDensity.COMPACT) }
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
                (if (resume) app.projects.firstOrNull { it.id == app.selected }?.cameraKey
                else cameraKey) == "main" &&
                    ArCoreApk.getInstance().requestInstall(activity, true) ==
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED
            ) {
                vm.error(
                    "Finish installing Google Play Services for AR, then tap Start capture again."
                )
                return
            }
            if (resume) vm.navigate(Screen.CAPTURE)
            else vm.create(quality, false, masterFormat, groundMode, cameraKey, density)
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
                    "Camera permission is needed to capture a photosphere. Enable it in Android Settings → Apps → sphere → Permissions."
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
            if (name.endsWith(".zip")) "sphere-capture-${p.id.take(8)}.zip"
            else "sphere-${p.id.take(8)}-${name}"
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
                    vm::clearExportCache,
                )
            Screen.SETUP ->
                Setup(
                    quality,
                    { quality = it },
                    { vm.navigate(Screen.HOME) },
                    { startCapture(false) },
                    processing.running,
                    masterFormat,
                    { masterFormat = it },
                    groundMode,
                    { groundMode = it },
                    cameraKey,
                    { cameraKey = it },
                    density,
                    { density = it },
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
                        app.storage[project.id],
                        { vm.navigate(Screen.HOME) },
                        { startCapture(true) },
                        { process(project.id) },
                        { name -> export(project, name) },
                    )
            Screen.VIEWER ->
                if (project != null)
                    ViewerScreen(
                        vm.store.masterFile(project),
                        project.name,
                        project.captures
                            .mapNotNull { c ->
                                c.exposures
                                    .sortedBy { it.seconds }
                                    .let { it.getOrNull(it.size / 2)?.seconds }
                            }
                            .sorted()
                            .let { it.getOrNull(it.size / 2)?.toFloat() },
                    ) {
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
            title = { Text("Could not complete operation") },
            text = { Text(app.error!!) },
            confirmButton = { TextButton(vm::clearError) { Text("OK") } },
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
            confirmButton = {
                if (app.operationCancellable) TextButton(vm::cancelExport) { Text("Cancel") }
            },
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
                androidx.compose.ui.res.painterResource(R.drawable.ic_brand_mark),
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
    clearCache: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Header(
                "sphere",
                trailing = {
                    Text("PREVIEW", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Capture HDR\nenvironments.",
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1.5).sp,
                )
                Text(
                    "Guided photosphere capture, on-device stitching, and HDR lighting previews.",
                    color = Muted,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                )
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().height(190.dp).background(Panel, RoundedCornerShape(28.dp))
            ) {
                SphereArt(Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Eyebrow("360° × 180°")
                    Text(
                        "LOCAL PROCESSING",
                        color = Muted,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
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
                    "No captures yet. Tap New photosphere to start.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        if (app.projects.isNotEmpty())
            item {
                Text(
                    "${formatBytes(app.storage.values.sumOf { it.total })} used by captures",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        item {
            Text(
                "Capture sizes exclude the installed app. Android Settings also counts the app and its image-processing libraries.",
                color = Muted,
                fontSize = 12.sp,
            )
            if (app.exportCacheBytes > 0)
                TextButton(clearCache, enabled = app.operation == null) {
                    Text("Clear temporary exports · ${formatBytes(app.exportCacheBytes)}")
                }
        }
        items(app.projects, key = { it.id }) { p ->
            CaptureRow(p, app.storage[p.id]) { open(p.id) }
        }
    }
}

@Composable
private fun CaptureRow(p: Project, storage: CaptureStorage?, open: () -> Unit) {
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
            storage?.let {
                Text("${formatBytes(it.total)} on device", color = Muted, fontSize = 12.sp)
            }
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
    masterFormat: MasterFormat,
    selectFormat: (MasterFormat) -> Unit,
    groundMode: GroundMode,
    selectGround: (GroundMode) -> Unit,
    cameraKey: String,
    selectCamera: (String) -> Unit,
    density: CoverageDensity,
    selectDensity: (CoverageDensity) -> Unit,
) {
    val context = LocalContext.current
    var cameraAttempt by remember { mutableIntStateOf(0) }
    var lensDetails by remember { mutableStateOf(false) }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraAttempt++
        }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) cameraAttempt++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val cameraScan by
        produceState<CameraScan?>(null, cameraAttempt) {
            value = null
            value = withContext(Dispatchers.IO) { CameraCatalog.inspect(context) }
        }
    val cameras = cameraScan?.choices
    val captureCameras = cameraScan?.captureChoices
    LaunchedEffect(cameraScan, cameraKey) {
        // This is new-capture setup only. Existing sessions retain their original camera key.
        if (
            cameraScan != null &&
                cameraKey != "main" &&
                captureCameras?.none { it.key == cameraKey } == true
        )
            selectCamera("main")
    }
    if (lensDetails)
        AlertDialog(
            onDismissRequest = { lensDetails = false },
            title = { Text("Camera lens details") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        cameraScan?.diagnostics?.joinToString("\n\n") ?: "Checking cameras…",
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton({ lensDetails = false }) { Text("Done") } },
            dismissButton = {
                TextButton({
                    context
                        .getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "sphere camera details",
                                cameraScan?.diagnostics?.joinToString("\n") ?: "Checking cameras",
                            )
                        )
                }) {
                    Text("Copy details")
                }
            },
        )
    val selectedLens =
        cameras?.firstOrNull { it.key == cameraKey }
            ?: cameras?.firstOrNull { cameraKey == "main" && it.label.startsWith("Main") }
    val stopCount by
        produceState<Int?>(null, selectedLens, density, groundMode) {
            value = null
            if (selectedLens != null)
                value =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                        CoveragePlanner.targets(
                                selectedLens.lens,
                                InertialOrientation.cameraInDevice(selectedLens.sensorOrientation),
                                density.margin,
                                groundMode.minPitch,
                            ) {
                                if (job?.isActive == false)
                                    throw kotlinx.coroutines.CancellationException()
                            }
                            .size
                    }
        }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header("New photosphere", back)
        Text(
            "Capture the light\naround you.",
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Medium,
        )
        InfoCard(
            "01  Turn where you stand",
            "Wipe the selected lens first. Turn where you stand, aim at each dot, and pause until the photo is captured.",
        )
        InfoCard(
            "02  Align with each dot",
            "Hold the phone upright, then follow the dots. Gyro guidance works on blank sky. Each stop automatically captures an HDR bracket.",
        )
        InfoCard(
            "03  Process and export",
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
                        if (q == Quality.DETAIL) "Detailed environment" else "Quick capture",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(q.label, color = Muted, fontSize = 13.sp)
                }
            }
        }
        Text("Camera lens", fontWeight = FontWeight.Medium)
        FilterChip(cameraKey == "main", { selectCamera("main") }, label = { Text("Main camera") })
        captureCameras?.forEach { camera ->
            FilterChip(
                cameraKey == camera.key,
                { selectCamera(camera.key) },
                label = { Text(camera.label) },
            )
        }
        if (cameraScan == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (cameraScan?.permissionRequired == true) {
            Text(
                "Allow camera access to find the ultrawide and other lenses.",
                color = Muted,
                fontSize = 13.sp,
            )
            OutlinedButton({ cameraPermission.launch(Manifest.permission.CAMERA) }) {
                Text("Allow camera access")
            }
        } else
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ cameraAttempt++ }) { Text("Refresh lenses") }
                TextButton({ lensDetails = true }) { Text("Lens details") }
            }
        Text(
            "Ultrawide needs fewer stops. The selected lens stays fixed throughout the capture.",
            color = Muted,
            fontSize = 13.sp,
        )
        Text("Capture pace", fontWeight = FontWeight.Medium)
        CoverageDensity.entries.forEach { option ->
            FilterChip(density == option, { selectDensity(option) }, label = { Text(option.label) })
        }
        Text(
            stopCount?.let {
                "About $it stops · ${it * quality.bracketCount} photos. More overlap gives the stitcher more shared detail."
            }
                ?: if (selectedLens != null) "Calculating stops…"
                else "The stop count is calculated when the camera opens.",
            color = Muted,
            fontSize = 13.sp,
        )
        Text("Stored HDR format", fontWeight = FontWeight.Medium)
        MasterFormat.entries.forEach { format ->
            FilterChip(
                masterFormat == format,
                { selectFormat(format) },
                label = { Text(format.label) },
            )
        }
        Text(
            "One HDR master and a small JPEG preview are kept. The other HDR format is converted when you export it.",
            color = Muted,
            fontSize = 13.sp,
        )
        Text("Ground", fontWeight = FontWeight.Medium)
        GroundMode.entries.forEach { mode ->
            FilterChip(groundMode == mode, { selectGround(mode) }, label = { Text(mode.label) })
        }
        if (groundMode == GroundMode.FILL)
            Text(
                "Skip the straight-down photo. The bottom 35° is filled from nearby ground colour and texture; it is an approximation.",
                color = Muted,
                fontSize = 13.sp,
            )
        Text(
            "Leave at least 1 GB free for capture and processing. Temporary processing files are cleared when finished. You can remove source photos later to keep only the HDRI. HDR exports contain relative lighting values. The default main-camera preview uses Google Play Services for AR. Other listed lenses use Camera2 directly. Gyro guidance and stitching work offline.",
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
    val state = engine.ui.collectAsStateWithLifecycle().value
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
    storage: CaptureStorage?,
    back: () -> Unit,
    resume: () -> Unit,
    process: () -> Unit,
    export: (String) -> Unit,
) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var removingSources by remember { mutableStateOf(false) }
    var name by remember(p.name) { mutableStateOf(p.name) }
    val dir = vm.store.dir(p.id)
    val ready = p.state in listOf("ready", "review") && !running
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(
            "Capture",
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
        storage?.let { Text("${formatBytes(it.total)} on device", color = Muted, fontSize = 13.sp) }
        if (ready) {
            Box(
                Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp)).clickable {
                    vm.navigate(Screen.VIEWER)
                }
            ) {
                Photo(File(dir, "preview.jpg"), Modifier.fillMaxSize())
                Text(
                    "View environment ↗",
                    Modifier.align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(Ink.copy(alpha = .85f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Lime,
                    fontSize = 13.sp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryAction("Lighting spheres", { vm.navigate(Screen.VIEWER) })
                Text(
                    "Chrome + 18% grey · rotate and adjust exposure",
                    color = Muted,
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
            if (!p.sourcesRemoved)
                OutlinedButton(
                    process,
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Rebuild from saved photos")
                }
            Text("Export", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            PrimaryAction("Save OpenEXR (.exr)", { export("environment.exr") })
            Text("32-bit float RGB · lossless ZIP compression", color = Muted, fontSize = 13.sp)
            OutlinedButton(
                { export("environment.hdr") },
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Save Radiance (.hdr)")
            }
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
                            vm.store.masterFile(p),
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
                Text("Share ${p.masterFormat.name}")
            }
            Text(
                "One ${p.masterFormat.name} master is stored. Other export formats are converted on demand. The JPEG is a tone-mapped preview.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        } else if (running || p.state in listOf("processing", "paused", "failed")) {
            SphereArt(Modifier.fillMaxWidth().height(220.dp), p.progress.toFloat())
            Eyebrow(if (running) "PROCESSING ON DEVICE" else "PROCESSING PAUSED")
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
        if (p.groundMode == GroundMode.FILL)
            Text(
                "Ground fill: the bottom 35° is approximate colour and texture, not captured scene detail.",
                color = Muted,
                fontSize = 13.sp,
            )
        StorageCard(
            p,
            storage,
            running,
            { vm.clearStorage(p.id, false) },
            { removingSources = true },
            { vm.changeMaster(p.id, it) },
        )
        if (p.captures.isNotEmpty() && !p.sourcesRemoved && !running)
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
    if (removingSources)
        AlertDialog(
            onDismissRequest = { removingSources = false },
            title = { Text("Remove source photos?") },
            text = {
                Text(
                    "Free ${formatBytes((storage?.sources ?: 0L) + (storage?.processing ?: 0L))} by removing original photos and processing files. Keep the finished HDR, JPEG, lighting viewer and EXR export. This capture can no longer be rebuilt. Export the original capture bundle first if you want a backup."
                )
            },
            confirmButton = {
                TextButton({
                    removingSources = false
                    vm.clearStorage(p.id, true)
                }) {
                    Text("Remove photos")
                }
            },
            dismissButton = { TextButton({ removingSources = false }) { Text("Cancel") } },
        )
    if (renaming)
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename capture") },
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
private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(LocalContext.current, bytes)

@Composable
private fun StorageCard(
    p: Project,
    usage: CaptureStorage?,
    running: Boolean,
    clearCache: () -> Unit,
    clearSources: () -> Unit,
    changeMaster: (MasterFormat) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Storage", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        if (usage == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Measuring storage…", color = Muted)
        } else {
            listOf(
                    "Source photos" to usage.sources,
                    "Processing files" to usage.processing,
                    "HDR master" to usage.environment,
                    "JPEG preview" to usage.preview,
                    "Metadata and other files" to
                        (usage.other - usage.environment - usage.preview).coerceAtLeast(0),
                    "Total on device" to usage.total,
                )
                .forEach { (label, bytes) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = Muted, fontSize = 14.sp)
                        Text(formatBytes(bytes), fontSize = 14.sp)
                    }
                }
            if (p.sourcesRemoved)
                Text(
                    "Source photos removed. Viewing and exporting remain available; rebuilding is unavailable.",
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            Text(
                if (p.state in listOf("paused", "failed"))
                    "Processing files speed up resume. Clearing them restarts processing from the saved photos."
                else
                    "Temporary processing files are cleared after each successful build. Original photos stay until you remove them.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            if (!running && usage.processing > 0 && p.state != "capture")
                OutlinedButton(clearCache, Modifier.fillMaxWidth()) {
                    Text("Clear processing files")
                }
            if (!running && p.state in listOf("ready", "review") && usage.sources > 0)
                OutlinedButton(clearSources, Modifier.fillMaxWidth()) {
                    Text(
                        if (p.sourcesRemoved) "Remove remaining source photos"
                        else "Remove source photos"
                    )
                }
            if (!running && p.state in listOf("ready", "review")) {
                Text(
                    "Stored as ${p.masterFormat.label}. EXR uses lossless ZIP compression; HDR uses RGBE precision. Only one master is needed.",
                    color = Muted,
                    fontSize = 13.sp,
                )
                val other =
                    if (p.masterFormat == MasterFormat.HDR) MasterFormat.EXR else MasterFormat.HDR
                OutlinedButton({ changeMaster(other) }, Modifier.fillMaxWidth()) {
                    Text("Store as ${other.name}")
                }
            }
        }
    }
}
