# Architecture

- `CaptureEngine`: Camera2 shared with ARCore for preview/intrinsics only. `TYPE_GAME_ROTATION_VECTOR` on a dedicated sensor thread owns orientation from startup through every bracket. No AR pose, tracking-state or translation enters guidance. Stale inertial data pauses the shutter. `GyroHistory` measures exposure motion independently of JPEG writes. The render thread does no photo or planning work.
- `CoveragePlanner`: searches compact latitude-ring plans using the rectangular lens footprint, stitching edge crop, aim margin and roll allowance. Plans are checked on a one-degree sphere grid; independent finer-grid tests check coverage. A camera-worker progress state is visible during optimization. Unfinished legacy projects retain their original photographs and IDs while unnecessary future stops are removed.
- `SessionStore`: versioned JSON manifests through Android AtomicFile. A completed bracket appears in the manifest only after all timestamp-matched exposure files are durable. No network or external storage permission is needed.
- `HdrPipeline`: reduced source decode → exposure alignment → one shared response calibration → radiance merge/checkpoints → overlap registration → coverage/seam selection → float multiband tiles → RGBE/JPEG/report.
- `ProcessingService`: one active process, native work off the main thread, persistent notification, pause, thermal handling, storage checks, timeout handling and bounded wake lock. Retry validates HDR checkpoint fingerprints and restores each checkpoint's clipping, motion and bracket-alignment findings. Pausing leaves a dismissible notification with the saved state.
- `AppViewModel`: lifecycle-retained navigation, asynchronous repository reads, SAF export with visible byte progress, and foreground-service state observation.
- Compose screens: home, capture setup, live capture, processing/quality review, and interactive spherical preview. `CaptureOverlay` provides directional vignettes/chevrons, screen-relative turn and pitch cues, optional leveling, and automatic shutter progress. The same composable is exercised by the emulator with supplied camera state.

## Coordinates and export conventions

Pose quaternions map unrotated JPEG camera coordinates (+X right, +Y up, -Z forward) into the capture's local world. Android game-vector world Z-up is mapped to Y-up with Rx(-90°); rear-camera readout axes use Rz(-SENSOR_ORIENTATION), separately from the display rotation. Still images are requested without JPEG rotation or automatic rotate-and-crop. Sensor orientation is interpolated at the middle exposure’s center-row midpoint when camera and inertial timestamps share the realtime clock. Otherwise the pre-bracket orientation is retained. Pixel rays are `( (u-cx)/fx, -(v-cy)/fy, -1 )`. Camera intrinsics scale to the matching-aspect JPEG size. This relationship must be confirmed on Pixel 8; digital stabilization is disabled for stills.

Equirectangular longitude runs from -180 to +180 left-to-right. Latitude runs +90 at the top to -90 at the bottom. The central ray is world -Z. Export is a 2:1 panorama, Radiance `-Y height +X width`, linear relative RGB / D65. Internal OpenCV arrays are BGR; the writer explicitly changes channel order. Preview JPEGs carry GPano full-sphere dimensions.

## Persistence

`files/sessions/<UUID>/session.json` contains quality, target directions, captures (pose, intrinsics and measured exposures), processing status, errors and quality findings. `*.jpg` are original exposures. `processed/` contains checkpoint HDR images, alignment previews and camera response. Final files are `environment.hdr`, `preview.jpg`, and `report.json`.

Partial captures are never counted as completed targets. Partial panorama files are not presented as final exports. In-progress data is excluded from capture ZIP export. Android backup is disabled to keep camera data local unless the user explicitly exports it.

The inertial yaw reference is session-local and may gradually drift. On resume, the user matches the first photograph at the original physical location to restore orientation; image-based refinement follows during stitching. The center is assumed fixed: new capture positions are zero and `poseSource` is `game_rotation_vector_fixed_pivot`. Older manifests default that field to `arcore`; old poses/files are preserved. There is no claim of measuring physical translation. `coverageVersion` is additive, defaults to 0, and prevents repeated plan migration.

## Dependencies

The Android Gradle/Kotlin toolchain and Compose baseline were copied from route. ARCore 1.54.0 and official OpenCV 4.12.0 Android AARs are pinned. Only arm64-v8a (Pixel 8) and x86_64 (emulator) are packaged. No runtime model download is required. This preview intentionally leaves release bytecode unminified so instrumentation and the installed preview share predictable binary APIs. Native processing dominates cost; future profiling should precede optimization work.
