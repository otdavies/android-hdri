package app.hdri

import app.hdri.core.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class InertialCaptureTest {
    private fun androidRotation(deviceWorld: Q) = Q.axis(V3(PI / 2, 0.0, 0.0)) * deviceWorld

    @Test
    fun skySweepAndReturnKeepTheSameReferenceAndAutoCapture() {
        val orientation = InertialOrientation()
        var now = 1_000_000_000L
        // This is the entire orientation input: no ground plane, position or AR state.
        // Several bracket-length pauses in the preview must not reset the sensor reference.
        for (pitch in listOf(0.0, 40.0, 80.0, 90.0, 80.0, 40.0, 0.0)) {
            val expected = Q.look(30.0, pitch)
            repeat(100) {
                orientation.add(now, androidRotation(expected))
                now += 10_000_000
            }
            assertTrue(orientation.latest(now)!!.device.angle(expected) < 1e-5)
            val gate = SteadyGate()
            var dwell = 0.0
            repeat(30) {
                orientation.add(now, androidRotation(expected))
                val sample = orientation.latest(now)!!
                dwell = gate.update(sample.time, sample.device, 0.0, true)
                now += 33_000_000
            }
            assertEquals("Automatic shutter must work at pitch $pitch", 1.0, dwell, 1e-8)
        }
        assertTrue(orientation.latest(now)!!.device.angle(Q.look(30.0, 0.0)) < 1e-5)
    }

    @Test
    fun cameraReadoutAndDisplayAxesRespectSensorAndScreenRotation() {
        val camera = InertialOrientation.cameraInDevice(90)
        assertTrue(camera.rotate(V3(1.0, 0.0, 0.0)).angle(V3(0.0, -1.0, 0.0)) < 1e-5)
        assertTrue(camera.rotate(V3(0.0, 1.0, 0.0)).angle(V3(1.0, 0.0, 0.0)) < 1e-5)
        for (angle in listOf(0, 90, 180, 270)) {
            val c = InertialOrientation.cameraInDevice(angle)
            assertTrue(c.rotate(V3.FORWARD).angle(V3.FORWARD) < 1e-5)
            assertTrue((InertialOrientation.displayInDevice(angle) * c).angle(Q()) < 1e-5)
        }
        val lens = Lens(800, 600, 500.0, 500.0, 400.0, 300.0)
        // On the upright phone, a point to screen-right lands above the raw JPEG center.
        assertTrue(lens.project(camera.inverse().rotate(V3(.1, 0.0, -1.0)))!!.second < 300)
    }

    @Test
    fun staleOrInvalidSensorsCannotFireAndShutterPoseIgnoresDeliveryDelay() {
        val tracker = InertialOrientation()
        tracker.add(1_000_000_000, androidRotation(Q.look(10.0, 20.0)))
        val next = androidRotation(Q.look(20.0, 20.0))
        tracker.add(1_100_000_000, Q(-next.x, -next.y, -next.z, -next.w))
        assertTrue(tracker.at(1_050_000_000)!!.angle(Q.look(15.0, 20.0)) < .001)
        tracker.add(900_000_000, Q())
        tracker.add(1_200_000_000, Q(Double.NaN))
        assertEquals(1_100_000_000L, tracker.latest(1_200_000_000)!!.time)
        assertNull(tracker.latest(1_400_000_000))
        tracker.add(2_000_000_000, androidRotation(Q.look(70.0, 20.0)))
        assertNull(tracker.at(1_600_000_000))
        assertTrue(tracker.at(1_050_000_000)!!.angle(Q.look(15.0, 20.0)) < .001)
    }

    @Test
    fun headingInitializationAtSkyAndGroundPreservesGravity() {
        for (pitch in listOf(-90.0, -89.99, 0.0, 89.99, 90.0)) {
            val device = Q.look(123.0, pitch)
            val offset = InertialOrientation.zeroHeading(device)
            assertTrue(offset.rotate(V3(0.0, 1.0, 0.0)).angle(V3(0.0, 1.0, 0.0)) < 1e-5)
            assertEquals(sin(Math.toRadians(pitch)), (offset * device).rotate(V3.FORWARD).y, 1e-8)
        }
    }
}
