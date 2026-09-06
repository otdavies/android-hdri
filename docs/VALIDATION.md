# Validation record · 0.1.0

## Local build

Passed with JDK 17, Android SDK 35 and the copied Gradle 8.11.1 / AGP 8.9.2 / Kotlin 2.0.21 toolchain:

```text
testReleaseUnitTest lintRelease assembleRelease assembleReleaseAndroidTest
BUILD SUCCESSFUL
```

Nine JVM tests passed: physical camera axes, spherical wrap/poles, dense-grid coverage for four lens fields of view, steady capture gating, hardware bracket limits, high-range radiance reconstruction, RGBE encoding/channel order, precise manifest round trips and schema rejection.

Android lint has no errors. Both the signed local application APK and separate release instrumentation APK assemble. ARCore and OpenCV arm64 native ELF load segments were inspected and use 16,384-byte alignment. This validates the libraries' alignment, not real hardware behavior.

Kotlin sources were formatted with ktfmt's Kotlin language style. Workflow YAML, shell syntax and the copied candidate-source Python script were checked locally.

## Installed-APK verification

The GitHub Actions release gate runs six tests on an API 36 x86_64 AOSP emulator:

1. Decode a generated RGBE file with native OpenCV and check HDR values/channel order.
2. Reconstruct and stitch a complete analytic light stage; verify dimensions, coverage, finite radiance, median relative radiometric error and GPano metadata.
3. Cancel processing; check originals/checkpoints remain and no final panorama is published.
4. Reopen a persisted capture with exact exposure timestamps and orientation.
5. Launch the home screen without camera permission and verify capture/sample access.
6. Navigate setup and quality controls, recording screenshots.

CI result and measured sample statistics will be recorded after the run completes. Candidate APKs, checksums and device reports are retained by the workflow. Publication is gated on successful installed-APK verification.

## Not yet validated

No physical Pixel 8 was connected to this development environment. Real shared-camera configuration, camera/AR intrinsics correspondence, bracketing behavior, tracking stability, scene-dependent stitching quality, thermal behavior and phone performance remain pending. The synthetic test uses small source images and cannot establish real 4K capture performance or production radiometric accuracy. See `PIXEL8-VALIDATION.md`.
