package app.hdri

import app.hdri.core.*
import app.hdri.data.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CoveragePlannerTest {
    private fun lens(h: Double, v: Double) =
        Lens(
            800,
            600,
            800 / (2 * tan(Math.toRadians(h / 2))),
            600 / (2 * tan(Math.toRadians(v / 2))),
            400.0,
            300.0,
        )

    @Test
    fun fewerStopsStillCoverTheCroppedSphereWithAimAndRollErrors() {
        for ((horizontal, vertical) in
            listOf(54.0 to 68.0, 60.0 to 75.0, 84.0 to 100.0, 40.0 to 52.0)) {
            // Raw JPEG is landscape; the phone is held portrait.
            val lens = lens(vertical, horizontal)
            val extrinsic = InertialOrientation.cameraInDevice(90)
            val begin = System.nanoTime()
            val targets = CoveragePlanner.targets(lens, extrinsic)
            println(
                "COVERAGE ${horizontal}x$vertical: ${targets.size} stops, ${(System.nanoTime()-begin)/1e6} ms"
            )
            if (horizontal == 54.0)
                assertTrue("Expected substantially fewer than 74 stops", targets.size <= 44)
            assertEquals(targets.size, targets.map { it.id }.toSet().size)
            val footprints =
                targets.map { t ->
                    val rolls = if (abs(t.pitch) > 89) (0 until 360 step 30) else (-10..10 step 2)
                    rolls.map { roll ->
                        PhotoFootprint(
                            Q.look(t.yaw, t.pitch) *
                                Q.axis(V3(0.0, 0.0, Math.toRadians(roll.toDouble()))) *
                                extrinsic,
                            lens,
                        )
                    }
                }
            // Offset one-degree grid is independent of the planner's two-degree probe grid.
            // Angular erosion by 6 degrees proves coverage for any aim error in that ball.
            for (y in 0 until 180) for (x in 0 until 360) {
                val ray = Sphere.ray(x + .5, y + .5, 360, 180)
                assertTrue(
                    "Gap at $x,$y for $horizontal x $vertical",
                    footprints.any { variants -> variants.all { it.contains(ray, 6.0) } },
                )
            }
        }
    }

    @Test
    fun existingPhotosReplaceRedundantStopsWithoutChangingTheirIds() {
        val lens = lens(100.0, 84.0)
        val plan = CoveragePlanner.targets(lens)
        val saved = plan.map { PhotoFootprint(Q.look(it.yaw, it.pitch), lens) }
        assertTrue(CoveragePlanner.retainNeeded(plan, lens, Q(), saved).isEmpty())
        val partial = CoveragePlanner.retainNeeded(plan, lens, Q(), saved.take(4))
        assertTrue(partial.size < plan.size)
        assertTrue(partial.all { it in plan })
    }

    @Test
    fun migrationPreservesOriginalPhotosAndAllocatesCollisionFreeTargets() {
        val l = lens(68.0, 54.0)
        val captures =
            listOf(2, 17).map { id ->
                Capture(
                    id,
                    Q.look(id * 10.0, 20.0),
                    V3(.1, .2, .3),
                    l,
                    listOf(Exposure("$id-photo.jpg", 1000000L, 100, id.toLong())),
                )
            }
        val old =
            Project(
                "12345678-1234-1234-1234-123456789abc",
                1,
                "Saved sphere",
                Quality.QUICK,
                targets = (0..73).map { Target(it, it * 4.0, 0.0) },
                captures = captures,
            )
        val updated =
            CapturePlans.replaceRemaining(old, listOf(Target(0, 20.0, 45.0), Target(1, 50.0, 45.0)))
        val restored = SessionStore.decode(SessionStore.encode(updated))
        assertEquals(captures.map { it.exposures }, restored.captures.map { it.exposures })
        assertEquals(captures.map { it.position }, restored.captures.map { it.position })
        captures.zip(restored.captures).forEach { (a, b) ->
            assertTrue(a.rotation.angle(b.rotation) < 1e-5)
        }
        assertEquals(listOf(2, 17, 74, 75), restored.targets.map { it.id })
        assertEquals(CoveragePlanner.VERSION, restored.coverageVersion)
        assertEquals(old.name, restored.name)
    }

    @Test
    fun footprintAgreesWithActualOffCenterImageCrop() {
        val l = Lens(800, 600, 500.0, 490.0, 420.0, 280.0)
        val q = Q.look(72.0, 38.0) * Q.axis(V3(0.0, 0.0, .2))
        val footprint = PhotoFootprint(q, l)
        for (y in -40..640 step 11) for (x in -40..840 step 11) {
            val pixel = l.project(l.ray(x.toDouble(), y.toDouble()))
            val valid =
                pixel != null &&
                    pixel.first >= 800 * .045 &&
                    pixel.first <= 800 * .955 &&
                    pixel.second >= 600 * .045 &&
                    pixel.second <= 600 * .955
            assertEquals(valid, footprint.contains(q.rotate(l.ray(x.toDouble(), y.toDouble()))))
        }
    }
}
