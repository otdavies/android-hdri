package app.hdri

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.capture.CaptureUi
import app.hdri.capture.Marker
import app.hdri.core.AimGuide
import app.hdri.ui.CaptureOverlay
import app.hdri.ui.LumaTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        // PixelCopy waits for the Compose root's rendered frame; semantics can update
        // before a raw display screenshot, otherwise capturing the previous loading screen.
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun homeShowsCaptureAndOfflineSampleWithoutCameraPermission() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("New photosphere").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("New photosphere").assertIsDisplayed()
        rule.onNodeWithText("Explore a sample capture").assertExists()
        screenshot("home.png")
    }

    @Test
    fun setupExplainsCaptureAndOffersTwoQualityLevels() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("New photosphere").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("New photosphere").performClick()
        rule.onNodeWithText("Keep the lens\nin one place.").assertIsDisplayed()
        screenshot("setup.png")
        rule.onNodeWithText("Detailed environment").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Quick light study").assertExists()
        rule.onNodeWithText("Start capture").performScrollTo().assertIsDisplayed()
        screenshot("quality.png")
    }

    @Test
    fun captureGuidesBothAxesAndKeepsAutomaticShutterPrimary() {
        val state =
            mutableStateOf(
                CaptureUi(
                    message = "Turn right · tilt up",
                    detail = "Follow the glow and bring the next dot into the ring.",
                    captured = 7,
                    total = 41,
                    ready = true,
                    guide = AimGuide(26f, 16f, 12f),
                    aimDegrees = 30f,
                    markers =
                        listOf(
                            Marker(8, .82f, .32f, false, true),
                            Marker(9, .22f, .25f, false, false),
                        ),
                )
            )
        var manualTaps = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                LumaTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF46534A))) {
                        CaptureOverlay(state.value, {}, { manualTaps++ })
                    }
                }
            }
        }
        rule.onNodeWithText("AUTO CAPTURE").assertIsDisplayed()
        rule.onNodeWithText("GYRO GUIDANCE · ROTATE IN PLACE").assertIsDisplayed()
        rule.onNodeWithText("Turn right · tilt up").assertIsDisplayed()
        rule.onNodeWithText("Capture now").assertIsNotEnabled()
        screenshot("capture-guidance.png")
        rule.runOnUiThread {
            state.value = state.value.copy(guide = AimGuide(3.9f, 3.9f, 0f), aimDegrees = 5.5f)
        }
        rule.onNodeWithText("→ 4°").assertIsDisplayed()
        rule.onNodeWithText("↑ 4°").assertIsDisplayed()
        rule.onNodeWithText("Capture now").assertIsNotEnabled()
        rule.runOnUiThread {
            state.value =
                state.value.copy(
                    message = "Nice aim · capturing automatically",
                    detail = "Small wobbles are okay. Let the ring fill.",
                    guide = AimGuide(2f, 1f, 2f),
                    aimDegrees = 2.2f,
                    aimLocked = true,
                    dwell = .68f,
                    manualReady = true,
                    markers = listOf(Marker(8, .52f, .49f, false, true)),
                )
        }
        rule.onNodeWithText("Nice aim · capturing automatically").assertIsDisplayed()
        rule.onNodeWithText("Auto shutter is settling · 68%").assertIsDisplayed()
        rule.onNodeWithText("Capture now").assertIsEnabled().performClick()
        assertEquals(1, manualTaps)
        screenshot("capture-aligned.png")
        rule.runOnUiThread {
            state.value =
                state.value.copy(
                    ready = false,
                    manualReady = false,
                    guide = null,
                    message = "Waiting for motion sensors…",
                    aimLocked = false,
                    dwell = 0f,
                )
        }
        rule.onNodeWithText("Capture now").assertIsNotEnabled()
    }
}
