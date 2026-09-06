package app.hdri.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.*
import android.hardware.camera2.*
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.*
import app.hdri.core.*
import app.hdri.data.*
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
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
    val message: String = "Starting AR camera…",
    val detail: String = "Camera and motion tracking",
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
    val turn: Float = 0f,
)

/** ARCore owns tracking; a paused shared session hands manual bracket capture to Camera2. */
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
    private val arLock = Any()
    private val backdrop = CameraBackdrop()
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val manager = activity.getSystemService(CameraManager::class.java)
    private var ar: Session? = null
    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var capturing = false
    @Volatile private var opened = false
    @Volatile private var latestResult: TotalCaptureResult? = null
    @Volatile private var latestQ = Q()
    @Volatile private var latestPosition = V3.ZERO
    @Volatile private var lens: Lens? = null
    @Volatile private var offset: Q? = null
    private var origin: V3? = null
    private var width = 1
    private var height = 1
    private var lastUi = 0L
    private var lastFrameTimestamp = 0L
    private var selected = -1
    private val gate = SteadyGate()
    private var pending: Pending? = null
    private var gyroTimestamp = 0L
    @Volatile private var motionDegrees = 0.0
    private var fixedIso: Int? = null
    @Volatile private var errorLatched = false

    private data class Pending(
        val id: Int,
        val q: Q,
        val position: V3,
        val lens: Lens,
        val expected: Int,
        val results: MutableMap<Long, Exposure> = mutableMapOf(),
        val files: MutableMap<Long, String> = mutableMapOf(),
        val started: Long = SystemClock.elapsedRealtime(),
    )

    init {
        try {
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
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        } catch (e: Exception) {
            fail(
                "AR camera is unavailable. Update Google Play Services for AR, then reopen capture. ${e.message.orEmpty()}"
            )
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (closed || ar == null) return
        try {
            backdrop.create()
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
            val session = ar ?: return
            if (!active) {
                if (backdrop.texture != 0) backdrop.draw(null)
                return
            }
            try {
                @Suppress("DEPRECATION")
                val rotation = activity.windowManager.defaultDisplay.rotation
                session.setDisplayGeometry(rotation, width, height)
                val frame = session.update()
                backdrop.draw(frame)
                if (frame.timestamp == 0L || frame.timestamp == lastFrameTimestamp) return
                lastFrameTimestamp = frame.timestamp
                val c = frame.camera
                val tracked = c.trackingState == TrackingState.TRACKING
                if (!tracked) {
                    gate.reset()
                    emit(
                        CaptureUi(
                            "Finding your position…",
                            "Move slowly and point at textured surfaces. ${c.trackingFailureReason.name.lowercase().replace('_',' ')}",
                            project.captures.size,
                            project.targets.size,
                            needsAnchor = state.value.needsAnchor,
                        )
                    )
                    return
                }
                val r = c.pose.rotationQuaternion
                latestQ =
                    Q(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble())
                        .normalized()
                val p = c.pose.translation
                latestPosition = V3(p[0].toDouble(), p[1].toDouble(), p[2].toDouble())
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
                if (offset == null && project.captures.isEmpty()) {
                    val fw = latestQ.rotate(V3.FORWARD)
                    offset = Q.axis(V3(0.0, atan2(fw.x, -fw.z), 0.0))
                    origin = latestPosition
                }
                if (project.targets.isEmpty() && lens != null) {
                    val targets = Sphere.targets(min(lens!!.fovX, lens!!.fovY))
                    project = project.copy(targets = targets)
                    handler.post {
                        runCatching { store.update(project.id) { it.copy(targets = targets) } }
                            .onFailure { fail("Could not save capture plan: ${it.message}") }
                    }
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
                val next =
                    project.targets.filter { it.id !in done }.minByOrNull { it.ray.angle(forward) }
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
                    gate.reset()
                }
                val angle = forward.angle(next.ray)
                val shift = (latestPosition - (origin ?: latestPosition)).length()
                val dwell =
                    gate.update(SystemClock.elapsedRealtimeNanos(), q, angle < 2.5, true, shift)
                val display = c.displayOrientedPose.rotationQuaternion
                val dq =
                    (offset ?: Q()) *
                        Q(
                            display[0].toDouble(),
                            display[1].toDouble(),
                            display[2].toDouble(),
                            display[3].toDouble(),
                        )
                val proj = FloatArray(16)
                c.getProjectionMatrix(proj, 0, .1f, 100f)
                val markers =
                    project.targets.mapNotNull { t ->
                        val v = dq.inverse().rotate(t.ray)
                        if (v.z >= -.05) return@mapNotNull null
                        val nx = (proj[0] * v.x + proj[8] * v.z) / -v.z
                        val ny = (proj[5] * v.y + proj[9] * v.z) / -v.z
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
                val direction = dq.inverse().rotate(next.ray)
                val msg =
                    when {
                        shift > .12 -> "Return to your starting point"
                        angle < 2.5 -> "Hold steady"
                        next.pitch > 65 -> "Look up"
                        next.pitch < -65 -> "Look down"
                        else -> "Bring a dot into the ring"
                    }
                emit(
                    CaptureUi(
                        msg,
                        if (shift > .12)
                            "Camera moved ${(shift*100).roundToInt()} cm · pivot around the lens"
                        else
                            "${done.size+1} of ${project.targets.size} directions · keep the lens in one place",
                        done.size,
                        project.targets.size,
                        markers,
                        dwell.toFloat(),
                        ready = true,
                        turn = atan2(direction.x, -direction.z).toFloat(),
                    )
                )
                if (dwell >= 1.0) {
                    capturing = true
                    motionDegrees = 0.0
                    handler.post { capture(next.id, q, latestPosition, lens!!) }
                }
            } catch (e: Exception) {
                fail(
                    "Tracking stopped: ${e.message.orEmpty()}. Leave capture and reopen to recover your saved photos."
                )
            }
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
            offset = (project.captures.first().rotation * latestQ.inverse()).normalized()
            origin = latestPosition
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

    private fun fail(message: String) {
        errorLatched = true
        gate.reset()
        state.value = state.value.copy(error = message, busy = false, dwell = 0f)
    }

    private fun openCamera() {
        if (closed) return
        try {
            val session = ar ?: return
            val id = session.cameraConfig.cameraId
            val c = manager.getCameraCharacteristics(id)
            characteristics = c
            val caps = (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
            check(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps) {
                "This camera cannot capture manual exposure brackets."
            }
            val rawSize = session.cameraConfig.imageSize
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
            session.sharedCamera.setAppSurfaces(id, listOf(reader!!.surface))
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
                session.sharedCamera.createARDeviceStateCallback(callback, handler),
                handler,
            )
        } catch (e: Exception) {
            fail("Could not open the HDR camera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun configure() {
        try {
            val session = ar ?: return
            val device = camera ?: return
            val surfaces = session.sharedCamera.arCoreSurfaces.toMutableList()
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
                            "The phone rejected the shared AR/still-camera streams. Your saved captures are safe."
                        )
                    }
                }
            device.createCaptureSession(
                surfaces,
                session.sharedCamera.createARSessionStateCallback(callback, handler),
                handler,
            )
        } catch (e: Exception) {
            fail("Camera configuration failed: ${e.message}")
        }
    }

    private val previewCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                r: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                latestResult = result
            }
        }

    private fun startPreview() {
        val session = ar ?: return
        val request = camera?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD) ?: return
        session.sharedCamera.arCoreSurfaces.forEach { request.addTarget(it) }
        request.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )
        request.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
        captureSession?.setRepeatingRequest(request.build(), previewCallback, handler)
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

    private fun capture(id: Int, q: Q, position: V3, lens: Lens) {
        if (closed) {
            capturing = false
            return
        }
        try {
            store.checkSpace(project.id, 150_000_000)
            val c = characteristics!!
            val result = latestResult ?: error("Camera exposure is not ready. Retry in a moment.")
            val range = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
            val aeIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val iso =
                fixedIso
                    ?: aeIso.coerceIn(isoRange.lower, min(400, isoRange.upper)).also {
                        fixedIso = it
                    }
            val base =
                ((result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L).toDouble() *
                        aeIso / iso)
                    .toLong()
            val times =
                Brackets.times(
                    base,
                    project.quality.bracketCount,
                    range.lower,
                    min(range.upper, 250_000_000L),
                )
            check(times.size >= 3) {
                "This scene exceeds the camera's bracket range. Aim at a mid-brightness area and retry."
            }
            pending = Pending(id, q, position, lens, times.size)
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
                    camera!!
                        .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
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
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                            // Fixed daylight balance preserves illuminant color across every
                            // direction.
                            val wb =
                                (c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
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
                                (c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                                    ?: intArrayOf())
                            check(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in modes) {
                                "The camera cannot keep a fixed tone curve for HDR reconstruction."
                            }
                            val curve =
                                FloatArray(32).also { a ->
                                    for (k in 0..15) {
                                        val x = k / 15f
                                        a[k * 2] = x
                                        a[k * 2 + 1] =
                                            if (x <= .0031308f) 12.92f * x
                                            else
                                                (1.055 * x.toDouble().pow(1 / 2.4) - .055).toFloat()
                                    }
                                }
                            set(
                                CaptureRequest.TONEMAP_MODE,
                                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
                            )
                            set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
                            set(CaptureRequest.JPEG_ORIENTATION, 0)
                            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                        }
                        .build()
                }
            captureSession!!.captureBurst(
                requests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val p = pending ?: return
                        val timestamp =
                            result.get(CaptureResult.SENSOR_TIMESTAMP)
                                ?: return abort(
                                    "The camera did not report a frame timestamp. Retry this direction."
                                )
                        val time =
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                ?: return abort(
                                    "The camera did not report exposure time. Retry this direction."
                                )
                        val gain =
                            result.get(CaptureResult.SENSOR_SENSITIVITY)
                                ?: return abort(
                                    "The camera did not report ISO. Retry this direction."
                                )
                        p.results[timestamp] = Exposure("", time, gain, timestamp)
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

    private fun finishIfReady() {
        val p = pending ?: return
        val matched = p.results.keys.intersect(p.files.keys)
        state.value =
            state.value.copy(
                bracket = matched.size.toFloat() / p.expected,
                detail = "Saving exposure ${matched.size} of ${p.expected} · hold steady",
            )
        if (matched.size < p.expected) return
        if (motionDegrees > 1.2) {
            abort(
                "The phone moved during the exposures. Hold the lens still, then retry this direction."
            )
            return
        }
        try {
            val exposures =
                matched
                    .map { t -> p.results.getValue(t).copy(file = p.files.getValue(t)) }
                    .sortedBy { it.seconds }
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
            val capture = Capture(p.id, p.q, p.position, p.lens, exposures)
            project =
                store.update(project.id) {
                    it.copy(
                        captures = it.captures.filter { old -> old.targetId != p.id } + capture,
                        error = null,
                    )
                }
            pending = null
            capturing = false
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

    private fun abort(message: String) {
        val p = pending
        pending = null
        p?.files?.values?.forEach { File(store.dir(project.id), it + ".part").delete() }
        capturing = false
        fail(message)
        if (!closed)
            runCatching {
                startPreview()
                resumeAr()
            }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (capturing && gyroTimestamp != 0L) {
            val dt = (e.timestamp - gyroTimestamp) / 1e9
            if (dt in 0.0..0.2)
                motionDegrees += Math.toDegrees(sqrt(e.values.sumOf { it.toDouble() * it }) * dt)
        }
        gyroTimestamp = e.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Called before pausing/destroying the view; all camera cleanup runs on its callback thread.
     */
    fun close() {
        if (closed) return
        closed = true
        sensors.unregisterListener(this)
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
        runCatching { reader?.close() }
        reader = null
        synchronized(arLock) {
            runCatching { ar?.close() }
            ar = null
        }
        thread.quitSafely()
    }
}
