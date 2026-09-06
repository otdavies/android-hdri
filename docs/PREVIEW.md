# Luma Sphere 0.1.0 · device-test preview

Download **hdri-0.1.0-preview.apk** below and open it on your Pixel 8. Allow installation from your browser/Files app when Android asks.

This preview includes gyro-guided automatic exposure brackets, fully on-device HDR reconstruction and spherical stitching, visible progress, pause/recovery, interactive preview and Radiance HDR / photosphere JPEG / source-bundle exports.

The handheld stitching update adds stronger visual matching, joint camera refinement, checked local warps and continuous graph-cut seams. It also fixes false color contours from clipped JPEG channels, accelerates radiance merging and rendering, and exposes stage timings. Open an existing completed project and choose **Rebuild from saved photos** to process it again without recapturing.

A private 41-direction replay reduced unmatched directions from 19 to 5 and fresh host processing from 58.1 to 49.1 seconds. This is host evidence, not a Pixel 8 speed promise. Some nearby-object and curtain seams remain imperfect. See [stitching measurements and release verification](https://github.com/otdavies/android-hdri/blob/main/docs/STITCHING-UPDATE.md).

The sky-capture update replaces AR position guidance with a continuous fused gyro/gravity reference and an assumed fixed center. Blank sky and AR relocalization no longer move the sphere or ask you to walk. Automatic capture remains forgiving, with directional glow, arrows, tilt guidance and a clear gyro status.

A rectangular-lens coverage planner substantially reduces redundant stops. The exact total is calculated from your camera, with margins for stitching crop and handheld aiming. Existing unfinished captures receive the smaller plan and keep their saved photos. Each stop still takes three or five HDR exposures automatically.
Start with **Explore a sample capture** to exercise processing without a camera, then try a static real scene. Keep the main camera lens at one physical point and complete the sky and ground.

HDR values are relative radiance reconstructed from JPEG exposure brackets; they are not RAW-derived or absolute photometric measurements. Real Pixel 8 shared-camera behavior, calibration and visual quality still need physical testing. Parallax, motion and clipped light sources can affect results. See the repository's Pixel 8 acceptance checklist and research notes.

The APK is a signed development preview. CI verifies the same binary that is published; physical sensor/camera testing is a separate step. All original photos stay on your phone unless you export them. Google Play Services for AR must be installed for capture; no backend or app Internet permission is used.
