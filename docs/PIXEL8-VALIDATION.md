# Pixel 8 device acceptance

Status: the user can complete full captures on Pixel 8 using inertial orientation and the compact camera-aware coverage plan. The current acceptance concern is reconstructed image quality: brightness differences, grey patches and geometric tears. The supplied fast capture is representative average-to-good handheld input. The latest processor has been evaluated using source replays and installed-app tests; its physical Pixel 8 timing and visual acceptance still need user evaluation.

## Installation and startup

- Install the GitHub Release APK directly on Pixel 8. Reinstall the next APK without uninstalling; verify saved captures survive.
- Grant/deny/regrant camera permission. Verify ARCore installation messaging and airplane-mode operation after its system component is installed.
- Start real capture. Confirm the main rear camera negotiates AR + JPEG streams, preview orientation matches the phone, dots align with real objects and no background camera access continues after leaving.

## Capture quality

- With the handheld update, align a dot while breathing normally. Confirm automatic capture fires without tapping and without trying to be perfectly motionless. Follow horizontal/vertical arrows, try a rolled phone, and capture both poles; leveling is optional.
- Make a large shake during a bracket. Confirm it automatically settles for another attempt, with an inline explanation. Small motion should keep the photos and preserve a review finding when appropriate.

- Capture a static outdoor sphere while turning with the feet and tilting the phone normally. The optical centre is expected to move on an orbit around the person. Verify all rings and both poles have sufficient overlap and the guidance does not request position corrections.
- Check the camera2-reported shutter/ISO in the exported manifest: each bracket must have distinct measured exposures with no duplicate/mismatched timestamps.
- Inspect the fixed daylight white balance and tone curve across bright/dark views. Compare projected features against stored intrinsics to detect camera stream crop differences.
- Start while pointing at blank sky; capture the zenith, tilt back to a recognizable horizon feature, and repeat after several brackets. The targets must return to the same directions and never request walking or position correction.
- Compare the initial number of stops with the old plan; record actual lens FOV and count. Resume an old unfinished project and verify saved photos remain while remaining stops decrease.
- Move during a bracket: excessive angular movement should retry. Between-view translation from normal turning is supported input and must be handled in reconstruction. Orientation tracking does not measure camera translation; a stored zero position is a placeholder, not evidence of a fixed optical centre.
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

The hardest pending quality work is reliable correspondence and structure preservation around weak or repetitive seams, occlusions, resumed-capture alignment and camera-specific radiometry. RAW reconstruction remains a possible later camera-path improvement. Keep representative source bundles and assess visible edges as well as numerical fitting errors; additional aiming pauses are not a solution to ordinary between-view parallax.

## Wider lenses and compact storage

- Choose Ultrawide in New photosphere. Check that its actual view is wider, the physical lens does not switch, and dots stay on the preview features while tilting. Capture a main-lens and ultrawide sphere from the same area and inspect straight lines and exposure matching. Unsupported stream combinations should display an error with the main-lens recovery option.
- Compare Compact and Most overlap stop counts. Check focus/settling and auto-capture with both; no stricter steadiness threshold is introduced.
- Try Fill below me on a textured floor: no straight-down dot, no feet in the final cap, and approximate ground is labelled. Check sky and normal coverage holes still require capture.
- In Lighting spheres, compare Meter grey, Capture exposure, Scene 1×, ±1 EV and the environment background. Explore HDR should pinch smoothly, zoom buttons agree, and reset returns to 1×.
- After a 4K build, inspect the master/preview breakdown. Convert formats, cancel a conversion, export both formats and remove sources. Viewing/export must survive cleanup and restart. Source removal must disable rebuild. Android Settings includes installed code in addition to capture data.

### Exposure and ultrawide follow-up

- Default **Smooth highlights** should show detail in chrome and the environment with only a small grey-level change. Toggle it off to inspect reference sRGB clipping. Exposure controls remain shared by both materials and the environment.
- **Scene 1×** should show a normally exposed environment, including older captures with large numeric radiance; it must not turn an ordinary room uniformly white.
- After camera permission, look for **Ultrawide · 0.5×** (the label follows Android's actual minimum). Prefer that logical option first; direct physical options remain available. **Lens details → Copy details** includes zoom ranges and discovery decisions if the lens is still missing.
- Compare framing at the same pose between main and ultrawide. Confirm the ultrawide's wider preview agrees with target markers, requires fewer stops, and keeps the same crop through each bracket. A rejected/ignored zoom or hardware lens switch must produce a visible error instead of a corrupted mixed-lens sphere.
