package app.hdri

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hdri.core.*
import app.hdri.data.*
import app.hdri.processing.ExrExport
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val store
        get() = SessionStore(context)

    private fun fixture(): Project {
        val p = store.create(Quality.QUICK, masterFormat = MasterFormat.HDR)
        val dir = store.dir(p.id)
        val exposures =
            (0..2).map { i ->
                File(dir, "test-$i.jpg").writeBytes(ByteArray(100 + i) { i.toByte() })
                Exposure("test-$i.jpg", 1_000_000, 100, i.toLong())
            }
        HdrWriter(File(dir, "environment.hdr").outputStream(), 16, 8).use { writer ->
            repeat(8) { writer.row(FloatArray(48) { (it % 3 + 1).toFloat() }) }
        }
        File(dir, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "processed").mkdirs()
        File(dir, "processed/0.f32").writeBytes(ByteArray(1_000))
        return store.update(p.id) {
            it.copy(
                state = "ready",
                captures =
                    listOf(Capture(0, Q(), V3.ZERO, Lens(16, 8, 10.0, 10.0, 8.0, 4.0), exposures)),
            )
        }
    }

    @Test
    fun cleanupPreservesExportsAndMetadataAndExrStillWorks() {
        val p = fixture()
        val dir = store.dir(p.id)
        try {
            val originalHdr = File(dir, "environment.hdr").readBytes()
            val originalJpeg = File(dir, "preview.jpg").readBytes()
            val before = store.storage(p)
            assertEquals(303L, before.sources)
            assertEquals(1_000L, before.processing)
            assertEquals(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, before.total)
            store.clearProcessingFiles(p.id)
            assertEquals(0L, store.storage(p).processing)
            store.requireSources(p.id)
            store.removeSources(p.id)
            val restored = SessionStore(context).read(p.id)
            assertTrue(restored.sourcesRemoved)
            assertEquals(p.captures, restored.captures)
            assertEquals(0L, store.storage(restored).sources)
            assertArrayEquals(originalHdr, File(dir, "environment.hdr").readBytes())
            assertArrayEquals(originalJpeg, File(dir, "preview.jpg").readBytes())
            assertThrows(IllegalStateException::class.java) { store.requireSources(p.id) }
            val exr = File(context.cacheDir, "storage-export.exr")
            try {
                ExrExport.write(File(dir, "environment.hdr"), exr, {}, {})
                assertTrue(exr.length() > 100)
            } finally {
                exr.delete()
            }
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun interruptedRemovalIsPersistedAndRetryRemovesRemainingFiles() {
        val p = fixture()
        try {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                store.removeSources(p.id, {}, { if (++checks == 3) throw CancellationException() })
            }
            val restored = SessionStore(context).read(p.id)
            assertTrue(restored.sourcesRemoved)
            assertEquals(203L, store.storage(restored).sources)
            assertThrows(IllegalStateException::class.java) { store.requireSources(p.id) }
            store.removeSources(p.id)
            store.removeSources(p.id) // Retry is idempotent, including after process death.
            assertEquals(0L, store.storage(restored).sources)
            assertTrue(File(store.dir(p.id), "environment.hdr").length() > 0)
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun malformedNamesAndMissingOutputsCannotDeleteSources() {
        val p = fixture()
        try {
            for (bad in listOf("../outside.jpg", "preview.jpg", "environment.hdr")) {
                store.update(p.id) {
                    it.copy(
                        captures =
                            p.captures.map { c ->
                                c.copy(exposures = c.exposures + Exposure(bad, 1, 100, 0))
                            }
                    )
                }
                assertThrows(IllegalArgumentException::class.java) { store.removeSources(p.id) }
                assertFalse(store.read(p.id).sourcesRemoved)
                assertEquals(303L, store.storage(p).sources)
            }
            store.save(p)
            File(store.dir(p.id), "preview.jpg").delete()
            assertThrows(IllegalStateException::class.java) { store.removeSources(p.id) }
            assertFalse(store.read(p.id).sourcesRemoved)
            assertEquals(303L, store.storage(p).sources)
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun pausedAndBusyCapturesKeepTheirSourcesProtected() {
        val p = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var worker: Thread? = null
        try {
            store.update(p.id) { it.copy(state = "paused") }
            assertThrows(IllegalStateException::class.java) { store.removeSources(p.id) }
            store.clearProcessingFiles(p.id)
            store.requireSources(p.id)
            worker = thread {
                store.withFiles(p.id) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { store.clearProcessingFiles(p.id) }
            assertThrows(IllegalStateException::class.java) { store.delete(p.id) }
            assertEquals(303L, store.storage(p).sources)
        } finally {
            release.countDown()
            worker?.join(5_000)
            store.delete(p.id)
        }
    }

    @Test
    fun olderManifestsDefaultToRetainedSources() {
        val p = fixture()
        try {
            val json = SessionStore.encode(p)
            json.remove("sourcesRemoved")
            assertFalse(SessionStore.decode(json).sourcesRemoved)
            store.requireSources(p.id)
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun canonicalMasterMigrationIsVerifiedAndSurvivesCancellation() {
        val p = fixture()
        val dir = store.dir(p.id)
        try {
            val original = store.masterFile(p).readBytes()
            assertThrows(CancellationException::class.java) {
                store.replaceMaster(
                    p.id,
                    MasterFormat.EXR,
                    { _, target -> target.writeBytes(byteArrayOf(1, 2, 3)) },
                    { throw CancellationException() },
                )
            }
            assertEquals(MasterFormat.HDR, store.read(p.id).masterFormat)
            assertArrayEquals(original, store.masterFile(p).readBytes())
            assertFalse(File(dir, "environment.partial.exr").exists())
            val before = app.hdri.processing.EnvironmentIO.read(store.masterFile(p))
            store.replaceMaster(
                p.id,
                MasterFormat.EXR,
                { source, target ->
                    app.hdri.processing.EnvironmentIO.convert(source, target, {}, {})
                },
            )
            val saved = store.read(p.id)
            val after = app.hdri.processing.EnvironmentIO.read(store.masterFile(saved))
            try {
                assertEquals(
                    0.0,
                    org.opencv.core.Core.norm(before, after, org.opencv.core.Core.NORM_INF),
                    0.0,
                )
            } finally {
                before.release()
                after.release()
            }
            assertFalse(File(dir, "environment.hdr").exists())
            store.clearProcessingFiles(p.id)
            store.removeSources(p.id)
            val export = File(context.cacheDir, "master-roundtrip.hdr")
            try {
                app.hdri.processing.EnvironmentIO.convert(store.masterFile(saved), export, {}, {})
                assertTrue(export.length() > 100)
            } finally {
                export.delete()
            }
            assertEquals(
                store.masterFile(saved).length(),
                store.storage(store.read(p.id)).environment,
            )
        } finally {
            store.delete(p.id)
        }
    }

    @Test
    fun cleanupFindsAbandonedPhotosAndDuplicateMasterWithoutTouchingDeclaredSources() {
        val p = fixture()
        val dir = store.dir(p.id)
        try {
            val orphan = File(dir, "8-123456789012345.jpg").apply { writeBytes(ByteArray(125)) }
            val partial =
                File(dir, "9-123456789012345.jpg.part").apply { writeBytes(ByteArray(50)) }
            val duplicate = File(dir, "environment.exr").apply { writeBytes(ByteArray(200)) }
            assertEquals(1375L, store.storage(p).processing)
            val bundle = store.bundleFiles(p)
            assertFalse(orphan in bundle)
            assertFalse(partial in bundle)
            assertFalse(duplicate in bundle)
            store.clearProcessingFiles(p.id)
            assertFalse(orphan.exists())
            assertFalse(partial.exists())
            assertFalse(duplicate.exists())
            store.requireSources(p.id)
            assertEquals(0L, store.storage(store.read(p.id)).processing)
            assertEquals(
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                store.storage(p).total,
            )
        } finally {
            store.delete(p.id)
        }
    }
}
