package app.hdri

import app.hdri.core.*
import org.junit.Assert.*
import org.junit.Test

class CameraGeometryTest {
    @Test
    fun zoomOutUsesTheOneTimesCalibrationBeforePostZoomAspectCropping() {
        val main = Lens(4000, 3000, 2800.0, 2820.0, 2070.0, 1440.0)
        val zoomed = CameraGeometry.zoom(main, .5)
        val wide = CameraGeometry.crop(zoomed, 0.0, 0.0, 4000.0, 3000.0, 1920, 1080)
        assertEquals(672.0, wide.fx, 1e-9)
        assertEquals(976.8, wide.cx, 1e-9)
        assertEquals(525.6, wide.cy, 1e-9)
        assertTrue(wide.fovX > 109)
        val roundTrip = CameraGeometry.zoom(zoomed, 2.0)
        assertEquals(main, roundTrip)
        assertThrows(IllegalArgumentException::class.java) { CameraGeometry.zoom(main, Double.NaN) }
    }

    @Test
    fun cropKeepsOffCentreCalibrationAndPreviewIsInvertibleAtEveryDisplayRotation() {
        val sensor = Lens(4000, 3000, 2200.0, 2180.0, 2070.0, 1440.0)
        val crop = CameraGeometry.crop(sensor, 100.0, 200.0, 3600.0, 2500.0, 1920, 1080)
        assertEquals(2200.0 * 1920 / 3600, crop.fx, 1e-9)
        assertEquals((2070.0 - 100) * 1920 / 3600, crop.cx, 1e-9)
        for (degrees in listOf(0, 90, 180, 270)) {
            val q =
                InertialOrientation.displayInDevice(degrees).inverse() *
                    InertialOrientation.cameraInDevice(90)
            for (x in 0..10) for (y in 0..10) {
                val image = CameraGeometry.displayToImage(x / 10.0, y / 10.0, q, crop, 1080, 1920)
                val display =
                    CameraGeometry.imageToDisplay(
                        image.first * crop.width,
                        image.second * crop.height,
                        q,
                        crop,
                        1080,
                        1920,
                    )
                assertEquals(x / 10.0, display.first, 1e-9)
                assertEquals(y / 10.0, display.second, 1e-9)
            }
        }
    }
}
