package app.hdri

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.*
import app.hdri.ui.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LightingViewerTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val verification
        get() = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }

    private fun fixture(name: String, directional: Boolean): File {
        val file = File(context.cacheDir, name)
        val width = if (directional) 4096 else 128
        val height = width / 2
        HdrWriter(file.outputStream(), width, height).use { writer ->
            for (y in 0 until height) writer.row(
                FloatArray(width * 3) { i ->
                    if (!directional) floatArrayOf(5f, 3f, 2f)[i % 3]
                    else {
                        val light =
                            AnalyticLight.sample(Sphere.ray(i / 3 + .5, y + .5, width, height))
                        floatArrayOf(light.z.toFloat(), light.y.toFloat(), light.x.toFloat())[i % 3]
                    }
                }
            )
        }
        return file
    }

    private fun pixels(view: SphereViewer): Bitmap? {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(
            view,
            image,
            {
                result = it
                done.countDown()
            },
            Handler(Looper.getMainLooper()),
        )
        assertTrue(done.await(10, TimeUnit.SECONDS))
        if (result == PixelCopy.ERROR_SOURCE_NO_DATA || result == PixelCopy.ERROR_TIMEOUT) {
            image.recycle()
            return null
        }
        assertEquals(PixelCopy.SUCCESS, result)
        return image
    }

    @Test
    fun actualGpuPreservesHdrChannelsAndPhysicalGreyReflectance() {
        val file = fixture("lighting-uniform.hdr", false)
        val light = LightingEnvironment.load(file) { _, _ -> }
        val ready = AtomicBoolean(false)
        val error = AtomicReference<String?>(null)
        lateinit var viewer: SphereViewer
        rule.runOnUiThread {
            rule.activity.setContent {
                AndroidView(
                    factory = {
                        SphereViewer(it, light, { ready.set(true) }, { error.set(it) }).also {
                            viewer = it
                            it.linear = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitUntil(15_000) { ready.get() || error.get() != null }
        assertNull(error.get())
        fun verify(multiplier: Double, linear: Boolean) {
            fun expected(reflectance: Double, channel: Int): Int {
                val value =
                    floatArrayOf(2f, 3f, 5f)[channel] * light.scale * reflectance * multiplier
                val peak = 5 * light.scale * reflectance * multiplier
                val factor =
                    if (peak <= .25) 1.0 else (.25 + .75 * (peak - .25) / (peak + .5)) / peak
                val mapped = if (linear) value.coerceIn(0.0, 1.0) else value * factor
                val srgb =
                    if (mapped <= .0031308) mapped * 12.92 else 1.055 * mapped.pow(1 / 2.4) - .055
                return (srgb * 255).roundToInt()
            }
            fun channels(pixel: Int) =
                intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            val probes = listOf(.25 to 1.0, .75 to .18)
            var presented: Bitmap? = null
            // A chrome channel under the shoulder can equal that same channel at a
            // different linear exposure. It cannot identify which frame was presented.
            // Wait for both probes and every channel, then assert that SAME buffer.
            rule.waitUntil(5_000) {
                val candidate = pixels(viewer) ?: return@waitUntil false
                val matches =
                    probes.all { (x, reflectance) ->
                        val values =
                            channels(
                                candidate.getPixel(
                                    (candidate.width * x).toInt(),
                                    candidate.height / 2,
                                )
                            )
                        (0..2).all { abs(values[it] - expected(reflectance, it)) <= 3 }
                    }
                if (matches) presented = candidate else candidate.recycle()
                matches
            }
            val image =
                checkNotNull(presented) { "The requested lighting frame was not presented." }
            try {
                for ((x, reflectance) in probes) {
                    val values =
                        channels(image.getPixel((image.width * x).toInt(), image.height / 2))
                    for (c in 0..2) assertEquals(
                        "Channel $c, reflectance $reflectance",
                        expected(reflectance, c).toDouble(),
                        values[c].toDouble(),
                        3.0,
                    )
                }
                File(verification, if (linear) "lighting-linear.png" else "lighting-probes.png")
                    .outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                image.recycle()
            }
        }
        verify(1.0, false)
        rule.runOnUiThread {
            viewer.exposure = -1f
            viewer.linear = true
            viewer.requestRender()
        }
        verify(.5, true)
        // Force texture and shader reconstruction, as after a lost EGL context.
        ready.set(false)
        rule.runOnUiThread {
            viewer.preserveEGLContextOnPause = false
            viewer.onPause()
            viewer.onResume()
        }
        rule.waitUntil(10_000) { ready.get() || error.get() != null }
        assertNull(error.get())
        verify(.5, true)
        file.delete()
    }

    @Test
    fun directionalHdrProducesTheCorrectDiffuseGradientOnGpu() {
        val w = 128
        val h = 64
        val rgb =
            FloatArray(w * h * 3) { index ->
                val y = index / 3 / w
                (1.0 + .9 * Sphere.ray(index / 3 % w + .5, y + .5, w, h).y).toFloat()
            }
        val map = LightingMap(w, h, rgb)
        val diffuse = map.diffuse()
        // For L(w)=1+0.9*w.y, Lambertian unit-reflectance radiance is 1+0.6*n.y.
        assertEquals(1.6, diffuse.rgb[0].toDouble(), .01)
        val light = LightingEnvironment(map, diffuse, 1f)
        val ready = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        lateinit var viewer: SphereViewer
        rule.runOnUiThread {
            rule.activity.setContent {
                AndroidView(
                    factory = {
                        SphereViewer(it, light, { ready.set(true) }, { failure.set(it) }).also {
                            viewer = it
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitUntil(15_000) { ready.get() || failure.get() != null }
        assertNull(failure.get())
        var image: Bitmap? = null
        rule.waitUntil(5_000) {
            image = pixels(viewer)
            image != null
        }
        val frame = checkNotNull(image)
        try {
            val radius = min(viewer.width * .41, viewer.height * .78) / 2
            val measurements = org.json.JSONArray()
            val checks = mutableListOf<Pair<Int, Int>>()
            for (ny in listOf(-.6, 0.0, .6)) {
                val pixel =
                    frame.getPixel(
                        frame.width * 3 / 4,
                        (frame.height / 2 - ny * radius).roundToInt(),
                    )
                val outgoing = .18 * (1 + .6 * ny)
                val mapped = outgoing
                val expected = (255 * (1.055 * mapped.pow(1 / 2.4) - .055)).roundToInt()
                measurements.put(
                    org.json
                        .JSONObject()
                        .put("normalY", ny)
                        .put("expectedRed", expected)
                        .put("renderedRed", Color.red(pixel))
                )
                checks += expected to Color.red(pixel)
            }
            val rotation = FloatArray(9)
            val query = CountDownLatch(1)
            viewer.queueEvent {
                val program = IntArray(1)
                android.opengl.GLES30.glGetIntegerv(
                    android.opengl.GLES30.GL_CURRENT_PROGRAM,
                    program,
                    0,
                )
                android.opengl.GLES30.glGetUniformfv(
                    program[0],
                    android.opengl.GLES30.glGetUniformLocation(program[0], "rot"),
                    rotation,
                    0,
                )
                query.countDown()
            }
            assertTrue(query.await(5, TimeUnit.SECONDS))
            File(verification, "lighting-gpu.json")
                .writeText(
                    org.json
                        .JSONObject()
                        .put("diffuseGradient", measurements)
                        .put("rotation", org.json.JSONArray(rotation.toList()))
                        .put("cpuDiffuseNorth", diffuse.rgb[0])
                        .put("cpuDiffuseSouth", diffuse.rgb[diffuse.rgb.size - 3])
                        .toString(2)
                )
            checks.forEach { (expected, actual) ->
                assertEquals(
                    "Directional diffuse lighting",
                    expected.toDouble(),
                    actual.toDouble(),
                    3.0,
                )
            }
            File(verification, "lighting-gradient.png").outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            frame.recycle()
        }
    }

    @Test
    fun lightingScreenLoadsHdrAndOffersExposureAndPanoramaControls() {
        val file = fixture("lighting-directional.hdr", true)
        rule.runOnUiThread {
            rule.activity.setContent { SphereTheme { ViewerScreen(file, "Studio light study") {} } }
        }
        rule.waitUntil(20_000) {
            runCatching {
                    rule
                        .onNodeWithContentDescription("Increase exposure one stop")
                        .assertIsEnabled()
                }
                .isSuccess
        }
        rule.onNodeWithText("Grey · 18% diffuse").assertIsDisplayed()
        rule
            .onNodeWithContentDescription("Increase exposure one stop")
            .assertIsEnabled()
            .performClick()
        rule.onNodeWithText("+1.0 EV").assertIsDisplayed()
        rule.onNodeWithText("Explore HDR").performClick()
        rule.onNodeWithContentDescription("Zoom in").performScrollTo().performClick()
        rule.onNodeWithText("1.3×").assertIsDisplayed()
        rule.onNodeWithContentDescription("Zoom out").performClick()
        rule.onNodeWithText("1.0×").assertIsDisplayed()
        rule.onNodeWithText("Lighting spheres").performClick()
        rule.onNodeWithContentDescription("Reset view and exposure").performClick()
        rule.onNodeWithText("+0.0 EV").assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(verification, "lighting-screen.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        file.delete()
    }

    @Test
    fun sceneOneTimesDisplaysLargeRadianceWithoutWhiteoutAndGreyKeepsPhysicalReflectance() {
        val file = File(context.cacheDir, "lighting-large-values.hdr")
        HdrWriter(file.outputStream(), 128, 64).use { writer ->
            repeat(64) { writer.row(FloatArray(128 * 3) { 500f }) }
        }
        rule.runOnUiThread {
            rule.activity.setContent {
                SphereTheme { ViewerScreen(file, "High radiance regression") {} }
            }
        }
        rule.waitUntil(20_000) {
            runCatching {
                    rule
                        .onNodeWithContentDescription("Increase exposure one stop")
                        .assertIsEnabled()
                }
                .isSuccess
        }
        fun find(view: android.view.View): SphereViewer? {
            if (view is SphereViewer) return view
            if (view is android.view.ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i))?.let {
                    return it
                }
            return null
        }
        lateinit var viewer: SphereViewer
        rule.runOnUiThread { viewer = checkNotNull(find(rule.activity.window.decorView)) }
        fun centre(x: Double, expected: Int) {
            rule.waitUntil(5_000) {
                val image = pixels(viewer) ?: return@waitUntil false
                val value = Color.red(image.getPixel((image.width * x).toInt(), image.height / 2))
                image.recycle()
                abs(value - expected) <= 3
            }
        }
        // Only a quarter-stop viewing trim affects neutral grey (118 -> 108 sRGB).
        // The shoulder lowers the mirror from clipped white to about 200 without changing rho.
        centre(.75, 108)
        centre(.25, 200)
        rule.onNodeWithText("Explore HDR").performClick()
        rule.onNodeWithText("Scene 1×").performScrollTo().performClick().assertIsSelected()
        centre(.5, 118)
        rule.onNodeWithText("Lighting spheres").performClick()
        centre(.25, 118)
        centre(.75, 50) // .18 * .18 linear, with exactly the same exposure as the mirror.
        val image = checkNotNull(pixels(viewer))
        File(verification, "lighting-scene-reference.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        file.delete()
    }

    @Test
    fun zoomChangesGpuFieldOfViewAndClampsInvalidRequests() {
        val w = 256
        val h = 128
        val map =
            LightingMap(
                w,
                h,
                FloatArray(w * h * 3) { i ->
                    (.35 + .25 * Sphere.ray(i / 3 % w + .5, i / 3 / w + .5, w, h).x).toFloat()
                },
            )
        val light = LightingEnvironment(map, map.diffuse(), 1f)
        val ready = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        lateinit var viewer: SphereViewer
        rule.runOnUiThread {
            rule.activity.setContent {
                AndroidView(
                    factory = {
                        SphereViewer(it, light, { ready.set(true) }, { failure.set(it) }).also { v
                            ->
                            viewer = v
                            v.probes = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitUntil(15000) { ready.get() || failure.get() != null }
        assertNull(failure.get())
        fun verify(zoom: Float) {
            rule.runOnUiThread { viewer.setViewZoom(zoom) }
            val px = (viewer.width * .85).toInt()
            val nx = ((px + .5) / viewer.width * 2 - 1) * viewer.width / viewer.height * .7 / zoom
            val value = .35 + .25 * nx / sqrt(1 + nx * nx)
            val expected = (Radiance.srgb(value) * 255).roundToInt()
            rule.waitUntil(5000) {
                val image = pixels(viewer) ?: return@waitUntil false
                val actual = Color.red(image.getPixel(px, image.height / 2))
                image.recycle()
                abs(expected - actual) <= 3
            }
        }
        verify(.5f)
        verify(1f)
        verify(4f)
        rule.runOnUiThread {
            viewer.setViewZoom(100f)
            assertEquals(4f, viewer.zoomFactor, 0f)
            viewer.setViewZoom(Float.NaN)
            assertEquals(4f, viewer.zoomFactor, 0f)
            viewer.reset()
            assertEquals(1f, viewer.zoomFactor, 0f)
        }
    }
}
