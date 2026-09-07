package app.hdri

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.capture.CameraBackdrop
import app.hdri.core.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private data class Case(
        val sensor: Int,
        val display: Int,
        val producer: Int,
        val revision: Int,
    )

    private data class Result(
        val revision: Int,
        val corners: List<Int>,
        val radiusX: Int,
        val radiusY: Int,
        val textureTransform: List<Float>,
    )

    private class Fixture(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
        val failure = AtomicReference<String?>(null)
        val result = AtomicReference<Result?>(null)
        @Volatile var case = Case(90, 0, 0, 0)
        @Volatile private var newFrame = false
        private val backdrop = CameraBackdrop()
        private var texture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var available = false
        private var w = 1
        private var h = 1
        private var bytes = ByteBuffer.allocateDirect(4)

        init {
            setEGLContextClientVersion(2)
            setRenderer(this)
            renderMode = RENDERMODE_WHEN_DIRTY
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                backdrop.create()
                val source = SurfaceTexture(backdrop.texture)
                texture = source
                source.setDefaultBufferSize(160, 120)
                source.setOnFrameAvailableListener(
                    {
                        newFrame = true
                        requestRender()
                    },
                    Handler(Looper.getMainLooper()),
                )
                val producer = Surface(source)
                surface = producer
                val canvas = producer.lockCanvas(null)
                val paint = Paint()
                // The raw sensor chart is R G / B Y, with a circular centre reference.
                for ((index, colour) in
                    listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                    paint.color = colour
                    val x = index % 2 * 80f
                    val y = index / 2 * 60f
                    canvas.drawRect(x, y, x + 80f, y + 60f, paint)
                }
                paint.color = Color.WHITE
                canvas.drawCircle(80f, 60f, 12f, paint)
                producer.unlockCanvasAndPost(canvas)
            } catch (e: Throwable) {
                failure.set(e.toString())
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            w = width
            h = height
            bytes = ByteBuffer.allocateDirect(w * h * 4)
            glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                if (newFrame) {
                    newFrame = false
                    texture!!.updateTexImage()
                    available = true
                }
                if (!available) return
                val state = case
                val original = FloatArray(16)
                texture!!.getTransformMatrix(original)
                // Simulate Camera2's buffer-queue orientation metadata while retaining the
                // real SurfaceTexture's crop/flip. Pixel data stays in raw sensor order.
                val rotation = FloatArray(16)
                Matrix.setIdentityM(rotation, 0)
                Matrix.translateM(rotation, 0, .5f, .5f, 0f)
                Matrix.rotateM(rotation, 0, state.producer * 90f, 0f, 0f, 1f)
                Matrix.translateM(rotation, 0, -.5f, -.5f, 0f)
                val transform = FloatArray(16)
                Matrix.multiplyMM(transform, 0, original, 0, rotation, 0)
                val cameraInDisplay =
                    InertialOrientation.displayInDevice(state.display).inverse() *
                        InertialOrientation.cameraInDevice(state.sensor)
                val lens = Lens(160, 120, 100.0, 100.0, 80.0, 60.0)
                backdrop.drawNative(
                    CameraGeometry.previewCoordinates(cameraInDisplay, lens, w, h),
                    transform,
                )
                bytes.clear()
                glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
                check(glGetError() == GL_NO_ERROR) { "External camera texture rendering failed" }
                fun pixel(x: Int, y: Int): Int {
                    val i = ((h - 1 - y) * w + x) * 4
                    return Color.rgb(
                        bytes.get(i).toInt() and 255,
                        bytes.get(i + 1).toInt() and 255,
                        bytes.get(i + 2).toInt() and 255,
                    )
                }
                fun white(x: Int, y: Int): Boolean {
                    val c = pixel(x, y)
                    return minOf(Color.red(c), Color.green(c), Color.blue(c)) > 245
                }
                var rx = 0
                var ry = 0
                while (w / 2 + rx < w && white(w / 2 + rx, h / 2)) rx++
                while (h / 2 + ry < h && white(w / 2, h / 2 + ry)) ry++
                if (state.sensor == 90 && state.display == 0 && state.producer == 1) {
                    val image =
                        android.graphics.Bitmap.createBitmap(
                            IntArray(w * h) { pixel(it % w, it / w) },
                            w,
                            h,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    val folder =
                        File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
                    File(folder, "native-preview-portrait.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
                result.set(
                    Result(
                        state.revision,
                        listOf(
                            pixel(w / 4, h / 4),
                            pixel(w * 3 / 4, h / 4),
                            pixel(w / 4, h * 3 / 4),
                            pixel(w * 3 / 4, h * 3 / 4),
                        ),
                        rx,
                        ry,
                        original.toList(),
                    )
                )
            } catch (e: Throwable) {
                failure.set(e.toString())
            }
        }

        fun close() {
            val finished = CountDownLatch(1)
            queueEvent {
                surface?.release()
                texture?.release()
                finished.countDown()
            }
            check(finished.await(5, TimeUnit.SECONDS))
            onPause()
        }
    }

    @Test
    fun cameraTextureStaysUprightAndRoundAcrossSensorDisplayAndProducerRotations() {
        lateinit var fixture: Fixture
        rule.runOnUiThread {
            rule.activity.setContent {
                AndroidView(
                    factory = { Fixture(it).also { fixture = it } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        val golden =
            listOf(
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            )
        val records = org.json.JSONArray()
        var revision = 1
        var passed = false
        try {
            for (sensor in listOf(0, 90, 180, 270)) for (display in
                listOf(0, 90, 180, 270)) for (producer in 0..3) {
                val state = Case(sensor, display, producer, revision++)
                rule.runOnUiThread {
                    fixture.case = state
                    fixture.requestRender()
                }
                rule.waitUntil(15_000) {
                    fixture.result.get()?.revision == state.revision ||
                        fixture.failure.get() != null
                }
                assertNull(fixture.failure.get())
                val frame = checkNotNull(fixture.result.get())
                records.put(
                    org.json
                        .JSONObject()
                        .put("sensor", sensor)
                        .put("display", display)
                        .put("producer", producer)
                        .put("radiusX", frame.radiusX)
                        .put("radiusY", frame.radiusY)
                        .put("corners", org.json.JSONArray(frame.corners))
                        .put("textureTransform", org.json.JSONArray(frame.textureTransform))
                )
                assertEquals(
                    "sensor=$sensor display=$display producer=$producer",
                    golden[(sensor + display) % 360 / 90],
                    frame.corners,
                )
                assertTrue("Circle lost", frame.radiusX > 5 && frame.radiusY > 5)
                assertTrue(
                    "Preview stretched: ${frame.radiusX} by ${frame.radiusY}",
                    abs(frame.radiusX - frame.radiusY) <= 4,
                )
            }
            passed = true
        } finally {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "verification")
                .apply { mkdirs() }
                .let {
                    File(it, "native-preview-gpu.json")
                        .writeText(
                            org.json
                                .JSONObject()
                                .put("cases", records)
                                .put("allCornerOrientationsCorrect", passed)
                                .put("allAspectRatiosCorrect", passed)
                                .toString(2)
                        )
                }
            fixture.close()
        }
    }
}
