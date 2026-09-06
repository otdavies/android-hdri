package app.hdri.core

import kotlin.math.*

/** Pinhole stream cropping and a shared preview/marker transform. */
object CameraGeometry {
    fun crop(
        sensor: Lens,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        outputWidth: Int,
        outputHeight: Int,
    ): Lens {
        require(width > 0 && height > 0 && outputWidth > 0 && outputHeight > 0)
        val aspect = outputWidth.toDouble() / outputHeight
        val cw = min(width, height * aspect)
        val ch = min(height, width / aspect)
        val x = left + (width - cw) / 2
        val y = top + (height - ch) / 2
        return Lens(
            outputWidth,
            outputHeight,
            sensor.fx * outputWidth / cw,
            sensor.fy * outputHeight / ch,
            (sensor.cx - x) * outputWidth / cw,
            (sensor.cy - y) * outputHeight / ch,
        )
    }

    fun displayToImage(
        x: Double,
        y: Double,
        cameraInDisplay: Q,
        lens: Lens,
        width: Int,
        height: Int,
    ): Pair<Double, Double> {
        val turn = abs(cameraInDisplay.rotate(V3(1.0, 0.0, 0.0)).y) > .5
        val iw = if (turn) lens.height else lens.width
        val ih = if (turn) lens.width else lens.height
        val scale = max(width.toDouble() / iw, height.toDouble() / ih)
        val display = V3((x - .5) * width / scale, (.5 - y) * height / scale, 0.0)
        val v = cameraInDisplay.inverse().rotate(display)
        return (.5 + v.x / lens.width) to (.5 - v.y / lens.height)
    }

    fun imageToDisplay(
        x: Double,
        y: Double,
        cameraInDisplay: Q,
        lens: Lens,
        width: Int,
        height: Int,
    ): Pair<Double, Double> {
        val turn = abs(cameraInDisplay.rotate(V3(1.0, 0.0, 0.0)).y) > .5
        val iw = if (turn) lens.height else lens.width
        val ih = if (turn) lens.width else lens.height
        val scale = max(width.toDouble() / iw, height.toDouble() / ih)
        val v = cameraInDisplay.rotate(V3(x - lens.width * .5, lens.height * .5 - y, 0.0))
        return (.5 + v.x * scale / width) to (.5 - v.y * scale / height)
    }
}
