package app.hdri

import app.hdri.core.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CaptureBehaviorTest {
    @Test
    fun autoCaptureAcceptsHandTremorAndTrackingJitterAtDifferentFrameRates() {
        for (fps in listOf(15, 30, 60)) {
            val gate = SteadyGate()
            var fired = false
            repeat(fps * 2) { i ->
                val t = i.toDouble() / fps
                val jitter = .32 * sin(t * 2 * PI * 8) + .1 * cos(t * 2 * PI * 13)
                val q = Q.look(3.4 + jitter, .2 * sin(t * 31))
                if (
                    gate.update(1_000_000_000L + (t * 1e9).toLong(), q, 3.4 + jitter, true, .24) >=
                        1.0
                )
                    fired = true
            }
            assertTrue("Handheld capture never fired at $fps fps", fired)
        }
    }

    @Test
    fun aimWindowHasHysteresisButRejectsLostTrackingAndStaleFrames() {
        val gate = SteadyGate()
        repeat(13) { i ->
            gate.update(1_000_000_000L + i * 30_000_000L, Q.look(4.4, 0.0), 4.4, true, 0.0)
        }
        val before = gate.update(1_390_000_000, Q.look(4.4, 0.0), 4.4, true, 0.0)
        val after = gate.update(1_420_000_000, Q.look(4.8, 0.0), 4.8, true, 0.0)
        assertTrue("An edge wobble should not reset the ring", after >= before && after > 0)
        assertEquals(0.0, gate.update(2_000_000_000, Q.look(4.8, 0.0), 4.8, true, 0.0), 0.0)
        assertEquals(0.0, gate.update(2_030_000_000, Q(), 0.0, false, 0.0), 0.0)
    }

    @Test
    fun deliberateSweepsDoNotAutoCaptureJustBecauseTheyPassATarget() {
        val gate = SteadyGate()
        repeat(120) { i ->
            val yaw = 7 * sin(i / 30.0 * 2 * PI)
            assertTrue(
                gate.update(
                    1_000_000_000L + i * 33_333_333L,
                    Q.look(yaw, 0.0),
                    abs(yaw),
                    true,
                    0.0,
                ) < 1.0
            )
        }
    }

    @Test
    fun gyroMeasuresExcursionInsteadOfAddingEveryWobbleOrJpegWriteDelay() {
        val gyro = GyroHistory()
        val begin = 1_000_000_000L
        for (i in 0..1000) {
            val t = i * .01
            // Ten seconds of small oscillation would accumulate >20 degrees in the old check.
            val speed =
                if (t < 5) Math.toRadians(.3 * 2 * PI * 4 * cos(2 * PI * 4 * t))
                else Math.toRadians(25.0)
            gyro.add(begin + i * 10_000_000L, V3(0.0, speed, 0.0))
        }
        val shutter = gyro.excursion(begin + 200_000_000, begin + 4_000_000_000)!!
        assertTrue("Tiny oscillations were incorrectly accumulated: $shutter", shutter < .8)
        assertTrue(
            "Large motion during the measured interval must be detected",
            gyro.excursion(begin + 5_000_000_000, begin + 6_000_000_000)!! > 20,
        )
        assertNull(gyro.excursion(begin - 1, begin + 1_000_000_000))
    }

    @Test
    fun gyroCatchesAnActualShakeEvenWhenPhoneReturnsToStart() {
        val gyro = GyroHistory()
        for (i in 0..200) {
            val t = i * .01
            gyro.add(
                1_000_000_000 + i * 10_000_000L,
                V3(Math.toRadians(12 * PI * cos(PI * t)), 0.0, 0.0),
            )
        }
        assertTrue(gyro.excursion(1_000_000_000, 3_000_000_000)!! > 10)
    }

    @Test
    fun visualCorrectionsFollowScreenAxesAndDisableLevelingAtPoles() {
        val g = AimGuide.from(Q(), Q.look(25.0, 15.0).rotate(V3.FORWARD), V3(-.3, 0.0, 0.0))
        assertEquals(25f, g.yaw, .01f)
        assertEquals(15f, g.pitch, .01f)
        assertTrue(g.instruction.contains("right") && g.instruction.contains("up"))
        assertTrue(g.positionInstruction.contains("left"))
        val roll = AimGuide.from(Q.axis(V3(0.0, 0.0, Math.toRadians(20.0))), V3.FORWARD, V3.ZERO)
        assertEquals(20f, roll.roll, .01f)
        assertFalse(AimGuide.from(Q.look(0.0, 90.0), V3(0.0, 1.0, 0.0), V3.ZERO).canLevel)
        assertTrue(abs(AimGuide.from(Q(), V3(0.0, 0.0, 1.0), V3.ZERO).yaw) > 179)
    }

    @Test
    fun syntheticLightHasNoHardFloorOrHorizonBrightnessStep() {
        for (y in listOf(-.5, -.35, -.2, 0.0)) for (degrees in 0 until 360 step 3) {
            fun light(py: Double): V3 {
                val a = Math.toRadians(degrees.toDouble())
                val radius = sqrt(1 - py * py)
                return AnalyticLight.sample(V3(sin(a) * radius, py, -cos(a) * radius))
            }
            val a = light(y - 1e-6)
            val b = light(y + 1e-6)
            assertTrue(
                "Discontinuity at y=$y, longitude=$degrees",
                (a - b).length() / a.length() < .002,
            )
        }
    }
}
