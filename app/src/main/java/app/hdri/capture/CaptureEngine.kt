package app.hdri.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.*
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.*
import android.util.Size
import android.view.Surface
import app.hdri.core.*
import app.hdri.core.Target
import app.hdri.data.*
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Marker(
    val id: Int,
    val x: Float,
    val y: Float,
    val complete: Boolean,
    val active: Boolean,
)

data class CaptureUi(
    val message: String = "Starting camera…",
    val detail: String = "Camera and gyro guidance",
    val captured: Int = 0,
    val total: Int = 0,
    val markers: List<Marker> = emptyList(),
    val dwell: Float = 0f,
    val bracket: Float = 0f,
    val busy: Boolean = false,
    val ready: Boolean = false,
    val needsAnchor: Boolean = false,
    val error: String? = null,
    val complete: Boolean = false,
    val guide: AimGuide? = null,
    val aimDegrees: Float = 180f,
    val aimLocked: Boolean = false,
    val manualReady: Boolean = false,
    val notice: String? = null,
    val route: RouteProgress? = null,
)

/** Inertial orientation owns guidance. ARCore supplies preview/intrinsics and shared Camera2. */
@SuppressLint("MissingPermission")
class CaptureEngine(
    private val activity: Activity,
    private val store: SessionStore,
    initial: Project,
) : GLSurfaceView.Renderer, SensorEventListener {
    private val state =
        MutableStateFlow(
            CaptureUi(
                captured = initial.captures.size,
                total = initial.targets.size,
                needsAnchor = initial.captures.isNotEmpty(),
            )
        )
    val ui = state.asStateFlow()
    @Volatile private var project = initial
    private val thread = HandlerThread("HDRI camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val motionThread = HandlerThread("HDRI motion").apply { start() }
    private val gyro = GyroHistory()
    private val orientation = InertialOrientation()
    @Volatile private var sensorOrientation = 90
    private val arLock = Any()
    private val backdrop = CameraBackdrop()
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val manager = activity.getSystemService(CameraManager::class.java)
    private val nativeCamera = initial.cameraKey != "main"
    private var choice: CameraChoice? = null
    private val cameraMetadata = mutableMapOf<String, CameraCharacteristics>()
    private var logicalCharacteristics: CameraCharacteristics? = null
    private var lensGuard = LensSessionGuard(null)
    private var zoomWaitSince = 0L

    private fun metadata(id: String): CameraCharacteristics =
        cameraMetadata.getOrPut(id) { manager.getCameraCharacteristics(id) }

    private fun <T> control(key: CameraCharacteristics.Key<T>): T? =
        characteristics?.get(key) ?: logicalCharacteristics?.get(key)

    private var nativeTexture: SurfaceTexture? = null
    private var nativeSurface: Surface? = null
    private var previewSize: Size? = null
    @Volatile private var nativeFrame = false
    private var physicalKeys = emptyList<CaptureRequest.Key<*>>()
    private var ar: Session? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var capturing = false
    @Volatile private var opened = false
    @Volatile private var latestResult: CaptureResult? = null
    @Volatile private var latestQ = Q()
    @Volatile private var lens: Lens? = null
    @Volatile private var offset: Q? = null
    private var width = 1
    private var height = 1
    private var lastUi = 0L
    private var lastFrameTimestamp = 0L
    private var selected = -1
    private var routeTargets: List<Target>? = null
    private var route = CaptureRoute(initial.targets)
    @Volatile private var focusWaitSince = 0L
    @Volatile private var planning = false
    private val gate = SteadyGate()
    private var pending: Pending? = null
    @Volatile private var manualRequested = false
    @Volatile private var noticeUntil = 0L
    @Volatile private var notice: String? = null
    @Volatile private var resultReceivedAt = 0L
    @Volatile private var focusStableSince = 0L
    @Volatile private var previousFocus: Float? = null
    @Volatile private var errorLatched = false

    private data class Pending(
        val id: Int,
        val q: Q,
        val offset: Q,
        val lens: Lens,
        val expected: Int,
        val fallbackFocus: Boolean = false,
        val results: MutableMap<Long, Exposure> = mutableMapOf(),
        val files: MutableMap<Long, String> = mutableMapOf(),
        val windows: MutableList<LongRange> = mutableListOf(),
        val lenses: MutableMap<Long, Lens> = mutableMapOf(),
    )

    init {
        try {
            if (!nativeCamera)
                ar =
                    Session(activity, EnumSet.of(Session.Feature.SHARED_CAMERA)).also { session ->
                        val config =
                            Config(session).apply {
                                focusMode = Config.FocusMode.AUTO
                                planeFindingMode = Config.PlaneFindingMode.DISABLED
                                lightEstimationMode = Config.LightEstimationMode.DISABLED
                                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            }
                        session.configure(config)
                    }
            val motionHandler = Handler(motionThread.looper)
            for (type in listOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE)) {
                val sensor =
                    sensors.getDefaultSensor(type)
                        ?: error(
                            "This phone needs a gyroscope and fused rotation sensor for sky capture."
                        )
                check(sensors.registerListener(this, sensor, 10_000, motionHandler)) {
                    "Could not start the motion sensors. Reopen capture to try again."
                }
            }
        } catch (e: Exception) {
            fail("Camera or motion sensors are unavailable. ${e.message.orEmpty()}")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (closed || (!nativeCamera && ar == null)) return
        try {
            backdrop.create()
            if (nativeCamera)
                nativeTexture =
                    SurfaceTexture(backdrop.texture).also {
                        it.setOnFrameAvailableListener({ nativeFrame = true }, handler)
                    }
            synchronized(arLock) { ar?.setCameraTextureName(backdrop.texture) }
            if (!opened) {
                opened = true
                handler.post { openCamera() }
            }
        } catch (e: Exception) {
            fail("Could not initialize the camera view: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (closed) return
        synchronized(arLock) {
            val session = ar
            if (!nativeCamera && session == null) return
            if (nativeCamera && nativeFrame) {
                nativeFrame = false
                nativeTexture?.updateTexImage()
            }
            if (!active) {
                if (backdrop.texture != 0) backdrop.draw(null)
                return
            }
            try {
                @Suppress("DEPRECATION")
                val rotation = activity.windowManager.defaultDisplay.rotation
                val cameraInDisplay =
                    InertialOrientation.displayInDevice(rotation * 90).inverse() *
                        InertialOrientation.cameraInDevice(sensorOrientation)
                val frame =
                    if (!nativeCamera) {
                        session!!.setDisplayGeometry(rotation, width, height)
                        session.update().also { backdrop.draw(it) }
                    } else null
                if (nativeCamera) {
                    val visibleLens = lens ?: return
                    val coordinates =
                        CameraGeometry.previewCoordinates(
                            cameraInDisplay,
                            visibleLens,
                            width,
                            height,
                        )
                    val transform = FloatArray(16)
                    nativeTexture!!.getTransformMatrix(transform)
                    backdrop.drawNative(coordinates, transform)
                }
                val timestamp = frame?.timestamp ?: nativeTexture?.timestamp ?: 0L
                if (timestamp == 0L || timestamp == lastFrameTimestamp) return
                lastFrameTimestamp = timestamp
                val c = frame?.camera
                // The AR camera's pose and tracking state are intentionally never consulted.
                // Its image stream and intrinsics remain usable when visual tracking is paused.
                val motion = orientation.latest(SystemClock.elapsedRealtimeNanos())
                if (motion == null) {
                    manualRequested = false
                    gate.reset()
                    emit(
                        CaptureUi(
                            "Waiting for motion sensors…",
                            "Gyro guidance will resume automatically. Stay in place.",
                            project.captures.size,
                            project.targets.size,
                            needsAnchor = state.value.needsAnchor,
                        )
                    )
                    return
                }
                latestQ =
                    (motion.device * InertialOrientation.cameraInDevice(sensorOrientation))
                        .normalized()
                if (c != null) {
                    val i = c.imageIntrinsics
                    val dims = i.imageDimensions
                    val f = i.focalLength
                    val cp = i.principalPoint
                    reader?.let {
                        lens =
                            Lens(
                                    dims[0],
                                    dims[1],
                                    f[0].toDouble(),
                                    f[1].toDouble(),
                                    cp[0].toDouble(),
                                    cp[1].toDouble(),
                                )
                                .scaled(it.width, it.height)
                    }
                }
                if (offset == null && project.captures.isEmpty()) {
                    offset = InertialOrientation.zeroHeading(motion.device)
                }
                if (planning || errorLatched) return
                if (nativeCamera && latestResult == null) {
                    emit(
                        state.value.copy(
                            message = "Preparing selected lens…",
                            detail = "Checking zoom and calibration before planning coverage.",
                            ready = false,
                        )
                    )
                    return
                }
                if (
                    project.targets.isEmpty() || project.coverageVersion < CoveragePlanner.VERSION
                ) {
                    val captureLens = lens ?: return
                    planning = true
                    state.value =
                        state.value.copy(
                            message = "Optimizing sphere coverage…",
                            detail = "Using the full camera view to reduce the number of stops.",
                            ready = false,
                        )
                    val cameraInDisplay =
                        InertialOrientation.displayInDevice(rotation * 90).inverse() *
                            InertialOrientation.cameraInDevice(sensorOrientation)
                    handler.post { preparePlan(captureLens, cameraInDisplay) }
                    return
                }
                if (state.value.needsAnchor) {
                    emit(
                        state.value.copy(
                            message = "Match your first photo",
                            detail = "Rotate into the reference view, then tap Resume here.",
                            ready = true,
                        )
                    )
                    return
                }
                if (capturing || errorLatched) return
                val q = (offset ?: Q()) * latestQ
                val forward = q.rotate(V3.FORWARD)
                val done = project.captures.map { it.targetId }.toSet()
                if (routeTargets !== project.targets) {
                    routeTargets = project.targets
                    route = CaptureRoute(project.targets)
                }
                val next = route.next(done)
                if (next == null) {
                    emit(
                        CaptureUi(
                            "Sphere captured",
                            "Every direction is saved on your phone.",
                            done.size,
                            project.targets.size,
                            ready = true,
                            complete = true,
                        )
                    )
                    return
                }
                if (selected != next.id) {
                    selected = next.id
                    focusWaitSince = 0L
                    gate.reset()
                }
                val angle = forward.angle(next.ray)
                val now = SystemClock.elapsedRealtimeNanos()
                if (angle > CaptureTolerance.AIM_EXIT) focusWaitSince = 0L
                else if (focusWaitSince == 0L) focusWaitSince = now
                val focusFallback = focusWaitSince != 0L && now - focusWaitSince >= 1_800_000_000L
                val focusReady =
                    now - resultReceivedAt < 500_000_000L &&
                        ((focusStableSince != 0L && now - focusStableSince >= 200_000_000L) ||
                            focusFallback)
                val dwell = gate.update(motion.time, q, angle, focusReady)
                val dq =
                    (offset ?: Q()) *
                        motion.device *
                        InertialOrientation.displayInDevice(rotation * 90)
                val proj = FloatArray(16)
                c?.getProjectionMatrix(proj, 0, .1f, 100f)
                val markers =
                    project.targets.mapNotNull { t ->
                        if (t.id != next.id && t.id !in done) return@mapNotNull null
                        val v = dq.inverse().rotate(t.ray)
                        if (v.z >= -.05) return@mapNotNull null
                        val screen =
                            if (nativeCamera) {
                                val l = lens ?: return@mapNotNull null
                                val local = q.inverse().rotate(t.ray)
                                val pixel = l.project(local) ?: return@mapNotNull null
                                CameraGeometry.imageToDisplay(
                                    pixel.first,
                                    pixel.second,
                                    cameraInDisplay,
                                    l,
                                    width,
                                    height,
                                )
                            } else null
                        val nx =
                            screen?.let { it.first * 2 - 1 }
                                ?: ((proj[0] * v.x + proj[8] * v.z) / -v.z)
                        val ny =
                            screen?.let { 1 - it.second * 2 }
                                ?: ((proj[5] * v.y + proj[9] * v.z) / -v.z)
                        if (abs(nx) > 1.15 || abs(ny) > 1.15) null
                        else
                            Marker(
                                t.id,
                                ((nx + 1) / 2).toFloat(),
                                ((1 - ny) / 2).toFloat(),
                                t.id in done,
                                t.id == next.id,
                            )
                    }
                val guide = AimGuide.from(dq, next.ray)
                val msg =
                    when {
                        !focusReady && angle <= CaptureTolerance.AIM_EXIT ->
                            "Waiting for focus to settle"
                        gate.reason == HoldReason.SETTLING || gate.reason == HoldReason.READY ->
                            "Aligned · capturing automatically"
                        else -> guide.instruction
                    }
                emit(
                    CaptureUi(
                        msg,
                        if (angle <= CaptureTolerance.AIM_EXIT)
                            "Pause your turn · let focus and the shutter ring settle."
                        else "Follow the arrow to the highlighted dot.",
                        done.size,
                        project.targets.size,
                        markers,
                        dwell.toFloat(),
                        ready = true,
                        guide = guide,
                        aimDegrees = angle.toFloat(),
                        aimLocked =
                            gate.reason == HoldReason.SETTLING || gate.reason == HoldReason.READY,
                        manualReady = angle <= CaptureTolerance.AIM_ENTER && dwell > 0.0,
                        notice = if (SystemClock.elapsedRealtime() < noticeUntil) notice else null,
                        route = route.progress(done),
                    )
                )
                val manual = manualRequested && angle <= CaptureTolerance.AIM_ENTER && dwell > 0.0
                manualRequested = false
                if (dwell >= 1.0 || manual) {
                    capturing = true
                    val captureLens = lens!!
                    val captureOffset = offset ?: Q()
                    handler.post { capture(next.id, q, captureOffset, captureLens) }
                }
            } catch (e: Exception) {
                fail(
                    "Tracking stopped: ${e.message.orEmpty()}. Leave capture and reopen to recover your saved photos."
                )
            }
        }
    }

    private fun preparePlan(lens: Lens, cameraInDisplay: Q) {
        try {
            val original = project
            val planned =
                CoveragePlanner.targets(
                    lens,
                    cameraInDisplay,
                    original.density.margin,
                    original.groundMode.minPitch,
                ) { progress ->
                    check(!closed) { "Capture was closed." }
                    state.value =
                        state.value.copy(
                            detail = "Checking overlap · ${(progress * 100).roundToInt()}%"
                        )
                }
            if (closed) return
            val saved = original.captures.map { PhotoFootprint(it.rotation, it.lens) }
            val remaining =
                CoveragePlanner.retainNeeded(
                    planned,
                    lens,
                    cameraInDisplay,
                    saved,
                    original.density.margin,
                    original.groundMode.minPitch,
                )
            if (closed) return
            project = store.update(original.id) { CapturePlans.replaceRemaining(it, remaining) }
            val targets = project.targets
            notice =
                if (original.targets.isNotEmpty())
                    "Plan updated · ${remaining.size} stops left · saved photos kept"
                else "${targets.size} stops · the camera takes HDR exposures at each"
            noticeUntil = SystemClock.elapsedRealtime() + 6000
            state.value = state.value.copy(total = targets.size)
        } catch (e: Exception) {
            fail("Could not prepare the capture plan: ${e.message}")
        } finally {
            planning = false
        }
    }

    private fun emit(value: CaptureUi) {
        val now = SystemClock.elapsedRealtime()
        if (
            now - lastUi > 80 ||
                value.error != null ||
                value.complete ||
                value.busy != state.value.busy
        ) {
            lastUi = now
            state.value = value
        }
    }

    fun resumeHere() {
        synchronized(arLock) {
            val first = project.captures.firstOrNull() ?: return
            val motion = orientation.latest(SystemClock.elapsedRealtimeNanos()) ?: return
            val physical = motion.device * InertialOrientation.cameraInDevice(sensorOrientation)
            offset = (first.rotation * physical.inverse()).normalized()
            state.value = state.value.copy(needsAnchor = false)
            gate.reset()
        }
    }

    fun retry() {
        handler.post {
            errorLatched = false
            state.value = state.value.copy(error = null)
            gate.reset()
            if (!active && !closed) resumeAr()
        }
    }

    fun captureNow() {
        if (state.value.manualReady && !capturing && !errorLatched && !closed)
            manualRequested = true
    }

    private fun fail(message: String) {
        errorLatched = true
        gate.reset()
        state.value = state.value.copy(error = message, busy = false, dwell = 0f)
    }

    private fun openCamera() {
        if (closed) return
        try {
            val session = ar
            if (nativeCamera)
                choice =
                    CameraCatalog.discover(activity).firstOrNull { it.key == project.cameraKey }
                        ?: error(
                            "The selected lens is unavailable. Start a new capture with a supported lens."
                        )
            val id = choice?.cameraId ?: session!!.cameraConfig.cameraId
            logicalCharacteristics = metadata(id)
            val c = metadata(choice?.physicalId ?: id)
            lensGuard = LensSessionGuard(choice?.zoomRatio)
            zoomWaitSince = 0L
            latestResult = null
            physicalKeys = metadata(id).availablePhysicalCameraRequestKeys.orEmpty()
            characteristics = c
            check(
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            ) {
                "Capture requires the rear camera."
            }
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val caps =
                (metadata(id)[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf())
            check(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) {
                "This camera cannot capture manual exposure brackets."
            }
            val rawSize =
                choice?.let { CameraCatalog.photoSize(c) } ?: session!!.cameraConfig.imageSize
            val sizes =
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(
                    ImageFormat.JPEG
                )
            val ratio = rawSize.width.toDouble() / rawSize.height
            val size =
                sizes
                    .filter { abs(it.width.toDouble() / it.height - ratio) < .015 }
                    .minByOrNull { abs(it.width.toLong() * it.height - 3_000_000) }
                    ?: error("No still-photo size matches the tracked camera lens.")
            reader =
                ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3).also { ir ->
                    ir.setOnImageAvailableListener(
                        { source ->
                            val image =
                                try {
                                    source.acquireNextImage()
                                } catch (_: Exception) {
                                    null
                                } ?: return@setOnImageAvailableListener
                            try {
                                val timestamp = image.timestamp
                                val bytes = ByteArray(image.planes[0].buffer.remaining())
                                image.planes[0].buffer.get(bytes)
                                val p = pending
                                if (p != null) {
                                    val name = "${p.id}-$timestamp.jpg"
                                    val file = File(store.dir(project.id), "$name.part")
                                    FileOutputStream(file).use {
                                        it.write(bytes)
                                        it.fd.sync()
                                    }
                                    p.files[timestamp] = name
                                    finishIfReady()
                                }
                            } catch (e: Exception) {
                                abort(
                                    "Could not save this bracket: ${e.message}. Free storage, then retry."
                                )
                            } finally {
                                image.close()
                            }
                        },
                        handler,
                    )
                }
            if (nativeCamera) {
                lens = CameraCatalog.frameCalibration(choice!!, ::metadata, null, size)
                val previews =
                    c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(
                        SurfaceTexture::class.java
                    )
                previewSize =
                    previews
                        .filter {
                            abs(it.width.toDouble() / it.height - ratio) < .015 && it.width <= 1920
                        }
                        .minByOrNull { abs(it.width * it.height - 1_000_000) }
                        ?: error("No calibrated preview size matches this lens.")
                nativeTexture!!.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
                nativeSurface = Surface(nativeTexture)
            } else session!!.sharedCamera.setAppSurfaces(id, listOf(reader!!.surface))
            val callback =
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (closed) {
                            device.close()
                            return
                        }
                        camera = device
                        configure()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        fail("Camera disconnected. Close other camera apps and reopen capture.")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail("Camera error $error. Close other camera apps and reopen capture.")
                    }

                    override fun onClosed(device: CameraDevice) {
                        if (closed) finishClose()
                    }
                }
            manager.openCamera(
                id,
                if (nativeCamera) callback
                else session!!.sharedCamera.createARDeviceStateCallback(callback, handler),
                handler,
            )
        } catch (e: Exception) {
            fail("Could not open the HDR camera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun configure() {
        try {
            val session = ar
            val device = camera ?: return
            val surfaces = previewSurfaces().toMutableList()
            surfaces.add(reader!!.surface)
            val callback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed) {
                            s.close()
                            return
                        }
                        captureSession = s
                        startPreview()
                    }

                    override fun onActive(s: CameraCaptureSession) {
                        if (!active && !capturing && !closed) resumeAr()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fail(
                            "This lens does not support the required preview and HDR photo streams. Start a new capture with the main lens. Saved photos are safe."
                        )
                    }
                }
            if (nativeCamera) {
                val outputs =
                    surfaces.map { surface ->
                        OutputConfiguration(surface).apply {
                            choice?.physicalId?.let { setPhysicalCameraId(it) }
                        }
                    }
                val configuration =
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        java.util.concurrent.Executor { handler.post(it) },
                        callback,
                    )
                check(
                    runCatching { device.isSessionConfigurationSupported(configuration) }
                        .getOrDefault(true)
                ) {
                    "This lens cannot combine calibrated preview and HDR photographs. Choose the main lens."
                }
                device.createCaptureSession(configuration)
            } else
                device.createCaptureSession(
                    surfaces,
                    session!!.sharedCamera.createARSessionStateCallback(callback, handler),
                    handler,
                )
        } catch (e: Exception) {
            fail("Camera configuration failed: ${e.message}")
        }
    }

    private fun previewSurfaces(): List<Surface> =
        if (nativeCamera) listOfNotNull(nativeSurface)
        else ar?.sharedCamera?.arCoreSurfaces.orEmpty()

    private fun physicalResult(result: TotalCaptureResult): CaptureResult? {
        val id = choice?.physicalId ?: return result
        return result.physicalCameraResults[id].also {
            if (it == null)
                fail(
                    "The selected lens did not return its calibration and exposure metadata. Choose the main lens for a new capture."
                )
        }
    }

    private fun calibratedFrame(result: CaptureResult, size: Size): Lens {
        val zoom =
            if (Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null
        check(lensGuard.zoomReady(zoom)) { "The camera changed zoom. Retry this direction." }
        lensGuard.acceptPhysical(result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID])
        val correction = result[CaptureResult.DISTORTION_CORRECTION_MODE]
        check(
            correction == CaptureRequest.DISTORTION_CORRECTION_MODE_FAST ||
                correction == CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        ) {
            "The camera did not report geometric correction for this frame."
        }
        return CameraCatalog.frameCalibration(choice!!, ::metadata, result, size)
    }

    private fun newRequest(template: Int): CaptureRequest.Builder =
        choice?.physicalId?.let { camera!!.createCaptureRequest(template, setOf(it)) }
            ?: camera!!.createCaptureRequest(template)

    private fun finishRequest(builder: CaptureRequest.Builder): CaptureRequest {
        if (nativeCamera) {
            val c = characteristics!!
            if (
                c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.contains(0) ==
                    true
            )
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                )
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, choice!!.correctionMode)
            if (Build.VERSION.SDK_INT >= 30 && choice?.zoomRatio != null)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, choice!!.zoomRatio)
            val active = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]!!
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                android.graphics.Rect(0, 0, active.width(), active.height()),
            )
            if (Build.VERSION.SDK_INT >= 31)
                builder.set(
                    CaptureRequest.SCALER_ROTATE_AND_CROP,
                    CaptureRequest.SCALER_ROTATE_AND_CROP_NONE,
                )
            if (
                builder.get(CaptureRequest.CONTROL_AF_MODE) ==
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE &&
                    c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.contains(
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    ) != true
            )
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            choice?.physicalId?.let { id ->
                for (key in physicalKeys) {
                    @Suppress("UNCHECKED_CAST") val k = key as CaptureRequest.Key<Any>
                    builder.get(k)?.let { builder.setPhysicalCameraKey(k, it, id) }
                }
            }
        }
        return builder.build()
    }

    private val previewCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                r: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val frameResult = physicalResult(result) ?: return
                if (nativeCamera) {
                    val ir = reader ?: return
                    val zoom =
                        if (Build.VERSION.SDK_INT >= 30)
                            frameResult[CaptureResult.CONTROL_ZOOM_RATIO]
                        else null
                    if (!lensGuard.zoomReady(zoom)) {
                        latestResult = null
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (zoomWaitSince == 0L) zoomWaitSince = now
                        if (now - zoomWaitSince > 3_000_000_000L)
                            fail(
                                "Android did not apply the selected ultrawide zoom. Reopen capture to retry, or start a new capture with the main camera."
                            )
                        return
                    }
                    zoomWaitSince = 0L
                    try {
                        lens = calibratedFrame(frameResult, Size(ir.width, ir.height))
                    } catch (e: Exception) {
                        latestResult = null
                        fail("Lens calibration failed: ${e.message}")
                        return
                    }
                }
                val now = SystemClock.elapsedRealtimeNanos()
                val focus = frameResult.get(CaptureResult.LENS_FOCUS_DISTANCE)
                val af = frameResult.get(CaptureResult.CONTROL_AF_STATE)
                val scanning =
                    af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                        af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                val moving =
                    frameResult.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING
                if (
                    moving ||
                        scanning ||
                        resultReceivedAt == 0L ||
                        now - resultReceivedAt > 500_000_000L ||
                        (focus != null &&
                            previousFocus != null &&
                            abs(focus - previousFocus!!) > .08f)
                ) {
                    focusStableSince = 0L
                } else if (focusStableSince == 0L) focusStableSince = now
                previousFocus = focus
                resultReceivedAt = now
                latestResult = frameResult
            }
        }

    private fun startPreview() {
        if (camera == null) return
        val request =
            newRequest(
                if (nativeCamera) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
            )
        previewSurfaces().forEach { request.addTarget(it) }
        request.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        request.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        captureSession?.setRepeatingRequest(finishRequest(request), previewCallback, handler)
    }

    private fun resumeAr() {
        try {
            synchronized(arLock) {
                if (closed || active) return
                ar?.resume()
                active = true
                ar?.sharedCamera?.setCaptureCallback(previewCallback, handler)
            }
            gate.reset()
        } catch (e: Exception) {
            fail("Could not resume tracking: ${e.message}")
        }
    }

    private fun capture(id: Int, q: Q, captureOffset: Q, lens: Lens) {
        if (closed) {
            capturing = false
            return
        }
        try {
            store.checkSpace(project.id, 150_000_000)
            val c = characteristics!!
            val result = latestResult ?: error("Camera exposure is not ready. Retry in a moment.")
            val range = control(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            val isoRange = control(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
            val aeIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            // Prefer a little sensor noise over long, smeared handheld exposures. Keep ISO
            // fixed inside a bracket; each frame records its actual gain for HDR merging.
            val previewTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val iso =
                ((previewTime.toDouble() * aeIso / 8_333_333L).roundToInt()).coerceIn(
                    isoRange.lower,
                    max(isoRange.lower, min(800, isoRange.upper)),
                )
            val base =
                ((result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L).toDouble() *
                        aeIso / iso)
                    .toLong()
            val times =
                Brackets.times(
                    base,
                    project.quality.bracketCount,
                    range.lower,
                    min(range.upper, 62_500_000L),
                )
            check(times.size >= 3) {
                "This scene exceeds the camera's bracket range. Aim at a mid-brightness area and retry."
            }
            // Freeze the preview's settled focus across the bracket. Jumping to infinity
            // here changed sharpness and magnification during indoor captures.
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - resultReceivedAt > 500_000_000L) {
                abort("Waiting for a fresh camera frame", automaticallyRetry = true)
                return
            }
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            val fallbackFocus =
                focusStableSince == 0L ||
                    af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            // Featureless sky must not trap capture in an endless AF hunt. A timed fallback
            // selects distant focus; a native preview preflight verifies the lens has arrived.
            val focus =
                (if (fallbackFocus) 0f else result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f)
                    .coerceIn(
                        0f,
                        c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    )
            val recentMotion = gyro.excursion(now - 150_000_000L, now - 20_000_000L)
            if (recentMotion != null && recentMotion > .5) {
                abort("Finish the turn · waiting for a clear exposure", automaticallyRetry = true)
                return
            }
            pending = Pending(id, q, captureOffset, lens, times.size, fallbackFocus)
            state.value =
                state.value.copy(
                    busy = true,
                    message = "Capturing HDR bracket",
                    detail = "Hold the phone still · ${times.size} exposures",
                    bracket = 0f,
                    dwell = 0f,
                )
            synchronized(arLock) {
                active = false
                ar?.pause()
            }
            captureSession?.stopRepeating()
            val requests =
                times.mapIndexed { index, time ->
                    newRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        .apply {
                            addTarget(reader!!.surface)
                            setTag(index)
                            set(
                                CaptureRequest.CONTROL_CAPTURE_INTENT,
                                CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
                            )
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, time)
                            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                            set(CaptureRequest.SENSOR_FRAME_DURATION, max(33_333_333L, time))
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                            // Fixed daylight balance preserves illuminant color across every
                            // direction.
                            val wb =
                                (control(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                                    ?: intArrayOf())
                            check(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT in wb) {
                                "The camera must support fixed daylight white balance for consistent HDR color."
                            }
                            set(
                                CaptureRequest.CONTROL_AWB_MODE,
                                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                            )
                            set(
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                            )
                            if (
                                (c.get(
                                        CameraCharacteristics
                                            .LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                                    ) ?: intArrayOf())
                                    .contains(0)
                            )
                                set(
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                                )
                            val modes =
                                (control(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                                    ?: intArrayOf())
                            check(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in modes) {
                                "The camera cannot keep a fixed tone curve for HDR reconstruction."
                            }
                            val curve = Radiance.captureToneCurve()
                            set(
                                CaptureRequest.TONEMAP_MODE,
                                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                            )
                            set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
                            if (Build.VERSION.SDK_INT >= 31)
                                set(
                                    CaptureRequest.SCALER_ROTATE_AND_CROP,
                                    CaptureRequest.SCALER_ROTATE_AND_CROP_NONE,
                                )
                            set(CaptureRequest.JPEG_ORIENTATION, 0)
                            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                            // Avoid costly still-template smoothing while retaining the ISP's
                            // normal demosaic and modest edge processing.
                            if (
                                c.get(
                                        CameraCharacteristics
                                            .NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
                                    )
                                    ?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true
                            )
                                set(
                                    CaptureRequest.NOISE_REDUCTION_MODE,
                                    CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                                )
                            if (
                                c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                                    ?.contains(CaptureRequest.EDGE_MODE_FAST) == true
                            )
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                        }
                        .let { finishRequest(it) }
                }
            settleLens(focus) {
                captureSession!!.captureBurst(
                    requests,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            s: CameraCaptureSession,
                            r: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val p = pending ?: return
                            val captured =
                                physicalResult(result)
                                    ?: return abort("Physical camera exposure metadata is missing.")
                            val timestamp =
                                captured.get(CaptureResult.SENSOR_TIMESTAMP)
                                    ?: return abort(
                                        "The camera did not report a frame timestamp. Retry this direction."
                                    )
                            val time =
                                captured.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                    ?: return abort(
                                        "The camera did not report exposure time. Retry this direction."
                                    )
                            val gain =
                                captured.get(CaptureResult.SENSOR_SENSITIVITY)
                                    ?: return abort(
                                        "The camera did not report ISO. Retry this direction."
                                    )
                            if (nativeCamera) {
                                try {
                                    val calibrated =
                                        calibratedFrame(captured, Size(p.lens.width, p.lens.height))
                                    check(
                                        abs(calibrated.fx / p.lens.fx - 1) < .02 &&
                                            abs(calibrated.fy / p.lens.fy - 1) < .02 &&
                                            abs(calibrated.cx - p.lens.cx) < p.lens.width * .01 &&
                                            abs(calibrated.cy - p.lens.cy) < p.lens.height * .01
                                    ) {
                                        "Lens crop changed during the bracket. Retry this direction."
                                    }
                                    p.lenses[timestamp] = calibrated
                                } catch (e: Exception) {
                                    return abort(e.message ?: "Lens calibration changed.")
                                }
                            }
                            p.results[timestamp] = Exposure("", time, gain, timestamp)
                            p.windows +=
                                timestamp..(timestamp +
                                        time +
                                        (captured.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
                                            ?: 0L))
                            finishIfReady()
                        }

                        override fun onCaptureFailed(
                            s: CameraCaptureSession,
                            r: CaptureRequest,
                            f: CaptureFailure,
                        ) {
                            abort(
                                "Exposure capture failed (${f.reason}). Hold steady and retry this direction."
                            )
                        }

                        override fun onCaptureSequenceAborted(
                            s: CameraCaptureSession,
                            sequenceId: Int,
                        ) {
                            abort("Capture was interrupted. Retry this direction.")
                        }
                    },
                    handler,
                )
            }
            val token = pending
            handler.postDelayed(
                {
                    if (pending === token && token != null)
                        abort("The camera timed out while saving exposures. Retry this direction.")
                },
                20_000,
            )
        } catch (e: Exception) {
            abort(e.message ?: "Could not capture this bracket. Retry this direction.")
        }
    }

    /** Confirm the physical lens and stabilization transition before exposing any JPEG. */
    private fun settleLens(focus: Float, beginBurst: () -> Unit) {
        val token = pending ?: return
        val start = SystemClock.elapsedRealtimeNanos()
        var stableFrames = 0
        var started = false
        state.value =
            state.value.copy(
                message = "Settling the lens",
                detail =
                    if (token.fallbackFocus) "Low detail · using distant focus · hold steady"
                    else "Confirming focus · hold steady",
            )
        val request =
            newRequest(
                    if (nativeCamera) CameraDevice.TEMPLATE_PREVIEW
                    else CameraDevice.TEMPLATE_RECORD
                )
                .apply {
                    previewSurfaces().forEach { addTarget(it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                    )
                    if (
                        characteristics
                            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                            ?.contains(0) == true
                    )
                        set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                        )
                }
                .let { finishRequest(it) }
        captureSession!!.setRepeatingRequest(
            request,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (closed || pending !== token || started) return
                    val metadata =
                        physicalResult(result) ?: return abort("Physical lens metadata is missing.")
                    val actual = metadata.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    val still =
                        metadata.get(CaptureResult.LENS_STATE) != CaptureResult.LENS_STATE_MOVING &&
                            (actual == null || abs(actual - focus) <= max(.08f, focus * .05f))
                    stableFrames = if (still) stableFrames + 1 else 0
                    if (
                        stableFrames < 2 ||
                            SystemClock.elapsedRealtimeNanos() - start < 100_000_000L
                    )
                        return
                    started = true
                    try {
                        val now = SystemClock.elapsedRealtimeNanos()
                        if ((gyro.excursion(now - 150_000_000L, now - 20_000_000L) ?: 0.0) > .5) {
                            abort(
                                "Finish the turn · waiting for a clear exposure",
                                automaticallyRetry = true,
                            )
                            return
                        }
                        session.stopRepeating()
                        state.value =
                            state.value.copy(
                                message = "Capturing HDR bracket",
                                detail = "${token.expected} short exposures · hold steady",
                            )
                        beginBurst()
                    } catch (e: Exception) {
                        abort("Camera could not finish focusing: ${e.message}")
                    }
                }
            },
            handler,
        )
        handler.postDelayed(
            {
                if (pending === token && !started)
                    abort(
                        "The lens did not settle. Point at a detailed area and retry this direction."
                    )
            },
            2_000,
        )
    }

    private fun finishIfReady() {
        val p = pending ?: return
        val matched = p.results.keys.intersect(p.files.keys)
        state.value =
            state.value.copy(
                bracket = matched.size.toFloat() / p.expected,
                detail = "Saving exposure ${matched.size} of ${p.expected} · hold steady",
            )
        if (matched.size < p.expected) return
        try {
            val exposures =
                matched
                    .map { t -> p.results.getValue(t).copy(file = p.files.getValue(t)) }
                    .sortedBy { it.seconds }
            val sensorClock =
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            // Never infer exposure motion from JPEG callback delivery time. Unknown clocks
            // produce a review finding, not an automatic rejection of otherwise valid photos.
            val excursion =
                if (sensorClock)
                    gyro.excursion(p.windows.minOf { it.first }, p.windows.maxOf { it.last })
                else null
            val blur =
                if (sensorClock)
                    p.windows.mapNotNull { gyro.excursion(it.first, it.last) }.maxOrNull()
                else null
            if ((excursion ?: 0.0) > 6.0 || (blur ?: 0.0) > .3) {
                abort(
                    "Movement during the exposure · automatically retrying this direction",
                    automaticallyRetry = true,
                )
                return
            }
            val findings = buildList {
                if (p.fallbackFocus)
                    add(
                        "Autofocus could not confirm detail; distant focus was used. Inspect nearby objects for softness."
                    )
                if (excursion == null)
                    add("Motion measurements were incomplete; inspect this direction for blur.")
                else if (excursion > 1.5 || (blur ?: 0.0) > .12)
                    add("Some movement occurred during capture; inspect alignment and sharpness.")
            }
            check(exposures.last().seconds / exposures.first().seconds >= 3.8) {
                "The camera did not produce distinct HDR exposures. Retry this direction."
            }
            exposures.forEach { e ->
                check(
                    File(store.dir(project.id), e.file + ".part")
                        .renameTo(File(store.dir(project.id), e.file))
                ) {
                    "Could not finish saving photos."
                }
            }
            val middle = exposures[exposures.size / 2]
            val measuredPose =
                if (sensorClock)
                    orientation
                        .at(
                            p.windows
                                .find { it.first == middle.timestamp }
                                ?.let { it.first + (it.last - it.first) / 2 }
                                ?: (middle.timestamp + middle.timeNs / 2)
                        )
                        ?.let {
                            (p.offset * it * InertialOrientation.cameraInDevice(sensorOrientation))
                                .normalized()
                        }
                else null
            // Translation is unmeasured. Zero is a placeholder, not an optical-centre constraint.
            val capture =
                Capture(
                    p.id,
                    measuredPose ?: p.q,
                    V3.ZERO,
                    p.lenses[middle.timestamp] ?: p.lens,
                    exposures,
                    findings,
                    "game_rotation_vector_handheld",
                )
            project =
                store.update(project.id) {
                    it.copy(
                        captures = it.captures.filter { old -> old.targetId != p.id } + capture,
                        error = null,
                    )
                }
            pending = null
            capturing = false
            notice = "Direction ${project.captures.size} saved"
            noticeUntil = SystemClock.elapsedRealtime() + 1800
            state.value = state.value.copy(busy = false, captured = project.captures.size)
            activity.runOnUiThread {
                activity.window.decorView.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                    else android.view.HapticFeedbackConstants.VIRTUAL_KEY
                )
            }
            startPreview()
            resumeAr()
        } catch (e: Exception) {
            abort("Could not finish saving the bracket: ${e.message}")
        }
    }

    private fun abort(message: String, automaticallyRetry: Boolean = false) {
        val p = pending
        pending = null
        p?.files?.values?.forEach { File(store.dir(project.id), it + ".part").delete() }
        capturing = false
        if (automaticallyRetry) {
            gate.reset()
            notice = message
            noticeUntil = SystemClock.elapsedRealtime() + 3500
            state.value = state.value.copy(busy = false, dwell = 0f, notice = message)
        } else fail(message)
        if (!closed)
            runCatching {
                startPreview()
                resumeAr()
            }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, e.values)
            orientation.add(
                e.timestamp,
                Q(q[1].toDouble(), q[2].toDouble(), q[3].toDouble(), q[0].toDouble()),
            )
            return
        }
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
        gyro.add(
            e.timestamp,
            V3(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Called before pausing/destroying the view; all camera cleanup runs on its callback thread.
     */
    fun close() {
        if (closed) return
        closed = true
        sensors.unregisterListener(this)
        motionThread.quitSafely()
        synchronized(arLock) {
            active = false
            runCatching { ar?.pause() }
        }
        handler.post {
            pending?.files?.values?.forEach { File(store.dir(project.id), it + ".part").delete() }
            pending = null
            runCatching { captureSession?.close() }
            if (camera != null) runCatching { camera?.close() }.onFailure { finishClose() }
            else finishClose()
        }
    }

    private fun finishClose() {
        runCatching { nativeSurface?.release() }
        runCatching { nativeTexture?.release() }
        nativeSurface = null
        nativeTexture = null
        runCatching { reader?.close() }
        reader = null
        synchronized(arLock) {
            runCatching { ar?.close() }
            ar = null
        }
        thread.quitSafely()
    }
}
