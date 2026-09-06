# sphere 0.1.0 · handheld stitching preview

Download **sphere-0.1.0-preview.apk** and open it on your Pixel 8. It updates the existing app in place and preserves saved captures. Choose **Rebuild from saved photos** on an existing capture to use the updated processor; its source photos must still be present.

## New in this preview

- Recover texture lost to the old scene-estimated HDR response curve by inverting the fixed Camera2 tone curve.
- Fit overlapping views jointly with bounded local warps to accommodate the moving camera position during normal turning with the feet.
- Correct exposure and lens-shading differences using corresponding overlap samples, with withheld-sample validation and bounded corrections.
- Improve multi-image seam costs and preserve measured image coverage.
- Bound gradient-cache memory and stream float checkpoints in small row blocks. Both supplied 41-view captures rebuild under a 192 MiB Java heap limit in host replay; native OpenCV memory is separate.
- Handle cancellation or missing alignment input before the first native flow calculation safely.

The completed capture still offers **Lighting spheres**, chrome and 18% grey lighting references, storage breakdown and optional source-photo removal, and JPEG / Radiance HDR / OpenEXR exports. Everything runs locally.

## Quality and verification

On the supplied daylight capture, median withheld overlap brightness disagreement falls from 0.103 to 0.043 EV. Grey texture loss is substantially reduced. Curtain and ceiling joins remain visible, and this update does not yet reach the requested Google Photo Sphere quality bar. The supplied capture is treated as average-to-good handheld input; extra aiming pauses do not solve between-view parallax.

Relative lighting values come from processed JPEG brackets. EXR preserves the saved HDR values; it does not recover clipped or blurred source detail or create calibrated photometric units.

See [research, experiments, measurements and release verification](https://github.com/otdavies/android-hdri/blob/main/docs/HANDHELD-STITCHING.md). CI tests the exact signed APK before publication, including the physical-orbit fixture, native cleanup paths, storage operations, lighting controls and independent OpenEXR decoding.
