# sphere 0.1.0 · lens picker and portrait preview fixes

Download **sphere-0.1.0-preview.apk** and open it on your Pixel 8. It updates the existing app and preserves saved captures.

## Changes

- New capture setup shows **Main camera** and one preferred **Ultrawide** option. Duplicate camera aliases and unreliable “direct” physical-stream options are removed from the picker.
- Fixed double rotation in the native camera preview. Camera2's texture already includes a rotation; the app now handles that once while preserving the buffer crop and correct aspect ratio.
- Preview and guide dots share the same sensor-to-display mapping. Existing capture pose conventions and the ARCore main-camera preview are preserved.
- Older sessions retain their original camera identity. Removing duplicate picker entries does not silently switch a saved capture to another lens.

## Verification

The release workflow runs **41 JVM tests**, lint and **32 installed-app tests** against the exact signed APK. A new GPU test uses an actual SurfaceTexture and the production external-texture renderer to check corner orientation and circular geometry across **64 combinations** of sensor, display and producer rotation. Existing exposure, HDR/EXR, capture, stitching and storage tests remain required.

Pixel 8 camera output still needs confirmation on hardware. The GPU fixture reproduces the double-rotation path but does not simulate every vendor camera behaviour.

[Implementation and verification details](https://github.com/otdavies/android-hdri/blob/main/docs/FIELD-WORKFLOW.md)
