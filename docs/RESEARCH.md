# Research and implementation decisions

Research date: 2026-09-06. Goal: a captured, full-sphere radiance environment processed on a Pixel 8, with observable progress and retained source data. Papers below inform the design; listing a method does not mean this app implements that entire paper.

## HDR reconstruction is distinct from display HDR

A tone-mapped panorama or an SDR JPEG stored in a floating-point container is not an HDR light probe. We need observations at multiple measured exposures and an inverse camera response. For a static pixel, a response-linearized measurement is proportional to scene radiance times shutter duration and sensor gain. This preview normalizes using the *reported* Camera2 exposure and ISO, merges consistent observations, and exports relative radiance without per-view normalization. Display tone mapping happens only in the JPEG preview.

| Source | Finding relevant to this app | Decision and implementation boundary |
|---|---|---|
| [Debevec & Malik, SIGGRAPH 1997](https://www.pauldebevec.com/Research/HDR/) | A camera response can be recovered from differently exposed photographs, yielding radiance up to a scale factor. | OpenCV response calibration and a saturation-weighted linear merge. Report relative radiance; no absolute lux/candela claims. |
| [Hasinoff et al., SIGGRAPH Asia 2016 / Google HDR+](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/) | Raw burst alignment and merging reduce noise and improve dynamic range on mobile cameras. | Informs preserving originals and avoiding changing image-processing parameters. This preview uses JPEG brackets, not Google's RAW HDR+ implementation. |
| [Google HDR+ with Bracketing, 2021](https://research.google/blog/hdr-with-bracketing-on-pixel-phones/) | Adding longer exposures improves shadows; frame alignment and handling motion are central to quality. | Three/five exposures, measured metadata, brightness-invariant bracket alignment and conservative disagreement rejection. No claim to reproduce Pixel Camera HDR+. |
| [Brown & Lowe, Automatic Panoramic Image Stitching using Invariant Features, IJCV 2007](https://mattabrown.github.io/autostitch.html) | Feature matching, camera estimation and blending are separate parts of a panorama pipeline. | ORB/RANSAC overlap constraints refine AR orientations, followed by spherical warping and multiband composition. We use a bounded rotation optimizer, not a general structure-from-motion solver. |
| [Nie et al., ICCV 2023, Parallax-Tolerant Unsupervised Deep Image Stitching](https://openaccess.thecvf.com/content/ICCV2023/html/Nie_Parallax-Tolerant_Unsupervised_Deep_Image_Stitching_ICCV_2023_paper.html) | Flexible warp and composition models address errors that a single homography cannot. | Research candidate for local refinement. Not shipped: two-view learned stitching does not establish full-sphere radiometric consistency or Pixel 8 performance. |
| [Liao et al., ICCV 2025, LPAM](https://arxiv.org/html/2311.18564v2) and [author implementation](https://github.com/tlliao/LPAM_seam-cutting) | Locally aligning problematic patches around a seam can improve large-parallax results after coarse alignment. | Informs evaluating overlap disagreement and isolating seam concerns. Current code uses discrete seam-label optimization and multiband blending; it does **not** implement LPAM or SIFT flow. |
| [PIS3R, 2025 preprint](https://arxiv.org/html/2508.04236v1) | Deep 3D reconstruction, reprojection and diffusion-based refinement address very large parallax. | Not suitable as the default measured-light pipeline without radiometric and mobile validation. Synthesizing plausible pixels does not measure missing illumination. No diffusion model or server is used. |

## Android capture choices

[ARCore's shared-camera guide](https://developers.google.com/ar/develop/java/camera-sharing) documents a Camera2/ARCore session with wrapped callbacks and additional still-image surfaces. It also warns that extra streams increase device demands. We use one optional JPEG surface, choose its aspect ratio to match the tracked CPU image, and disable plane detection and light estimation. Capture requires manual sensor and fixed tone-curve controls; unsupported combinations produce explicit errors.

The app pauses ARCore for each manual bracket, shows a held camera preview with exposure progress, and resumes tracking after the bracket. During that pause it retains the last tracked camera pose and rejects excessive gyroscope motion. This is an orientation **prior**, not an assertion that the pose was sampled at the precise midpoint of each still exposure. Later image matching refines orientations. Actual image buffers and [Camera2 capture results](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP) are joined by the sensor timestamp, never callback order.

Shared RAW + ARCore streams are not assumed to work across devices. A production RAW path should explicitly test Pixel 8 stream combinations and, if necessary, switch to an exclusive Camera2 RAW session while retaining inertial orientation. That also requires demosaicing, black/white levels, color matrices, lens shading, calibration and motion alignment. Merely adding DNG export would not complete this path.

Captured JPEGs use fixed daylight balance and an explicit tone curve. An environment should preserve the colors of illuminants rather than re-white-balance every view. Even with fixed requests, ISP processing and Camera2/ARCore crop calibration need physical-device validation. JPEG response estimation remains an approximation to the underlying sensor response.

## Geometry, seams and memory

Targets are generated from the smaller measured field of view, with overlapping elevation rings and explicit pole captures. The design asks the user to rotate around the camera lens. It pauses auto-capture beyond 12 cm translation from the capture origin and rejects more than 1.2 degrees integrated gyro movement during a bracket. These are initial engineering thresholds, not scientifically validated universal bounds.

Every output texel is converted to a spherical ray and projected into candidate cameras. This handles the longitude seam and poles without a single global planar homography. Matching uses only plausible overlapping views, rejects gross disagreement with AR priors and distributes residual rotation corrections across the graph. Blank sky may rely on AR orientation and generates a review warning.

Seams are selected in a reduced panorama using per-view validity and an edge-aware label objective. Five-level float Laplacian blending is performed in tiles with a 64-pixel halo. This preserves high-range values and bounds working memory. It does not turn parallax into correct geometry. Missing coverage is detected and can add capture directions; substantial gaps fail processing instead of being generated or silently painted over.

## Lifecycle and progress

Processing is user initiated and runs in a [mediaProcessing foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#media-processing) on Android 15+, with the documented local-processing dataSync type on earlier supported releases. The service handles cancellation and the system timeout, reports stages in-app and in a notification, checks free storage, and waits visibly during severe thermal throttling. Its wake lock is bounded. Process death leaves a recoverable manifest and per-direction HDR checkpoints.

## What would justify a production-quality claim

1. Physical Pixel 8 measurements of intrinsics/crop correspondence, lens shading, tone response and HDR linearity with fixed test lights.
2. Repeatable full 360×180 coverage, seam continuity and a known-radiance-ratio scene across indoor, outdoor and low-light captures.
3. Motion/parallax stress cases, clipped light-source reporting and explicit limits on usable capture conditions.
4. Peak Java/native memory, runtime, heat and battery measurements for real 4K capture sessions.
5. Comparisons against a reference desktop stitching/HDR workflow on the same preserved exposure files, before adopting learned or dense local-warp methods.

The exact device checklist is tracked separately. A passing emulator suite alone is insufficient evidence for these claims.
