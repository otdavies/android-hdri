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
        HdrWriter(file.outputStream(), 128, 64).use { writer ->
            for (y in 0 until 64) writer.row(
                FloatArray(128 * 3) { i ->
                    if (!directional) floatArrayOf(5f, 3f, 2f)[i % 3]
                    else {
                        val light = AnalyticLight.sample(Sphere.ray(i / 3 + .5, y + .5, 128, 64))
                        floatArrayOf(light.z.toFloat(), light.y.toFloat(), light.x.toFloat())[i % 3]
                    }
                }
            )
        }
        return file
    }

    private fun pixels(view: SphereViewer): Bitmap {
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
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitUntil(15_000) { ready.get() || error.get() != null }
        assertNull(error.get())
        fun verify(multiplier: Double, linear: Boolean) {
            val expectedRed = run {
                val value = 2 * light.scale * multiplier
                val mapped = if (linear) value.coerceIn(0.0, 1.0) else value / (1 + value)
                ((if (mapped <= .0031308) mapped * 12.92 else 1.055 * mapped.pow(1 / 2.4) - .055) *
                        255)
                    .roundToInt()
            }
            // PixelCopy reads the last presented buffer. Wait for the requested exposure
            // to reach the surface instead of racing the GL thread's next buffer swap.
            rule.waitUntil(5_000) {
                val candidate = pixels(viewer)
                val matches =
                    abs(
                        Color.red(candidate.getPixel(candidate.width / 4, candidate.height / 2)) -
                            expectedRed
                    ) <= 3
                candidate.recycle()
                matches
            }
            val image = pixels(viewer)
            try {
                for ((x, reflectance) in listOf(.25 to 1.0, .75 to .18)) {
                    val pixel = image.getPixel((image.width * x).toInt(), image.height / 2)
                    for (c in 0..2) {
                        var value =
                            floatArrayOf(2f, 3f, 5f)[c] * light.scale * reflectance * multiplier
                        value = if (linear) value.coerceIn(0.0, 1.0) else value / (1 + value)
                        val srgb =
                            if (value <= .0031308) value * 12.92
                            else 1.055 * value.pow(1 / 2.4) - .055
                        val actual =
                            intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))[c]
                        assertEquals(
                            "Channel $c, reflectance $reflectance",
                            (srgb * 255).roundToInt().toDouble(),
                            actual.toDouble(),
                            3.0,
                        )
                    }
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
}
