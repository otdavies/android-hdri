package app.hdri

import app.hdri.core.*
import org.junit.Assert.*
import org.junit.Test

class CameraSupportTest {
    @Test
    fun fastOnlyCorrectionDoesNotHideAnUltrawideAndQualityIsPreferredWhenAvailable() {
        assertEquals(1, CameraSupport.correction(intArrayOf(0, 1)))
        assertEquals(2, CameraSupport.correction(intArrayOf(0, 1, 2)))
        assertNull(CameraSupport.correction(intArrayOf(0)))
        assertNull(CameraSupport.correction(null))
        assertEquals(.5f, CameraSupport.ultrawideZoom(.5f)!!, 0f)
        assertEquals(.7f, CameraSupport.ultrawideZoom(.7f)!!, 0f)
        for (value in listOf(null, 0f, 1f, 2f, Float.NaN)) assertNull(
            CameraSupport.ultrawideZoom(value)
        )
    }

    @Test
    fun ignoredZoomAndAutomaticPhysicalLensSwitchCannotPassCaptureValidation() {
        val guard = LensSessionGuard(.5f)
        assertFalse(guard.zoomReady(null))
        assertFalse(guard.zoomReady(1f))
        assertFalse(guard.zoomReady(Float.NaN))
        assertTrue(guard.zoomReady(.5f))
        guard.acceptPhysical("ultrawide")
        guard.acceptPhysical("ultrawide")
        assertThrows(IllegalStateException::class.java) { guard.acceptPhysical("main") }
    }
}
