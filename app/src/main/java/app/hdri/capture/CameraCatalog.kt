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
    val zoomRatio: Float? = null,
    val correctionMode: Int = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
)

data class CameraScan(
    val choices: List<CameraChoice>,
    val diagnostics: List<String>,
    val permissionRequired: Boolean = false,
)

object CameraCatalog {
    fun discover(context: Context): List<CameraChoice> = inspect(context).choices

    fun inspect(context: Context): CameraScan {
        if (
            context.checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            return CameraScan(
                emptyList(),
                listOf("Camera permission is needed to inspect all lenses."),
                true,
            )
        val manager = context.getSystemService(CameraManager::class.java)
        val choices = mutableListOf<CameraChoice>()
        val notes =
            mutableListOf(
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
            )
        val ids =
            runCatching { manager.cameraIdList }
                .getOrElse {
                    return CameraScan(emptyList(), notes + "Camera discovery failed: ${it.message}")
                }
        var reference: Double? = null
        for (id in ids) runCatching {
                val logical = manager.getCameraCharacteristics(id)
                if (
                    logical[CameraCharacteristics.LENS_FACING] !=
                        CameraCharacteristics.LENS_FACING_BACK
                )
                    return@runCatching
                val size = photoSize(logical)
                val base = calibration(logical, null, size)
                if (reference == null) reference = base.fx / base.width
                val zoom =
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        logical[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.lower
                    else null
                notes +=
                    "Rear $id: zoom ${zoom ?: "unreported"}× minimum; physical IDs ${logical.physicalCameraIds.sorted()}; correction ${logical[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]?.toList()}"
                // Logical zoom-out uses ordinary supported JPEG + preview streams. It does not
                // depend on a physical camera advertising independent manual/JPEG capability.
                if (app.hdri.core.CameraSupport.ultrawideZoom(zoom) != null)
                    runCatching {
                            val mode = supported(logical, logical)
                            val lens = app.hdri.core.CameraGeometry.zoom(base, zoom!!.toDouble())
                            require(lens.fovX in 30.0..150.0 && lens.fovY in 30.0..150.0) {
                                "Unsupported field of view"
                            }
                            choices +=
                                CameraChoice(
                                    "zoom:$id:$zoom",
                                    id,
                                    null,
                                    "Ultrawide · ${String.format(Locale.US, "%.1f", zoom)}×",
                                    lens,
                                    logical[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
                                    zoom,
                                    mode,
                                )
                            notes += "Rear $id: logical ultrawide available (correction $mode)."
                        }
                        .onFailure { notes += "Rear $id logical ultrawide: ${it.message}" }
                for (physical in logical.physicalCameraIds.ifEmpty { setOf(id) }) runCatching {
                        val c = manager.getCameraCharacteristics(physical)
                        val mode = supported(logical, c)
                        val pinned = if (physical == id) null else physical
                        val lens = calibration(c, null, photoSize(c))
                        require(lens.fovX in 30.0..150.0 && lens.fovY in 30.0..150.0) {
                            "Unsupported field of view"
                        }
                        if (
                            choices.any {
                                it.zoomRatio == null && (it.physicalId ?: it.cameraId) == physical
                            }
                        )
                            return@runCatching
                        val ratio = (lens.fx / lens.width) / reference!!
                        val kind =
                            if (ratio < .85) "Ultrawide"
                            else if (ratio > 1.3) "Telephoto" else "Main"
                        choices +=
                            CameraChoice(
                                "camera:$id:${pinned.orEmpty()}",
                                id,
                                pinned,
                                "$kind · ${String.format(Locale.US, "%.1f", ratio)}× · direct",
                                lens,
                                c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
                                null,
                                mode,
                            )
                        notes += "Rear $id / $physical: direct lens available (correction $mode)."
                    }
                    .onFailure { notes += "Rear $id / $physical: ${it.message}" }
            }
            .onFailure { notes += "Rear $id: ${it.message}" }
        return CameraScan(
            choices.sortedWith(
                compareBy<CameraChoice> { it.zoomRatio == null }
                    .thenBy { it.lens.fx / it.lens.width }
            ),
            notes,
        )
    }

    private fun supported(logical: CameraCharacteristics, sensor: CameraCharacteristics): Int {
        // Manual exposure is a capability of the opened logical device. Physical-only IDs
        // need not repeat its capability list. Optional per-sensor controls inherit the parent.
        check(
            logical[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            ) == true
        ) {
            "Manual exposure brackets are unavailable."
        }
        check(
            (sensor[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]
                    ?: logical[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES])
                ?.contains(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) == true
        ) {
            "Fixed daylight white balance is unavailable."
        }
        check(
            (sensor[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES]
                    ?: logical[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES])
                ?.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) == true
        ) {
            "A fixed HDR tone curve is unavailable."
        }
        return app.hdri.core.CameraSupport.correction(
            sensor[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]
                ?: logical[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]
        ) ?: error("The camera exposes no geometric-correction mode.")
    }

    /** Prefer actual physical calibration + physical crop on Android 15 and newer. */
    fun frameCalibration(
        choice: CameraChoice,
        characteristics: (String) -> CameraCharacteristics,
        result: CaptureResult?,
        size: Size,
    ): Lens {
        val c = characteristics(choice.physicalId ?: choice.cameraId)
        if (choice.zoomRatio == null) return calibration(c, result, size)
        if (result == null) return choice.lens.scaled(size.width, size.height)
        val physical = result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
        val crop =
            if (android.os.Build.VERSION.SDK_INT >= 35)
                result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION]
            else null
        if (physical != null && crop != null)
            return calibration(characteristics(physical), result, size, crop)
        // Intrinsic/focal metadata on a logical result may already belong to the ultrawide.
        // Never multiply that by zoom again. Use the static logical 1× reference, then the
        // effective result zoom, then the post-zoom stream crop (Camera2 coordinate contract).
        val active = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]!!
        val full = calibration(c, null, Size(active.width(), active.height()))
        val zoom =
            if (android.os.Build.VERSION.SDK_INT >= 30)
                result[CaptureResult.CONTROL_ZOOM_RATIO] ?: error("Missing zoom result.")
            else 1f
        val zoomed = app.hdri.core.CameraGeometry.zoom(full, zoom.toDouble())
        val post =
            result[CaptureResult.SCALER_CROP_REGION] ?: Rect(0, 0, active.width(), active.height())
        return app.hdri.core.CameraGeometry.crop(
            zoomed,
            post.left.toDouble(),
            post.top.toDouble(),
            post.width().toDouble(),
            post.height().toDouble(),
            size.width,
            size.height,
        )
    }

    fun photoSize(c: CameraCharacteristics): Size =
        c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.JPEG)
            .filter { it.width >= it.height && it.width <= 4096 }
            .minBy {
                abs(it.width.toLong() * it.height - 3_000_000) +
                    if (abs(it.width.toDouble() / it.height - 4.0 / 3) > .03) 4_000_000 else 0
            }

    /** Scale the selected physical sensor's calibration through active-array and stream crops. */
    fun calibration(
        c: CameraCharacteristics,
        result: CaptureResult?,
        size: Size,
        cropOverride: Rect? = null,
    ): Lens {
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
            cropOverride
                ?: result?.get(CaptureResult.SCALER_CROP_REGION)
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
