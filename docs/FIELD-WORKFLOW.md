# Lighting reference, wider lenses, ground fill and storage

## Lighting reference

The diffuse calculation remains Lambertian: outgoing radiance is reflectance / π times the cosine-weighted incident-light integral. A uniform environment of radiance L therefore produces 0.18 L from the grey sphere. Brightening that material alone would be physically wrong. See [PBRT: diffuse reflection](https://pbr-book.org/4ed/Reflection_Models/Diffuse_Reflection).

The diffuse-light meter uses the geometric mean of *received diffuse light*. The viewing default applies a modest −0.25 EV trim to that reference. **Smooth highlights** is enabled by default: values below 0.25 linear retain their original RGB; above that knee the largest RGB channel is compressed with `0.25 + 0.75 * (peak - 0.25) / (peak + 0.5)` and all channels share its scale. This shoulder preserves RGB ratios and ordinary grey midtones while avoiding the previous hard clipping of reflections. Turn it off for the reference sRGB transfer and clipping at display white. The 18% material and exported radiance are unchanged.

**Scene 1×** replaces the misleading raw **HDR 1×** button. Merging divides image values by effective exposure seconds, so a numeric multiplier of one has no useful display-exposure meaning. The scene reference maps the panorama's solid-angle-weighted geometric luminance to 0.18 before display. **Capture exposure** retains the median middle-bracket exposure including ISO gain, when valid metadata exists. Exposure adjustments affect both balls and the environment equally. These JPEG-derived HDRIs have relative radiance; the renderer cannot infer scene lux without photometric calibration.

Reading the supplied saved EXR masters confirms the problem: raw 1× clipped every channel over 99.33% of the daylight sphere by solid angle. The previous diffuse-metered default clipped at least one channel over 32.46% of that sphere and 43.95% of the night sphere. The new scene multipliers are 0.0091264 and 0.2050511 respectively. These are measurements of saved captures, not a claim of calibrated real-world brightness.

Explore HDR supports pinch zoom and zoom buttons from 0.5× to 4×. Zoom changes perspective field of view and scales panning sensitivity. Reset restores the view and exposure. Two-finger gestures do not accidentally pan, and the surviving pointer is rebased when a pinch ends.

## Lens selection and capture pace

The existing main-camera ARCore preview remains available. Other eligible rear cameras use a native Camera2 preview, the same inertial guidance, and the same forgiving focus / steadiness gate. The app discovers public and logical-camera physical IDs, rather than assuming camera “2” or a universal 0.5× zoom ratio.

Lens discovery runs after camera permission and refreshes on return from Settings. **Lens details** exposes a copyable device/camera capability report and rejection reasons. The app checks manual exposure capability on the opened logical camera; physical-only IDs need not repeat it. Fixed daylight white balance and a fixed tone curve remain required. Geometric correction prefers HIGH_QUALITY, accepting FAST on devices that do not advertise the former. FAST is an ISP mode, not a guarantee of identical correction on every phone; Android permits it to be equivalent to OFF when correction would reduce frame rate, so physical-phone image validation remains necessary.

The preferred ultrawide route uses the logical camera's advertised [`CONTROL_ZOOM_RATIO_RANGE`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#CONTROL_ZOOM_RATIO_RANGE) minimum below 1×. The selected [`CONTROL_ZOOM_RATIO`](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_ZOOM_RATIO) is applied to preview, focus preflight and every HDR bracket. This uses normal logical JPEG/preview streams and does not require physical JPEG streams. Coverage waits for confirmed zoom and calibration. Ignored zoom, an active physical-lens switch, or a bracket crop change fails visibly. Native previews use the preview template, with video stabilization disabled.

On Android 15+ the renderer/recorder uses active physical calibration and [`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION`](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION) when supplied. Otherwise the static logical 1× intrinsics are transformed by the effective zoom and then the post-zoom crop. Result intrinsics can already refer to the active ultrawide: applying the zoom factor to those again would incorrectly double the field of view. Per-frame calibration is recorded with each capture.

**Direct** options remain available: preview and JPEG outputs are pinned to a physical ID, using physical request overrides and result metadata. Android does not guarantee arbitrary physical JPEG stream combinations: see [multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera). No fallback silently switches a capture to another lens.

Each lens uses its own intrinsic calibration, active-array and stream crop, sensor orientation, and per-frame metadata. Where intrinsic calibration is absent, sensor dimensions and focal length provide an initial estimate; the existing visual registration refines focal scale. Camera2's corrected metadata is an approximate mapping, so device validation is still required. See [intrinsic calibration](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LENS_INTRINSIC_CALIBRATION) and [distortion correction](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#DISTORTION_CORRECTION_MODE). Preview cropping and aim-marker placement share inverse transforms.

Compact / More overlap / Most overlap use 6.75° / 10° / 13° footprint erosion. Plans prove cropped coverage with aim and roll allowance. A representative 68° × 54° main lens needs 41 stops; 112° × 96° needs 17, or 16 with ground fill. Most overlap needs 19 for that wider lens with ground fill. These are fixture counts, not measured Pixel 8 claims. Setup estimates from available calibration, then capture plans from the active stream.

## Optional ground fill

“Fill below me” omits targets below −55° pitch. The nadir cap (35° from straight down) is explicitly excluded from coverage and overlap-brightness constraints. Other coverage holes still block completion and request additional directions.

The fill maps measured neighbouring ground onto a tangent plane, solves bounded smooth log-lighting inside the excluded area, and adds modest detail from a low-variance measured ground patch. A mirrored texture extension avoids tile discontinuities; tangent-plane sampling avoids polar streaks. The cap is blended at its boundary and the JPEG preview regenerated. The report, app details and HDR/EXR comments identify the approximation. This is useful lighting cleanup, not recovery of the true occluded geometry, shadows, or objects.

## One retained HDR master

New captures default to one ZIP-compressed FLOAT OpenEXR master plus a small JPEG preview and metadata. Radiance HDR is an alternative chosen before capture. Existing captures keep their old format until the user chooses “Store as EXR” or “Store as HDR” in Storage. Exporting the other format converts into temporary storage and removes that temporary file on completion or cancellation.

EXR uses lossless ZIP compression; all output floats are read back and compared before an old master is discarded. HDR conversion is checked within RGBE's shared-exponent precision. Conversion commits the new file, then the atomic manifest, then removes the old file. Cancellation before the commit retains the original. This EXR preserves the current pipeline's saved radiance values; converting existing RGBE output to FLOAT does not recover precision already lost upstream. See [OpenEXR technical introduction](https://openexr.com/en/latest/TechnicalIntroduction.html).

Storage separates source photos, recoverable processing files, HDR master, preview, metadata and total. Abandoned timestamped JPEGs, partial outputs and stale duplicate masters are recoverable. Original-bundle export only includes declared source files and current outputs. Source deletion still requires confirmation and disables rebuilding. Home also exposes abandoned temporary-export cleanup and distinguishes capture data from installed app/native-library size.

## Verification

- 37 JVM checks cover radiometry, EXR blocks and truncation, camera crop/preview inverses, planner coverage and existing capture behaviour.
- Installed-APK gate requires 31 tests, including GPU diffuse/exposure checks, GPU field-of-view changes, ground-fill boundaries and pole continuity, cancelled and successful master migration, orphan cleanup, UI controls, and complete sample processing with the EXR master.
- The daylight room replay omits the nadir photo and finishes with 5,114,507 bytes retained: EXR 4,806,089; JPEG 252,571; metadata 55,847. The 4K night replay retains 17,391,166 bytes: EXR 16,890,336; JPEG 431,783; metadata 69,047. Both have zero source and processing bytes after explicit cleanup of the extracted copies.
- Both real-input replays complete with a 192 MiB Java heap limit (native OpenCV allocations are separate). Host times were 33.7 s and 39.6 s; these are not Pixel 8 timing measurements.
- Pixel 8 ultrawide stream negotiation, viewfinder alignment, focus and delivered JPEG calibration need a physical-phone check. Emulator tests cannot establish those hardware properties.

Private photographs and rebuilt images are kept outside this public repository.

### Published Preview 19

[Release and APK](https://github.com/otdavies/android-hdri/releases/tag/v0.1.0-preview.19), from source `870315d8873b53731cab0727183895492ce753f3`. [GitHub run 34062450038](https://github.com/otdavies/android-hdri/actions/runs/34062450038) passed build, device and publication jobs. All 32 JVM tests passed; lint reported no errors and eight advisory warnings. The 30 installed-APK tests passed in 58.111 seconds. The independent OpenEXR 3.3.3 decoder confirmed exact FLOAT channels and ZIP/raw blocks.

The GPU directional-grey fixture produced sRGB values 95, 118 and 136 for three sampled normals, exactly matching its analytical expectations. Actual rendered field-of-view changes passed at 0.5×, 1× and 4×. Screenshots of lighting controls, ground options and source-removal storage were reviewed.

Published APK: 123,971,321 bytes; SHA-256 `14a81f33d58a66202e72641b798447eaae8533805201902ea5d879e3f3672504`. Pixel 8 physical lens validation remains outstanding.

### Published Preview 20

[Release and APK](https://github.com/otdavies/android-hdri/releases/tag/v0.1.0-preview.20), from source `f9b83f6d0b7cdcbb304597aadee9ab195edf20cf`. [GitHub run 34069021717](https://github.com/otdavies/android-hdri/actions/runs/34069021717) passed build, device and publication jobs. All 37 JVM tests passed; lint reported no errors and eight advisory warnings. The 31 installed-APK tests passed in 68.767 seconds, and the independent OpenEXR 3.3.3 checks passed.

The new GPU regression renders a uniform environment with radiance 500: Scene 1× yields approximately 118/255 on the mirror and 50/255 on the 18% sphere, instead of a whiteout. The default diffuse meter / shoulder checks approximately 200/255 mirror and 108/255 grey (GPU tolerance three code values). The unchanged analytical directional-grey reference still renders 95, 118 and 136 exactly. Verification screenshots show the new exposure references and highlight switch without clipped controls.

Published APK: 124,004,089 bytes; SHA-256 `0c604bd4d3346afab0fbe5182c5f8b06e3f0eeafdf6fd2f7b830e71cd6257324`. The checked device-verification archive has SHA-256 `069c0d5ad401807ede1012eea12bef1360afb9495951e4b5f8353e2fdd3a6877`. Pixel 8 logical-ultrawide negotiation and geometry remain hardware validation items.
