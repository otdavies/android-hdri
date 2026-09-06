package app.hdri.data

import app.hdri.core.*
import app.hdri.core.Target

object CapturePlans {
    /** Replace future work only. Original capture IDs, file references and poses stay intact. */
    fun replaceRemaining(project: Project, remaining: List<Target>): Project {
        val done =
            project.captures.map { capture ->
                project.targets.find { it.id == capture.targetId }
                    ?: run {
                        val uv = Sphere.uv(capture.rotation.rotate(V3.FORWARD))
                        Target(capture.targetId, (uv.first - .5) * 360, (.5 - uv.second) * 180)
                    }
            }
        val firstId =
            maxOf(
                project.targets.maxOfOrNull { it.id } ?: -1,
                project.captures.maxOfOrNull { it.targetId } ?: -1,
            ) + 1
        return project.copy(
            targets = done + remaining.mapIndexed { i, t -> t.copy(id = firstId + i) },
            coverageVersion = CoveragePlanner.VERSION,
        )
    }
}
