# Handheld stitching: radiometry, parallax and seams

The supported capture model is a person turning with their feet and tilting the phone. The optical centre moves around the person. The supplied fast capture is treated as representative good handheld input, not a failed attempt to use an imaginary tripod. Additional aiming dwell does not remove between-view parallax.

## Findings and changes

### Contrast was being destroyed before stitching

The previous pipeline estimated a free 256-entry camera response from one bracket, selected for high image contrast. In the supplied daylight capture, pooling the three estimated channels and taking a monotonic envelope produced **50 flat adjacent code intervals**. Entire brightness ranges lost contrast. This explains much of the flat grey carpet, curtain and ceiling detail; it is separate from geometric alignment.

The camera already requests an identical, fixed, 16-point RGB contrast curve. Capture and reconstruction now share its definition, and reconstruction inverts that piecewise-linear curve. The synthetic sample uses inverse sRGB because that is how its images are generated. A new checkpoint version invalidates the old merges. The chosen response model is retained in the processing report after temporary files are cleared.

Camera2 describes the control-point tone-curve interface in its [TonemapCurve reference](https://developer.android.com/reference/android/hardware/camera2/params/TonemapCurve). Inverting the requested curve removes the underconstrained scene-dependent fit. JPEG processing, clipping, colour transforms and sensor noise still limit radiometric accuracy; these files are relative HDR environments, not calibrated photometry.

### Rotation alone does not describe a handheld orbit

RootSIFT correspondences and robust rotation/focal refinement provide the coarse spherical mapping. They are followed by jointly fitted local warps, rather than independently moving each image toward pairwise midpoints.

Each camera has a 13 × 9 inverse warp grid. Correspondence constraints couple both images in world-ray coordinates. Robust reweighting limits inconsistent matches; bounded votes prevent one textured surface from overwhelming the solve. Weak displacement and first-derivative priors plus second-derivative smoothness constrain unsupported regions. A matrix-free conjugate-gradient solve avoids a large dense panorama-wide matrix. Finite displacement, triangle-area and residual checks guard against folds and failed fits. Views that violate the shape bounds receive stronger regularization before the coupled system is solved again, so one difficult view does not unnecessarily weaken correction across the whole panorama.

Local matching uses log-radiance detail from the merged brackets at a bounded resolution. Forward/backward flow, overlap support, local texture and patch agreement filter its correspondences. Coarse registration retains the camera image: experiments that enhanced every registration image changed the global solution unfavourably on this capture.

This follows the distinction between global geometry, local deformation and compositing in [Szeliski's alignment and stitching tutorial](https://www.microsoft.com/en-us/research/wp-content/uploads/2004/10/tr-2004-92.pdf). [Zhang and Liu's parallax-tolerant stitching](https://web.cecs.pdx.edu/~fliu/project/stitch/) makes the useful practical point that alignment around a seam matters more than forcing incompatible depths to agree everywhere. The implementation here is a bounded full-sphere solver, not a reproduction of that paper's code.

### Exposure and lens shading must agree in overlaps

A new radiometric stage estimates per-view RGB log gains and a shared radial correction from smooth overlapping scene locations. It does not equalize image histograms: a window and a dark wall must remain different in the HDR environment.

Samples exclude sharp boundaries and large colour disagreements, where parallax, occlusion or changing reflections would bias the fit. Each overlap contributes a bounded number of samples. Robust fitting uses 80% of the samples; the remaining 20% must show a lower median transfer error without materially worsening the 90th percentile. Accepted channels are then refit using all samples. Per-view corrections are bounded to ±0.6 EV and the radial coefficient to ±0.5 EV. Regularization fixes the scale ambiguity and leaves unsupported views unchanged.

These choices draw on [d'Angelo's radiometric alignment and vignetting calibration](https://hugin.sourceforge.io/tech/icvs2007_final.pdf): geometrically corresponding low-gradient samples, robust estimation and separate exposure/shading parameters. The app uses a simpler bounded log-linear radial model suitable for the existing fixed-tone Camera2 capture path. Corrections are applied in linear radiance once per decoded render frame. The report includes withheld-sample errors and correction magnitudes.

### A seam change must account for its neighbours

Binary seam swaps now include the boundary costs against fixed third-image labels. Previously an a/b swap could improve that overlap while damaging an adjacent seam. The cost also considers image gradients, so clear structure contributes to seam placement. Source-centre preference remains: substantially weakening it produced unacceptable object-boundary results in an experiment and was rejected.

Coverage now uses sampled image support with edge weighting, instead of discarding an arbitrary 4.5% rim from every source. This is not hole filling. Missing measured support still blocks completion above the coverage threshold. Multiband blending remains in linear HDR; it cannot substitute for correct geometry.

## Recent research reviewed

| Work | Useful lesson for sphere | Deployment decision |
| --- | --- | --- |
| [LPAM / local patch alignment and seam cutting, ICCV 2025](https://arxiv.org/abs/2311.18564) | Evaluate poor seams, realign their local patches, and constrain how corrected patches reconnect. | The local refinement strategy is relevant. We do not claim to reproduce its SIFT-flow implementation or published benchmarks. |
| [Warping Residual Based Image Stitching, CVPR 2020](https://openaccess.thecvf.com/content_CVPR_2020/html/Lee_Warping_Residual_Based_Image_Stitching_for_Large_Parallax_CVPR_2020_paper.html) | Residual alignment and local deformation are needed after a global transform. | Supports a bounded residual stage; no unverified mobile performance claims. |
| [SENA, January 2026 preprint](https://arxiv.org/html/2601.01257v1) | Flexible alignment needs structure preservation and spatial confidence. | Reviewed as a two-view research method, not evidence of full-sphere HDR or Pixel 8 performance. |
| [RopStitch, 2025](https://arxiv.org/html/2508.05903v3) | Robust matching and the choice of projection plane are both important. | No learned model or additional runtime is shipped in this update. |
| [Generative Panoramic Image Stitching](https://arxiv.org/html/2507.07133v1) | Generative completion can improve apparent continuity. | Generated pixels cannot be treated as measured lighting. No diffusion-based filling is used. |

Classical radiometry and multiview geometry remain essential alongside recent stitching work. A plausible-looking seam is insufficient for an environment intended to illuminate a film asset.

## Validation scope

- Private source captures stay outside this public checkout. The replay runner executes the production Kotlin processing code against host OpenCV; it is a reproducible algorithm comparison, **not a Pixel 8 timing measurement**.
- A new physical orbit fixture places cameras at different positions on a 0.32 m orbit around a surface with varying depth. Entire landmarks are withheld from fitting and evaluated afterwards.
- A radiometric fixture combines real spatial lighting variation, exposure differences, radial shading and a moving foreground patch. It checks overlap agreement and preservation of the scene's lighting gradient.
- A wide-bracket fixture verifies that the controlled camera response retains brightness slopes instead of flattening shadow and midtone ranges.
- Existing installed-app checks continue to cover the capture UI, cancellation/checkpoint reuse, storage cleanup, HDR/OpenEXR export and lighting-sphere rendering.

Real-capture measurements and release verification are recorded below. Passing synthetic tests does not establish Google Photo Sphere parity. Textureless ceilings, occlusions, repeated curtains and genuinely blurred source detail remain important visual checks.


## Real capture results

Both private datasets completed with full measured coverage in the replay. The daylight capture contains 41 directions × 3 exposures (2K output); the earlier capture contains 41 × 5 exposures (4K output).

| Measurement | Daylight / fast | Earlier / detail |
| --- | ---: | ---: |
| Median overlap brightness error before correction, withheld samples | 0.103 EV | 0.116 EV |
| Median overlap brightness error after correction, withheld samples | 0.043 EV | 0.040 EV |
| Median matched-ray disagreement before joint local correction | 0.518° | 0.605° |
| Median matched-ray disagreement after correction | 0.045° | 0.051° |
| 90th-percentile matched-ray disagreement after correction | 0.155° | 0.192° |

Matched-ray errors are residuals on accepted correspondences, not absolute geometric accuracy or proof of correct matching in repeated textures. The independent orbit fixture evaluates withheld landmarks: median error falls from 2.99° to 0.074°, and the 90th percentile from 4.33° to 0.131°.

Visual comparison confirms substantial recovery of texture lost to the response estimate and reduced brightness discontinuities. It also identifies remaining curtain and ceiling joins. Experiments with globally enhanced registration images, a strongly fixed focal length, and weak source-centre seam preference were rejected because they damaged coverage or object boundaries. An additional dense-refinement pass was also withheld: it increased runtime without a sufficient visual benefit.

This update does not yet meet the requested Google Photo Sphere quality bar. The remaining priority is reliable correspondence and seam-local structure preservation in weak or repetitive areas, including cycle consistency across multiple views. A lower fitting residual alone must not justify a release that visibly tears objects.

A separate seam-only flow experiment evaluated 118 adjacent image pairs and accepted 28 local corrections using forward/backward consistency, patch agreement, and seam residual checks. Its visual improvement was modest and estimating the fields alone added about eight host seconds. It is not included in the app. This result points toward better seam-local correspondence and structure constraints, rather than adding another unconditional flow pass.

To reproduce a private capture with the production processor and OpenEXR writer:

```sh
python3 scripts/replay.py /private/capture.zip --work-dir /private/replay --export-exr
```

Keep the work directory outside the public checkout. `HOST_SECONDS` measures processing; `EXR_EXPORT_SECONDS` reports the subsequent export separately. The JPEG is a tone-mapped preview; the HDR and EXR retain the same relative linear lighting values, including the existing RGBE storage quantization. Rebuilding cannot recover information already clipped or blurred in the source photographs.

### Release memory and lifecycle checks

The final daylight replay took 38.18 host seconds versus 36.54 seconds for the previous processor, with another 0.74 seconds for EXR export. The full earlier 4K capture took 50.26 seconds of processing. These are single sequential host runs, not phone benchmarks.

A constrained replay (`--heap-mib 192`) exposed an unnecessary all-view gradient allocation during seam optimization. Only the active image pair now retains gradient caches; fixed third-label gradients are evaluated as needed. The complete 41-view capture then finished within the 192 MiB Java heap limit, producing byte-identical JPEG and HDR output. Native OpenCV allocations are separate from this limit; it is not a claim of 192 MiB total process memory.

The larger detail capture additionally exposed whole-image Java copies in the float checkpoint reader. It now streams at most 64 rows at a time into the native image. The full 4K capture also completes under the 192 MiB Java heap limit with byte-identical JPEG and HDR results. The installed lossless round-trip fixture spans multiple complete row blocks and a partial final block, including radiance values above the half-float range.

Installed-APK testing also exposed a native DIS cleanup crash when matching exited before its first flow calculation. Cleanup now avoids that uninitialized path, input checkpoints are validated as float RGB, and an installed test exercises empty input and early cancellation. The registration fixture now supplies a real float checkpoint to the HDR-detail stage. Publication still requires the complete installed-app suite, including these paths.

## Verified Preview 18

[Install the signed APK](https://github.com/otdavies/android-hdri/releases/download/v0.1.0-preview.18/sphere-0.1.0-preview.apk), then choose **Rebuild from saved photos** on an existing capture whose sources are retained.

- Source: `db2f5723c20cb6dee2adad16a3614f62a109cf8f`.
- [Actions run 34054299399](https://github.com/otdavies/android-hdri/actions/runs/34054299399): successful build, device verification and publication of the same APK.
- 28 JVM tests; no lint errors; **26 installed-APK tests passed in 58.645 seconds** on API 36 x86_64 AOSP.
- OpenEXR 3.3.3 independently verified exact float channels, HDR range, compressed/raw ZIP blocks and image orientation.
- The physical-orbit fixture passed on Android: withheld-landmark median 2.987° → 0.074°, p90 4.332° → 0.131°.
- Analytic full-sphere fixture: full coverage, median relative radiance error 5.98%, 10.727 seconds of emulator processing. This is a small synthetic fixture, not a real-capture speed claim.
- Final-source daylight replay: 40.36 host seconds with a 192 MiB Java heap cap; EXR export 0.79 seconds. Earlier individual runs were 37–38 seconds. All produced identical JPEG/HDR output; this is not a controlled speedup measurement or a Pixel 8 benchmark.

APK SHA-256: `b94fbf8e36481254ae9a6398b2bbd9d5ed00965e9f365d9b2a7f01f95f0b9922` (123,856,633 bytes). The GitHub asset digest matches this value. The device verification archive was downloaded and its SHA-256 checked before inspecting its measurements and rendered lighting screen.

[Machine-readable release evidence](evidence/handheld-preview18.json) contains the release identifiers and checks. Private room photographs and source bundles are not included in this public repository.
