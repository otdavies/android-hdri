package app.hdri.core

import java.util.BitSet
import kotlin.math.*

/** Rectangular spherical footprints, including the same edge crop used by the stitcher. */
class PhotoFootprint(val rotation: Q, val lens: Lens) {
    private val crop = .045
    private val localNormals =
        listOf(
            V3(1.0, 0.0, -(lens.cx - lens.width * crop) / lens.fx).unit(),
            V3(-1.0, 0.0, -(lens.width * (1 - crop) - lens.cx) / lens.fx).unit(),
            V3(0.0, -1.0, -(lens.cy - lens.height * crop) / lens.fy).unit(),
            V3(0.0, 1.0, -(lens.height * (1 - crop) - lens.cy) / lens.fy).unit(),
        )

    private val normals = localNormals.map { rotation.rotate(it) }
    private val rollPlanes =
        localNormals.map { n ->
            Triple(
                rotation.rotate(V3(n.x, n.y, 0.0)),
                rotation.rotate(V3(-n.y, n.x, 0.0)),
                rotation.rotate(V3(0.0, 0.0, n.z)),
            )
        }
    private val forward = rotation.rotate(V3.FORWARD)

    fun contains(ray: V3, marginDegrees: Double = 0.0): Boolean {
        val threshold = sin(Math.toRadians(marginDegrees))
        return forward.dot(ray) > 0 && normals.all { it.dot(ray) >= threshold }
    }

    /** Exact minimum over a continuous roll interval, including full freedom at the poles. */
    fun containsWithRoll(ray: V3, marginDegrees: Double, freeRoll: Boolean): Boolean {
        if (forward.dot(ray) <= 0) return false
        val threshold = sin(Math.toRadians(marginDegrees))
        val c10 = .984807753012208
        val s10 = .17364817766693033
        val t10 = .17632698070846498
        return rollPlanes.all { (horizontal, perpendicular, axial) ->
            val a = horizontal.dot(ray)
            val b = perpendicular.dot(ray)
            val c = axial.dot(ray)
            val minimum =
                if (freeRoll || (a < 0 && abs(b) <= -a * t10)) c - hypot(a, b)
                else c + a * c10 - abs(b) * s10
            minimum >= threshold
        }
    }

    /** Bounds in the level handset's display axes; principal-point offsets are respected. */
    fun halfAngles(): Pair<Double, Double> {
        val horizontal = min(lens.cx, lens.width - lens.cx) - lens.width * crop
        val vertical = min(lens.cy, lens.height - lens.cy) - lens.height * crop
        return Math.toDegrees(atan(horizontal / lens.fx)) to
            Math.toDegrees(atan(vertical / lens.fy))
    }
}

object CoveragePlanner {
    const val VERSION = 3
    // Six degrees of aim hysteresis + .75° for the <= .71° radius of a one-degree cell.
    const val PLAN_MARGIN = 6.75
    private val probeRays by lazy {
        buildList {
            for (y in 0..180) for (x in 0 until 360) add(
                Sphere.ray(x.toDouble(), y.toDouble(), 360, 180)
            )
        }
    }

    private fun requiredMask(minPitch: Double) =
        BitSet(probeRays.size).apply {
            val y = sin(Math.toRadians(minPitch))
            probeRays.forEachIndexed { i, ray -> if (ray.y >= y - 1e-9) set(i) }
        }

    private fun covers(coverage: BitSet, required: BitSet): Boolean =
        (required.clone() as BitSet).apply { andNot(coverage) }.isEmpty

    private fun mask(footprints: List<PhotoFootprint>, margin: Double): BitSet =
        BitSet(probeRays.size).apply {
            probeRays.forEachIndexed { i, ray ->
                if (footprints.all { it.contains(ray, margin) }) set(i)
            }
        }

    private fun plannedMask(
        target: Target,
        lens: Lens,
        cameraInDisplay: Q,
        margin: Double = PLAN_MARGIN,
    ): BitSet {
        // The level indicator is a suggestion, not another shutter lock. Allow ten degrees
        // of handset roll. At poles the phone is free to rotate through the full circle.
        val footprint = PhotoFootprint(Q.look(target.yaw, target.pitch) * cameraInDisplay, lens)
        return BitSet(probeRays.size).apply {
            probeRays.forEachIndexed { i, ray ->
                if (footprint.containsWithRoll(ray, margin, abs(target.pitch) > 89)) set(i)
            }
        }
    }

