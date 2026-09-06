# Architecture

- `CaptureEngine`: one ARCore shared session, Camera2 callback thread, OpenGL preview and gyroscope stream. Capturing requires valid tracking, correct aim, steady orientation and acceptable translation. The GL thread never decodes or writes a photo.
- `SessionStore`: versioned JSON manifests through Android AtomicFile. A completed bracket appears in the manifest only after all timestamp-matched exposure files are durable. No network or external storage permission is needed.
- `HdrPipeline`: reduced source decode → exposure alignment → one shared response calibration → radiance merge/checkpoints → overlap registration → coverage/seam selection → float multiband tiles → RGBE/JPEG/report.
- `ProcessingService`: one active process, native work off the main thread, persistent notification, pause, thermal handling, storage checks, timeout handling and bounded wake lock. Retry validates HDR checkpoint fingerprints and restores each checkpoint's clipping, motion and bracket-alignment findings. Pausing leaves a dismissible notification with the saved state.
- `AppViewModel`: lifecycle-retained navigation, asynchronous repository reads, SAF export with visible byte progress, and foreground-service state observation.
- Compose screens: home, capture setup, live capture, processing/quality review, and interactive spherical preview.

## Coordinates and export conventions

Pose quaternions map ARCore camera coordinates (+X right, +Y up, -Z forward) into the capture's local world. Still images are requested without JPEG rotation. Pixel rays are `( (u-cx)/fx, -(v-cy)/fy, -1 )`. Camera intrinsics scale to the matching-aspect JPEG size. This relationship must be confirmed on Pixel 8; digital stabilization is disabled for stills.

Equirectangular longitude runs from -180 to +180 left-to-right. Latitude runs +90 at the top to -90 at the bottom. The central ray is world -Z. Export is a 2:1 panorama, Radiance `-Y height +X width`, linear relative RGB / D65. Internal OpenCV arrays are BGR; the writer explicitly changes channel order. Preview JPEGs carry GPano full-sphere dimensions.

## Persistence

`files/sessions/<UUID>/session.json` contains quality, target directions, captures (pose, intrinsics and measured exposures), processing status, errors and quality findings. `*.jpg` are original exposures. `processed/` contains checkpoint HDR images, alignment previews and camera response. Final files are `environment.hdr`, `preview.jpg`, and `report.json`.

Partial captures are never counted as completed targets. Partial panorama files are not presented as final exports. In-progress data is excluded from capture ZIP export. Android backup is disabled to keep camera data local unless the user explicitly exports it.

ARCore coordinates are session-local. On resume, the user matches the first photograph to re-establish an orientation offset at the original physical location. This is explicit approximate reanchoring, followed by image-based refinement. Persistent world anchors/cloud relocalization are not used.

## Dependencies

The Android Gradle/Kotlin toolchain and Compose baseline were copied from route. ARCore 1.54.0 and official OpenCV 4.12.0 Android AARs are pinned. Only arm64-v8a (Pixel 8) and x86_64 (emulator) are packaged. No runtime model download is required. This preview intentionally leaves release bytecode unminified so instrumentation and the installed preview share predictable binary APIs. Native processing dominates cost; future profiling should precede optimization work.
