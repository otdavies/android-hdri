# Pixel 8 device acceptance

Status: the user confirmed installation and UI work on Pixel 8, but reported excessive movement rejection during real photosphere capture. The handheld-capture update addresses that report; successful full real-world capture, stitching quality and performance still need phone verification. Record APK version/commit, Android build, ARCore version and actual results here.

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
- Move during a bracket, walk away from the origin, cover the lens and pause tracking. Confirm auto-capture stops, rejects bad brackets and offers clear recovery.
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
