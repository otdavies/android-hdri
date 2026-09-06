# Pixel 8 device acceptance

Status: installation/UI work and handheld capture improved on Pixel 8. The user then reported AR relocalization moving the assumed sphere center while pointing at sky, and excessive (74+) stops. The sky update uses continuous inertial orientation and compact camera-aware coverage. Physical validation of this change is pending.

## Installation and startup

- Install the GitHub Release APK directly on Pixel 8. Reinstall the next APK without uninstalling; verify saved captures survive.
- Grant/deny/regrant camera permission. Verify ARCore installation messaging and airplane-mode operation after its system component is installed.
- Start real capture. Confirm the main rear camera negotiates AR + JPEG streams, preview orientation matches the phone, dots align with real objects and no background camera access continues after leaving.

## Capture quality

- With the handheld update, align a dot while breathing normally. Confirm automatic capture fires without tapping and without trying to be perfectly motionless. Follow horizontal/vertical arrows, try a rolled phone, and capture both poles; leveling is optional.
- Make a large shake during a bracket. Confirm it automatically settles for another attempt, with an inline explanation. Small motion should keep the photos and preserve a review finding when appropriate.

- Capture a static outdoor sphere around one lens position. Verify all rings and both poles are captured with sufficient overlap and no fixed-point drift.
- Check the camera2-reported shutter/ISO in the exported manifest: each bracket must have distinct measured exposures with no duplicate/mismatched timestamps.
- Inspect the fixed daylight white balance and tone curve across bright/dark views. Compare projected features against stored intrinsics to detect camera stream crop differences.
- Start while pointing at blank sky; capture the zenith, tilt back to a recognizable horizon feature, and repeat after several brackets. The targets must return to the same directions and never request walking or position correction.
- Compare the initial number of stops with the old plan; record actual lens FOV and count. Resume an old unfinished project and verify saved photos remain while remaining stops decrease.
- Move during a bracket: excessive angular movement should retry. Covering the lens or walking is no longer a tracking gate: the center is assumed fixed, so avoid walking and inspect resulting blur/parallax.
- Complete a horizon loop and record yaw drift against a recognizable feature. Repeat over a longer capture; test the real camera-to-JPEG orientation and final seam.
- Background the app halfway through a bracket. Reopen, match the reference and finish the sphere; no half-bracket may be counted.

## HDR and seams

- Import `.hdr` into Blender, Unity or Unreal. Verify 2:1 orientation, zenith/nadir placement, left/right closure and values above 1. Inspect exposure adjustments to confirm highlight information beyond the JPEG preview.
- Capture a controlled scene with known exposure/radiance ratios. Compare reconstructed ratios against a reference before claiming calibrated lighting accuracy.
- Inspect close objects, windows, poles, sun cores, low-texture sky and moving people. Record alignment residuals, clipping warnings and visible artifacts.
- Compare against an offline reference stitch using the exported original brackets. Measure error, not just subjective attractiveness.

## Reliability and performance

- Record wall time, peak native/Java memory, thermal status and battery use for 2K and 4K captures.
- Pause during HDR merging and during tile blending; resume and verify output integrity. Kill the process mid-stage, then restart and recover from the saved manifest.
- Test low storage before capture, mid-capture and at export. Fill or revoke the document destination and ensure original files remain usable.
- Test denial of notification permission; progress must remain visible in-app. Verify the notification opens the app, pause works and the foreground service stops on completion/failure.
- Test font scaling and TalkBack on setup, processing, quality review and export controls.

The hardest pending quality work is RAW sensor reconstruction, camera-specific calibration, reliable automatic relocalization for resumed captures and dense correction of parallax. Keep failures and representative source bundles for the next iteration.
