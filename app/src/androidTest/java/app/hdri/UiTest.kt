package app.hdri

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
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
}
