# Lighting reference, wider lenses, ground fill and storage

## Lighting reference

The diffuse calculation remains Lambertian: outgoing radiance is reflectance / π times the cosine-weighted incident-light integral. A uniform environment of radiance L therefore produces 0.18 L from the grey sphere. Brightening that material alone would be physically wrong. See [PBRT: diffuse reflection](https://pbr-book.org/4ed/Reflection_Models/Diffuse_Reflection).

The previous display divided by the arithmetic mean of the raw environment and then applied Reinhard tone mapping. Bright emitters strongly influence that mean; Reinhard also moves an exposed 0.18 midtone down to about 0.1525 before display encoding. The default now meters the geometric mean of *received diffuse light* and uses the sRGB display transfer without a tone curve. Exposed linear 0.18 maps to approximately 0.461 sRGB (118/255). This is a display code value, not 46% physical luminance.

“Meter grey”, “Capture exposure” (the median middle-bracket exposure including ISO gain), and “HDR 1×” are explicit reference choices. Exposure adjustments affect both balls and the environment equally. Reinhard remains available for viewing highlights. An optional environment background gives lighting context. These JPEG-derived HDRIs have relative radiance; the renderer cannot infer scene lux or an absolute camera exposure without photometric calibration.

Explore HDR supports pinch zoom and zoom buttons from 0.5× to 4×. Zoom changes perspective field of view and scales panning sensitivity. Reset restores the view and exposure. Two-finger gestures do not accidentally pan, and the surviving pointer is rebased when a pinch ends.

## Lens selection and capture pace

The existing main-camera ARCore preview remains available. Other eligible rear cameras use a native Camera2 preview, the same inertial guidance, and the same forgiving focus / steadiness gate. The app discovers public and logical-camera physical IDs, rather than assuming camera “2” or a universal 0.5× zoom ratio.

Eligible lenses must expose manual sensor controls, fixed daylight white balance, a fixed tone curve, and HIGH_QUALITY geometric correction. Preview and JPEG outputs are pinned to the selected physical ID; physical request overrides and physical result metadata are used. Missing physical metadata or a rejected stream combination produces a visible error, rather than quietly capturing through another lens. Android does not guarantee arbitrary physical JPEG stream combinations: see [multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera).

Each lens uses its own intrinsic calibration, active-array and stream crop, sensor orientation, and per-frame metadata. Where intrinsic calibration is absent, sensor dimensions and focal length provide an initial estimate; the existing visual registration refines focal scale. Camera2's corrected metadata is an approximate mapping, so device validation is still required. See [intrinsic calibration](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LENS_INTRINSIC_CALIBRATION) and [distortion correction](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#DISTORTION_CORRECTION_MODE). Preview cropping and aim-marker placement share inverse transforms.

Compact / More overlap / Most overlap use 6.75° / 10° / 13° footprint erosion. Plans prove cropped coverage with aim and roll allowance. A representative 68° × 54° main lens needs 41 stops; 112° × 96° needs 17, or 16 with ground fill. Most overlap needs 19 for that wider lens with ground fill. These are fixture counts, not measured Pixel 8 claims. Setup estimates from available calibration, then capture plans from the active stream.

## Optional ground fill

“Fill below me” omits targets below −55° pitch. The nadir cap (35° from straight down) is explicitly excluded from coverage and overlap-brightness constraints. Other coverage holes still block completion and request additional directions.

The fill maps measured neighbouring ground onto a tangent plane, solves bounded smooth log-lighting inside the excluded area, and adds modest detail from a low-variance measured ground patch. A mirrored texture extension avoids tile discontinuities; tangent-plane sampling avoids polar streaks. The cap is blended at its boundary and the JPEG preview regenerated. The report, app details and HDR/EXR comments identify the approximation. This is useful lighting cleanup, not recovery of the true occluded geometry, shadows, or objects.

## One retained HDR master

New captures default to one ZIP-compressed FLOAT OpenEXR master plus a small JPEG preview and metadata. Radiance HDR is an alternative chosen before capture. Existing captures keep their old format until the user chooses “Store as EXR” or “Store as HDR” in Storage. Exporting the other format converts into temporary storage and removes that temporary file on completion or cancellation.

EXR uses lossless ZIP compression; all output floats are read back and compared before an old master is discarded. HDR conversion is checked within RGBE's shared-exponent precision. Conversion commits the new file, then the atomic manifest, then removes the old file. Cancellation before the commit retains the original. This EXR preserves the current pipeline's saved radiance values; converting existing RGBE output to FLOAT does not recover precision already lost upstream. See [OpenEXR technical introduction](https://openexr.com/en/latest/TechnicalIntroduction.html).

Storage separates source photos, recoverable processing files, HDR master, preview, metadata and total. Abandoned timestamped JPEGs, partial outputs and stale duplicate masters are recoverable. Original-bundle export only includes declared source files and current outputs. Source deletion still requires confirmation and disables rebuilding. Home also exposes abandoned temporary-export cleanup and distinguishes capture data from installed app/native-library size.

## Verification before device release

- 32 JVM checks cover radiometry, EXR blocks and truncation, camera crop/preview inverses, planner coverage and existing capture behaviour.
- Installed-APK gate requires 30 tests, including GPU diffuse/exposure checks, GPU field-of-view changes, ground-fill boundaries and pole continuity, cancelled and successful master migration, orphan cleanup, UI controls, and complete sample processing with the EXR master.
- The daylight room replay omits the nadir photo and finishes with 5,114,507 bytes retained: EXR 4,806,089; JPEG 252,571; metadata 55,847. The 4K night replay retains 17,391,166 bytes: EXR 16,890,336; JPEG 431,783; metadata 69,047. Both have zero source and processing bytes after explicit cleanup of the extracted copies.
- Both real-input replays complete with a 192 MiB Java heap limit (native OpenCV allocations are separate). Host times were 33.7 s and 39.6 s; these are not Pixel 8 timing measurements.
- Pixel 8 ultrawide stream negotiation, viewfinder alignment, focus and delivered JPEG calibration need a physical-phone check. Emulator tests cannot establish those hardware properties.

Private photographs and rebuilt images are kept outside this public repository.
