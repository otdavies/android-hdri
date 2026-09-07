package app.hdri.core

/** Camera2 enum values: OFF=0, FAST=1, HIGH_QUALITY=2. Prefer quality, accept FAST. */
object CameraSupport {
    fun correction(modes: IntArray?): Int? =
        listOf(2, 1).firstOrNull { modes?.contains(it) == true }

    fun ultrawideZoom(minimum: Float?): Float? =
        minimum?.takeIf { it.isFinite() && it >= .1f && it < .85f }
}

/** Prevent an ignored zoom request or an automatic lens switch from corrupting coverage. */
class LensSessionGuard(private val requestedZoom: Float?) {
    private var physicalId: String? = null

    fun zoomReady(actual: Float?): Boolean =
        requestedZoom == null ||
            (actual != null &&
                actual.isFinite() &&
                kotlin.math.abs(actual / requestedZoom - 1) < .02)

    fun acceptPhysical(id: String?) {
        if (requestedZoom == null || id == null) return
        check(physicalId == null || physicalId == id) {
            "Android switched lenses during capture. Start a new capture to keep the lens fixed."
        }
        physicalId = id
    }
}
