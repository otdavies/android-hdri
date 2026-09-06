package app.hdri.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.util.Size
import app.hdri.core.Lens
import java.util.Locale
import kotlin.math.*

/** IDs are discovered, never inferred from a vendor's numbering or a nominal 0.5× label. */
data class CameraChoice(
    val key: String,
    val cameraId: String,
    val physicalId: String?,
    val label: String,
    val lens: Lens,
    val sensorOrientation: Int,
)

object CameraCatalog {
    fun discover(context: Context): List<CameraChoice> {
        val manager = context.getSystemService(CameraManager::class.java)
        val choices = mutableListOf<CameraChoice>()
        for (id in manager.cameraIdList) runCatching {
            val logical = manager.getCameraCharacteristics(id)
            if (
                logical[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK
            )
                return@runCatching
            val ids = logical.physicalCameraIds.ifEmpty { setOf(id) }
            for (physical in ids) runCatching {
                val c = manager.getCameraCharacteristics(physical)
                if (!supported(c)) return@runCatching
                val pinned = if (physical == id) null else physical
                val size = photoSize(c)
                val lens = calibration(c, null, size)
                require(lens.fovX in 30.0..140.0 && lens.fovY in 30.0..140.0)
                if (choices.any { (it.physicalId ?: it.cameraId) == physical }) return@runCatching
                choices +=
                    CameraChoice(
                        "camera:$id:${pinned.orEmpty()}",
                        id,
                        pinned,
                        "",
                        lens,
                        c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
                    )
            }
        }
        val firstRear =
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        val reference =
            firstRear?.let {
                runCatching {
                        val c = manager.getCameraCharacteristics(it)
                        calibration(c, null, photoSize(c)).let { l -> l.fx / l.width }
                    }
                    .getOrNull()
            } ?: choices.firstOrNull()?.let { it.lens.fx / it.lens.width } ?: return emptyList()
        return choices
            .sortedBy { it.lens.fx / it.lens.width }
            .map { choice ->
                val ratio = (choice.lens.fx / choice.lens.width) / reference
                val kind =
                    if (ratio < .85) "Ultrawide"
                    else if (ratio > 1.3) "Telephoto" else "Main · native"
                choice.copy(label = "$kind · ${String.format(Locale.US, "%.1f", ratio)}×")
            }
    }

    private fun supported(c: CameraCharacteristics): Boolean =
        c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
            c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true &&
            c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.contains(
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            ) == true &&
            c[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES]?.contains(
                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
            ) == true &&
            // FAST may legally be equivalent to OFF; wide lenses require actual correction.
            c[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]?.contains(
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            ) == true

    fun photoSize(c: CameraCharacteristics): Size =
        c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.JPEG)
            .filter { it.width >= it.height && it.width <= 4096 }
            .minBy {
                abs(it.width.toLong() * it.height - 3_000_000) +
                    if (abs(it.width.toDouble() / it.height - 4.0 / 3) > .03) 4_000_000 else 0
            }

    /** Scale the selected physical sensor's calibration through active-array and stream crops. */
    fun calibration(c: CameraCharacteristics, result: CaptureResult?, size: Size): Lens {
        val active =
            c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
                ?: error("Missing camera sensor area.")
        val pre = c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE] ?: active
        val intrinsic =
            result?.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
                ?: c[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION]
        val full =
            if (intrinsic != null && intrinsic.size >= 5 && intrinsic[0] > 0 && intrinsic[1] > 0) {
                require(abs(intrinsic[4]) < 1f) {
                    "This camera's skew calibration is not supported."
                }
                Lens(
                        pre.width(),
                        pre.height(),
                        intrinsic[0].toDouble(),
                        intrinsic[1].toDouble(),
                        intrinsic[2].toDouble(),
                        intrinsic[3].toDouble(),
                    )
                    .scaled(active.width(), active.height())
            } else {
                val sensor =
                    c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
                        ?: error("Missing camera dimensions.")
                val focal =
                    result?.get(CaptureResult.LENS_FOCAL_LENGTH)
                        ?: c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()
                        ?: error("Missing focal length.")
                val pixels =
                    c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
                        ?: error("Missing sensor pixel dimensions.")
                Lens(
                    active.width(),
                    active.height(),
                    focal.toDouble() * pixels.width / sensor.width,
                    focal.toDouble() * pixels.height / sensor.height,
                    active.width() / 2.0,
                    active.height() / 2.0,
                )
            }
        val crop =
            result?.get(CaptureResult.SCALER_CROP_REGION)
                ?: Rect(0, 0, active.width(), active.height())
        return app.hdri.core.CameraGeometry.crop(
            full,
            crop.left.toDouble(),
            crop.top.toDouble(),
            crop.width().toDouble(),
            crop.height().toDouble(),
            size.width,
            size.height,
        )
    }
}
