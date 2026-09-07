package app.hdri

import app.hdri.core.*
import org.junit.Assert.*
import org.junit.Test

class CaptureRouteTest {
    private val targets =
        listOf(0.0, 40.0, 70.0, 90.0, -40.0, -70.0, -90.0)
            .flatMap { pitch ->
                (if (kotlin.math.abs(pitch) == 90.0) listOf(0) else listOf(0, 90, 180, 270)).map {
                    yaw ->
                    yaw.toDouble() to pitch
                }
            }
            .mapIndexed { id, (yaw, pitch) -> Target(id, yaw, pitch) }

    @Test
    fun horizonClosesBeforeUpperRingsAndGroundComesLast() {
        val route = CaptureRoute(targets.reversed())
        assertEquals(targets.toSet(), route.ordered.toSet())
        assertEquals(
            listOf(0.0, 40.0, 70.0, 90.0, -40.0, -70.0, -90.0),
            route.ordered.map { it.pitch }.distinct(),
        )
        assertEquals(listOf(0.0, 90.0, 180.0, 270.0), route.ordered.take(4).map { it.yaw })
        assertEquals(270.0, route.ordered[4].yaw, 1e-9)
        assertEquals(0.0, route.ordered[5].yaw, 1e-9) // wrap keeps the clockwise direction
        assertEquals(
            "Ground",
            route.progress(route.ordered.dropLast(1).map { it.id }.toSet())!!.title,
        )
    }

    @Test
    fun resumeFillsEarlierGapsAndDoesNotChooseANearbyUnfinishedRing() {
        val route = CaptureRoute(targets)
        val done = (route.ordered.take(7) + route.ordered.last()).map { it.id }.toSet() - 1
        assertEquals(1, route.next(done)!!.id)
        assertEquals(RouteProgress("Horizon", 1, 7, 3, 4), route.progress(done))
        val remaining = route.ordered.filter { it.id !in done }
        var saved = done
        remaining.forEach { expected ->
            // Constructing the route again simulates process death/resume, with manifest
            // ordering changed by migration. Target identities and geometry are retained.
            val resumed = CaptureRoute(targets.sortedByDescending { it.id })
            assertEquals(expected, resumed.next(saved))
            saved = saved + expected.id
        }
        assertNull(route.next(saved))
        assertNull(route.progress(saved))
    }

    @Test
    fun filledGroundAndUltrawidePlansKeepTheirExactCoverageAndCount() {
        val lens = Lens(800, 600, 300.0, 300.0, 400.0, 300.0)
        val plan = CoveragePlanner.targets(lens, minPitch = -55.0)
        val route = CaptureRoute(plan)
        assertEquals(plan.size, route.ordered.size)
        assertEquals(plan.toSet(), route.ordered.toSet())
        assertTrue(route.ordered.none { it.pitch < -55 })
        assertEquals(0.0, route.ordered.first().pitch, .001)
        assertNull(CaptureRoute(emptyList()).next(emptySet()))
    }
}
