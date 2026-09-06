# sphere · Android HDRI

**Bring the light back.**

A native, local-first Android app for guided spherical HDR photography. Built for testing on **Google Pixel 8**, with Kotlin, Jetpack Compose, ARCore, Camera2 and OpenCV. No backend, account, analytics or app Internet permission.

## Install

Download **`sphere-0.1.0-preview.apk`** from the newest [GitHub Release](https://github.com/otdavies/android-hdri/releases). Open it on your phone and allow your browser or Files app to install this APK when Android asks. Future preview APKs install over the same application, preserving captures when the signing identity is retained.

Google Play Services for AR is currently used for camera preview and lens intrinsics and must be installed before offline capture. Guidance uses the phone’s fused gyroscope and gravity, not AR position tracking. The app checks this and can open the system installation flow. It does not use Cloud Anchors or upload photographs. Processing and the sample capture also work without ARCore.

## Capture and export

1. Choose **New photosphere** and 2K / three exposures or 4K / five exposures.
2. Stay at one physical point. Pivot around the main camera lens, keeping objects at least a metre away where possible.
3. Follow the directional glow and arrows to bring a guide dot into the ring. Small hand wobbles are okay: the ring fills and captures a bracket automatically. Turn/tilt indicators and optional leveling help with framing; **Capture now** is a fallback when aligned.
4. Complete the sky, horizon and ground. Processing starts automatically with visible stages and a foreground notification.
5. Tap **Check the light** to inspect chrome and 18% grey spheres under the captured HDRI. Rotate the light, adjust exposure or explore the HDR panorama, then save the Radiance `.hdr` or photosphere JPEG. Existing projects can **Rebuild from saved photos** after an app update.

**Explore a sample capture** creates an analytic HDR light stage on the phone and runs it through the same image pipeline. It is useful for testing processing and export before a real capture.

Saved capture sessions survive app restarts. Returning to capture asks you to match a reference photograph to re-establish orientation at the same physical location. Interrupted processing restarts from valid HDR checkpoints. Original exposure JPEGs and metadata can be exported as a ZIP.

## What this preview implements

- Continuous fused gyro/gravity guidance with a fixed capture center; manual, timestamp-matched Camera2 HDR brackets. Blank sky and AR relocalization cannot move guide points.
- Compact rectangular-field-of-view coverage with crop/aim/roll margins; old unfinished plans are reduced while saved photos are retained.
- Forgiving automatic alignment dwell, stale-motion-sensor pause and exposure-time bracket-motion checks. No estimated distance or walking corrections.
- Fixed daylight white balance, fixed tone curve, measured shutter/ISO metadata, retained originals.
- Shared monotonic camera response estimation, subpixel bracket alignment, continuous RGB saturation weighting, native radiance merging and motion diagnostics.
- RootSIFT/RANSAC overlap matching, joint rotation/focal refinement, consistency-checked local optical flow and bounded mesh warps.
- Spherical inverse warping, graph-cut seam selection, float multiband blending, longitude wrapping and gap detection.
- Bounded image sizes and tiled rendering, cancellation, thermal cooldown, storage checks and atomic manifests.
- Relative linear RGB Radiance HDR export, tone-mapped JPEG with GPano metadata and Android document export.
- True HDR lighting inspection: ideal mirror and 18% diffuse probes, solid-angle integration, shared exposure and on-demand OpenGL ES 3 rendering.

## Quality boundary

This is a **device-test preview**, not a claim of production-ready or metrologically calibrated HDR capture. HDR radiance is reconstructed from bracketed processed JPEGs with a measured response curve. This does not recover clipped sensor values or provide absolute photometric units. RAW/DNG capture, calibrated lens-shading correction, large-parallax/depth reconstruction and robust moving-scene reconstruction remain follow-up work. Large translations, close objects, sun cores and moving subjects can still produce imperfect seams or radiance estimates; the app reports detectable issues.

Emulator checks exercise the actual image pipeline and installed APK. They cannot validate Pixel 8 shared-camera stream negotiation, camera-to-photo calibration, motion tracking or real-world visual quality. See [Pixel 8 validation](docs/PIXEL8-VALIDATION.md) before relying on captured environments for lighting work.

## Build

Use Android Studio or JDK 17 with Android SDK 35:

```sh
./gradlew testReleaseUnitTest lintRelease assembleRelease assembleReleaseAndroidTest
```

Windows: use `gradlew.bat` with the same arguments. Local release builds use the local Android development key unless `HDRI_KEYSTORE` and associated signing variables are provided.

The first commit copied the Android workflow from `otdavies/route`. Routine main pushes run unit tests and lint. A commit containing `[release]`, or **Actions → Android → Run workflow → release**, builds paired APKs once, verifies those exact candidates in an emulator, then publishes the same application APK. See [CI and signing](docs/CI.md).

See [sky capture and fewer stops](docs/SKY-CAPTURE.md), [handheld capture update](docs/CAPTURE-UPDATE.md), [research and design](docs/RESEARCH.md), [architecture](docs/ARCHITECTURE.md), [validation results and screenshots](docs/VALIDATION.md), and [preview notes](docs/PREVIEW.md).

Handheld stitching improvements, private replay instructions and measurements: [stitching update](docs/STITCHING-UPDATE.md).

Brand, lighting reference and camera sharpness update: [sphere update](docs/SPHERE-UPDATE.md).
