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
import app.hdri.ui.SphereTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        rule.onNodeWithText("Capture the light\naround you.").assertIsDisplayed()
        screenshot("setup.png")
        rule.onNodeWithText("Detailed environment").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Quick capture").assertExists()
        rule.onNodeWithText("Camera lens").performScrollTo().assertIsDisplayed()
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Allow camera access").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Lens details").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Lens details").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Lens details").performScrollTo().performClick()
            rule.onNodeWithText("Camera lens details").assertIsDisplayed()
            rule.onNodeWithText("Copy details").assertIsDisplayed()
            rule.onNodeWithText("Done").performClick()
        } else rule.onNodeWithText("Allow camera access").performScrollTo().assertIsDisplayed()

        rule.onNodeWithText("Fill below me").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("Most overlap").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText("OpenEXR · compressed").performScrollTo().assertIsSelected()
        rule.onNodeWithText("Start capture").performScrollTo().assertIsDisplayed()
        screenshot("quality.png")
    }

    @Test
    fun completedCaptureShowsLightingAndConfirmsSourceRemoval() {
        val store =
            app.hdri.data.SessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val initial =
            store.create(app.hdri.data.Quality.QUICK, masterFormat = app.hdri.data.MasterFormat.HDR)
        val dir = store.dir(initial.id)
        val bitmap =
            android.graphics.Bitmap.createBitmap(512, 256, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(72, 85, 65))
        File(dir, "preview.jpg").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        bitmap.recycle()
        app.hdri.core.HdrWriter(File(dir, "environment.hdr").outputStream(), 512, 256).use { writer
            ->
            repeat(256) { writer.row(FloatArray(512 * 3) { 1f }) }
        }
        File(dir, "source.jpg").writeBytes(ByteArray(2048))
        store.update(initial.id) {
            it.copy(
                name = "Storage test",
                state = "ready",
                captures =
                    listOf(
                        app.hdri.data.Capture(
                            0,
                            app.hdri.core.Q(),
                            app.hdri.core.V3.ZERO,
                            app.hdri.core.Lens(16, 8, 10.0, 10.0, 8.0, 4.0),
                            listOf(app.hdri.data.Exposure("source.jpg", 1000, 100, 0)),
                        )
                    ),
            )
        }
        try {
            rule.runOnUiThread {
                androidx.lifecycle
                    .ViewModelProvider(rule.activity)[AppViewModel::class.java]
                    .open(initial.id)
            }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Lighting spheres").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Lighting spheres").assertIsDisplayed()
            rule.onNodeWithText("Chrome + 18% grey · rotate and adjust exposure").assertExists()
            screenshot("capture-detail.png")
            rule.onNodeWithText("Lighting spheres").performClick()
            rule.onNodeWithText("Lighting preview").assertExists()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("Save OpenEXR (.exr)").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Remove source photos").performScrollTo().performClick()
            rule.onNodeWithText("Remove source photos?").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            assertTrue(File(dir, "source.jpg").exists())
            rule.onNodeWithText("Remove source photos").performClick()
            rule.onNodeWithText("Remove photos").performClick()
            rule.waitUntil(10_000) {
                rule
                    .onAllNodesWithText(
                        "Source photos removed. Viewing and exporting remain available; rebuilding is unavailable."
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule
                .onNodeWithText(
                    "Source photos removed. Viewing and exporting remain available; rebuilding is unavailable."
                )
                .performScrollTo()
                .assertIsDisplayed()
            screenshot("capture-storage.png")
            rule.onNodeWithText("Rebuild from saved photos").assertDoesNotExist()
            rule.onNodeWithText("Export original capture bundle").assertDoesNotExist()
            rule.onNodeWithText("Save OpenEXR (.exr)").performScrollTo().assertIsDisplayed()
            assertTrue(File(dir, "environment.hdr").exists())
        } finally {
            store.delete(initial.id)
        }
    }

    @Test
    fun captureGuidesBothAxesAndKeepsAutomaticShutterPrimary() {
        val state =
            mutableStateOf(
                CaptureUi(
                    message = "Turn right · tilt up",
                    detail = "Follow the arrow and bring the highlighted dot into the ring.",
                    route = app.hdri.core.RouteProgress("Horizon", 1, 5, 7, 12),
                    captured = 7,
                    total = 41,
                    ready = true,
                    guide = AimGuide(26f, 16f, 12f),
                    aimDegrees = 30f,
                    markers =
                        listOf(
                            Marker(8, .82f, .32f, false, true),
                            Marker(9, .22f, .25f, true, false),
                        ),
                )
            )
        var manualTaps = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                SphereTheme {
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
        rule.onNodeWithText("Horizon").assertIsDisplayed()
        rule.onNodeWithText("7/12 saved").assertIsDisplayed()
        rule.onNodeWithText("Stage 1 of 5 · finish this ring, then change tilt").assertIsDisplayed()
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
                    message = "Aligned · capturing automatically",
                    detail = "Small wobbles are okay. Let the ring fill.",
                    guide = AimGuide(2f, 1f, 2f),
                    aimDegrees = 2.2f,
                    aimLocked = true,
                    dwell = .68f,
                    manualReady = true,
                    markers = listOf(Marker(8, .52f, .49f, false, true)),
                )
        }
        rule.onNodeWithText("Aligned · capturing automatically").assertIsDisplayed()
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
