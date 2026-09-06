# Luma Sphere · Android HDRI

A native, local-first Android app for guided spherical HDR photography. Built for testing on **Google Pixel 8**, with Kotlin, Jetpack Compose, ARCore, Camera2 and OpenCV. No backend, account, analytics or app Internet permission.

## Install

Download **`hdri-0.1.0-preview.apk`** from the newest [GitHub Release](https://github.com/otdavies/android-hdri/releases). Open it on your phone and allow your browser or Files app to install this APK when Android asks. Future preview APKs install over the same application, preserving captures when the signing identity is retained.

Google Play Services for AR must be installed before offline capture. The app checks this and can open the system installation flow. It does not use Cloud Anchors or upload photographs. Processing and the sample capture also work without ARCore.

## Capture and export

1. Choose **New photosphere** and 2K / three exposures or 4K / five exposures.
2. Stay at one physical point. Pivot around the main camera lens, keeping objects at least a metre away where possible.
3. Align a guide dot with the ring and hold still. The app captures a bracket automatically and shows each exposure being saved.
4. Complete the sky, horizon and ground. Processing starts automatically with visible stages and a foreground notification.
5. Review any clipping, motion or alignment concerns, explore the sphere, then save the Radiance `.hdr` or photosphere JPEG.

**Explore a sample capture** creates an analytic HDR light stage on the phone and runs it through the same image pipeline. It is useful for testing processing and export before a real capture.

Saved capture sessions survive app restarts. Returning to capture asks you to match a reference photograph to re-establish the coordinate frame; it does not pretend a previous AR coordinate frame survives process death. Interrupted processing restarts from valid HDR checkpoints. Original exposure JPEGs and metadata can be exported as a ZIP.

## What this preview implements

- Real ARCore tracking and a shared Camera2 still camera; manual, timestamp-matched HDR brackets.
- Field-of-view-derived spherical coverage; alignment dwell, tracking-loss pause, position-drift and bracket-motion checks.
- Fixed daylight white balance, fixed tone curve, measured shutter/ISO metadata, retained originals.
- Camera response estimation, exposure alignment, weighted radiance merge and motion rejection.
- ORB/RANSAC overlap matching and robust rotation refinement with an AR orientation prior.
- Spherical inverse warping, seam-label optimization, float multiband blending, longitude wrapping and gap detection.
- Bounded image sizes and tiled rendering, cancellation, thermal cooldown, storage checks and atomic manifests.
- Relative linear RGB Radiance HDR export, tone-mapped JPEG with GPano metadata, interactive viewer and Android document export.

## Quality boundary

This is a **device-test preview**, not a claim of production-ready or metrologically calibrated HDR capture. HDR radiance is reconstructed from bracketed processed JPEGs with a measured response curve. This does not recover clipped sensor values or provide absolute photometric units. RAW/DNG capture, calibrated lens-shading correction, dense parallax correction and robust moving-scene reconstruction remain follow-up work. Large translations, close objects, sun cores and moving subjects can still produce imperfect seams or radiance estimates; the app reports detectable issues.

Emulator checks exercise the actual image pipeline and installed APK. They cannot validate Pixel 8 shared-camera stream negotiation, camera-to-photo calibration, motion tracking or real-world visual quality. See [Pixel 8 validation](docs/PIXEL8-VALIDATION.md) before relying on captured environments for lighting work.

## Build

Use Android Studio or JDK 17 with Android SDK 35:

```sh
./gradlew testReleaseUnitTest lintRelease assembleRelease assembleReleaseAndroidTest
```

Windows: use `gradlew.bat` with the same arguments. Local release builds use the local Android development key unless `HDRI_KEYSTORE` and associated signing variables are provided.

The first commit copied the Android workflow from `otdavies/route`. Routine main pushes run unit tests and lint. A commit containing `[release]`, or **Actions → Android → Run workflow → release**, builds paired APKs once, verifies those exact candidates in an emulator, then publishes the same application APK. See [CI and signing](docs/CI.md).

See [research and design](docs/RESEARCH.md), [architecture](docs/ARCHITECTURE.md), and [preview notes](docs/PREVIEW.md).