    /**
     * Try compact latitude rings and prove their cropped footprints cover the sphere. Wider
     * vertical FOV buys fewer rows; horizontal FOV determines points in each row. Runs on the
     * camera worker with visible progress, never the preview/render thread.
     */
    fun targets(
        lens: Lens,
        cameraInDisplay: Q = Q(),
        margin: Double = PLAN_MARGIN,
        minPitch: Double = -90.0,
        progress: (Double) -> Unit = {},
    ): List<Target> {
        require(lens.fovX in 30.0..140.0 && lens.fovY in 30.0..140.0)
        require(margin in PLAN_MARGIN..13.0 && minPitch in -90.0..-45.0)
        val required = requiredMask(minPitch)
        val (hx, hy) = PhotoFootprint(Q(), lens).halfAngles()
        val quarterTurn = abs(cameraInDisplay.rotate(V3(1.0, 0.0, 0.0)).y) > .5
        val h = if (quarterTurn) hy else hx
        val v = if (quarterTurn) hx else hy
        val firstN = ceil(360 / (2 * (h - margin))).toInt()
        val firstLevel = max(2, floor(90 / (2 * (v - margin))).toInt())
        val plans =
            buildList {
                    for (levels in firstLevel..firstLevel + 2) for (factor in
                        listOf(.92, .96, 1.0, 1.02, 1.04, 1.06, 1.08)) for (n in
                        firstN..firstN + 8) {
                        val pitches =
                            listOf(0.0) +
                                (1 until levels).map { it * 90.0 / levels * factor } +
                                listOf(90.0) +
                                (1 until levels).map { -it * 90.0 / levels * factor } +
                                listOf(-90.0)
                        if (pitches.any { abs(it) > 90 }) continue
                        add(
                            buildList {
                                pitches
                                    .filter { it >= minPitch }
                                    .forEach { pitch ->
                                        val count =
                                            if (abs(pitch) == 90.0) 1
                                            else
                                                ceil(
                                                        n *
                                                            cos(
                                                                Math.toRadians(
                                                                    max(0.0, abs(pitch) - v)
                                                                )
                                                            )
                                                    )
                                                    .toInt()
                                        for (i in 0 until count) add(
                                            Target(
                                                size,
                                                (i +
                                                    if (pitch == 0.0 || abs(pitch) == 90.0) 0.0
                                                    else .5) * 360 / count,
                                                pitch,
                                            )
                                        )
                                    }
                            }
                        )
                    }
                }
                .sortedBy { it.size }
        // Cache complete rings rather than every dense ray test across similar candidate plans.
        val cache = mutableMapOf<Triple<Double, Double, Int>, BitSet>()
        plans.forEachIndexed { index, plan ->
            progress(index.toDouble() / plans.size)
            val union = BitSet(probeRays.size)
            for ((pitch, ring) in plan.groupBy { it.pitch }) {
                val key = Triple(pitch, ring.first().yaw, ring.size)
                union.or(
                    cache.getOrPut(key) {
                        BitSet(probeRays.size).apply {
                            ring.forEach { or(plannedMask(it, lens, cameraInDisplay, margin)) }
                        }
                    }
                )
            }
            if (covers(union, required)) {
                progress(1.0)
                return plan
            }
        }
        error("Could not find complete coverage for this camera lens.")
    }

    /** Drop unnecessary planned directions when migrating a project with saved photographs. */
    fun retainNeeded(
        planned: List<Target>,
        lens: Lens,
        cameraInDisplay: Q,
        saved: List<PhotoFootprint>,
        margin: Double = PLAN_MARGIN,
        minPitch: Double = -90.0,
    ): List<Target> {
        if (saved.isEmpty()) return planned
        val required = requiredMask(minPitch)
        val covered = BitSet(probeRays.size)
        saved.forEach { covered.or(mask(listOf(it), 2.0)) }
        val masks = planned.map { plannedMask(it, lens, cameraInDisplay, margin) }
        val retained = planned.indices.toMutableSet()
        for (i in planned.indices.reversed()) {
            val union = covered.clone() as BitSet
            retained.forEach { if (it != i) union.or(masks[it]) }
            if (covers(union, required)) retained.remove(i)
        }
        return planned.filterIndexed { i, _ -> i in retained }
    }
}
