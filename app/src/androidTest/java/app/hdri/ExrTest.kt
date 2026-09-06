package app.hdri

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.ExrWriter
import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CI decodes these files from the installed APK with the independent OpenEXR library. */
@RunWith(AndroidJUnit4::class)
class ExrTest {
    @Test
    fun exportsFloatChannelsWithZipAndPartialFinalBlock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        val progress = mutableListOf<Float>()
        ExrWriter.write(
            File(directory, "float-test.exr"),
            37,
            19,
            { y, row ->
                repeat(37) { x ->
                    row[x * 3] = (x + y * 37) / 1024f
                    row[x * 3 + 1] = if (x == 0) 100_000f else .18f
                    row[x * 3 + 2] = if (y == 0) 0f else 1e-8f
                }
            },
            { progress += it },
            {},
        )
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { it.first <= it.second })
        // Uncompressible pixels exercise the OpenEXR raw-block fallback, width 1,
        // negative values, and full float mantissas as well as positive HDR values.
        val random = Random(718)
        val reference = File(directory, "random-test.f32")
        reference.outputStream().use { out ->
            ExrWriter.write(
                File(directory, "random-test.exr"),
                1,
                17,
                { _, row ->
                    for (c in 0..2) {
                        val bits =
                            random.nextInt().let {
                                if ((it and 0x7f800000) == 0x7f800000) it xor 0x00800000 else it
                            }
                        row[c] = Float.fromBits(bits)
                        repeat(4) { out.write(bits ushr (it * 8)) }
                    }
                },
                {},
                {},
            )
        }
    }
}
