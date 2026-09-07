package app.hdri

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.*
import app.hdri.data.*
import app.hdri.ui.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThumbnailTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun hdrAndExrChromeThumbnailsCacheRefreshAndAppearInCaptureRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        val p = store.create(Quality.QUICK, masterFormat = MasterFormat.EXR)
        val dir = store.dir(p.id)
        val artifacts = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val cache = File(context.cacheDir, "sphere-thumbnails")
        val bitmaps = mutableListOf<Bitmap>()
        val times = mutableListOf<Long>()
        fun row(y: Int, pixels: FloatArray) {
            for (x in 0 until 512) {
                val light = AnalyticLight.sample(Sphere.ray(x + .5, y + .5, 512, 256))
                pixels[x * 3] = light.z.toFloat()
                pixels[x * 3 + 1] = light.y.toFloat()
                pixels[x * 3 + 2] = light.x.toFloat()
            }
        }
        suspend fun load(project: Project): Bitmap {
            val start = System.nanoTime()
            var progress = 0f
            val result = ScanThumbnails.load(context, store, project) { progress = it }
            assertEquals(1f, progress, 0f)
            times.add((System.nanoTime() - start) / 1_000_000)
            return result.also { bitmaps.add(it) }
        }
        try {
            ExrWriter.write(store.masterFile(p), 512, 256, ::row, {}, {})
            val ready = store.update(p.id) { it.copy(state = "ready", name = "Studio environment") }
            val first = load(ready)
            val cached = load(ready)
            assertEquals(ReflectionThumbnail.SIZE, first.width)
            assertEquals(0, first.getPixel(0, 0))
            assertEquals(first.getPixel(96, 96), cached.getPixel(96, 96))
            assertTrue(first.hasAlpha())
            File(artifacts, "chrome-thumbnail.png").outputStream().use {
                first.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            HdrWriter(File(dir, "environment.hdr").outputStream().buffered(), 512, 256).use { writer
                ->
                val values = FloatArray(512 * 3)
                repeat(256) {
                    row(it, values)
                    writer.row(values)
                }
            }
            val hdr = load(ready.copy(masterFormat = MasterFormat.HDR))
            val before = first.getPixel(96, 96)
            val actual = hdr.getPixel(96, 96)
            for (shift in listOf(0, 8, 16)) assertTrue(
                kotlin.math.abs((before shr shift and 255) - (actual shr shift and 255)) <= 2
            )

            // A rebuild at the same path must invalidate a previously rendered cache entry.
            ExrWriter.write(
                store.masterFile(p),
                512,
                256,
                { _, values ->
                    values.indices.forEach { values[it] = if (it % 3 == 1) .4f else .02f }
                },
                {},
                {},
            )
            store.masterFile(p).setLastModified(System.currentTimeMillis() + 2000)
            val updated = load(ready)
            assertNotEquals(before, updated.getPixel(96, 96))
            assertEquals(1, cache.listFiles()!!.count { it.name.startsWith(p.id) })

            // Restore the representative light rig for a screenshot of the production row.
            ExrWriter.write(store.masterFile(p), 512, 256, ::row, {}, {})
            store.masterFile(p).setLastModified(System.currentTimeMillis() + 4000)
            rule.runOnUiThread {
                rule.activity.setContent {
                    SphereTheme {
                        Column(
                            Modifier.fillMaxSize().background(Ink).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text("Your captures")
                            CaptureRow(ready, store.storage(ready), store) {}
                        }
                    }
                }
            }
            rule.waitUntil(15000) {
                rule
                    .onAllNodesWithContentDescription(
                        "Chrome preview of Studio environment",
                        useUnmergedTree = true,
                    )
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule
                .onNodeWithContentDescription(
                    "Chrome preview of Studio environment",
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
            File(artifacts, "capture-list-thumbnail.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            File(artifacts, "thumbnail-verification.json")
                .writeText(
                    """{"size":192,"alpha":true,"exrHdrCompared":true,"cacheInvalidated":true,"loadMilliseconds":$times}"""
                )
        } finally {
            bitmaps.forEach { it.recycle() }
            store.delete(p.id)
            cache.listFiles()?.filter { it.name.startsWith(p.id) }?.forEach { it.delete() }
        }
    }
}
