package app.hdri

import app.hdri.core.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test
    fun projectionUsesPhysicalCameraAxes() {
        val lens = Lens(800, 600, 400.0, 400.0, 400.0, 300.0)
        assertEquals(400.0, lens.project(V3.FORWARD)!!.first, 1e-8)
        assertTrue(lens.project(V3(.1, .1, -1.0))!!.first > 400)
        assertTrue(lens.project(V3(.1, .1, -1.0))!!.second < 300)
        assertNull(lens.project(V3(0.0, 0.0, 1.0)))
        val q = Q.look(123.0, 42.0)
        val p = V3(.2, .5, -.8).unit()
        assertTrue(q.inverse().rotate(q.rotate(p)).angle(p) < 1e-5)
    }

    @Test
    fun sphericalCoordinatesWrapAndIncludeBothPoles() {
        assertTrue(
            Sphere.ray(0.0, 250.0, 1000, 500).angle(Sphere.ray(1000.0, 250.0, 1000, 500)) < 1e-6
        )
        assertEquals(1.0, Sphere.ray(123.0, 0.0, 1000, 500).y, 1e-9)
        assertEquals(-1.0, Sphere.ray(123.0, 500.0, 1000, 500).y, 1e-9)
        assertTrue(Sphere.ray(500.0, 250.0, 1000, 500).angle(V3.FORWARD) < 1e-6)
    }

    @Test
    fun plannedCoverageHasNoGapsAcrossRealisticLenses() {
        for (fov in listOf(40.0, 54.0, 75.0, 90.0)) {
            val targets = Sphere.targets(fov)
            assertEquals(targets.size, targets.map { it.id }.toSet().size)
            for (y in 0..90) for (x in 0..180) {
                val ray = Sphere.ray(x.toDouble(), y.toDouble(), 180, 90)
                assertTrue(
                    "Uncovered $x,$y at FOV $fov",
                    targets.minOf { it.ray.angle(ray) } < fov * .455,
                )
            }
        }
    }

    @Test
    fun dwellRejectsTranslationTrackingLossAndMovement() {
        val gate = SteadyGate(500_000_000)
        var now = 1_000_000_000L
        repeat(8) {
            gate.update(now, Q(), true, true, 0.0)
            now += 100_000_000
        }
        assertEquals(1.0, gate.update(now, Q(), true, true, 0.0), 1e-8)
        assertEquals(0.0, gate.update(now + 100_000_000, Q(), true, true, .2), 1e-8)
        assertEquals(0.0, gate.update(now + 200_000_000, Q.look(10.0, 0.0), true, true, 0.0), 1e-8)
        assertEquals(0.0, gate.update(now + 300_000_000, Q(), true, false, 0.0), 1e-8)
    }

    @Test
    fun bracketsClampAndDeduplicateHardwareLimits() {
        val times = Brackets.times(10_000_000, 5, 100_000, 100_000_000)
        assertEquals(listOf(625_000L, 2_500_000L, 10_000_000L, 40_000_000L, 100_000_000L), times)
        val limited = Brackets.times(1_000_000, 5, 1_000_000, 2_000_000)
        assertEquals(2, limited.size)
        assertEquals(limited.distinct(), limited)
    }
}
