# sphere 0.1.0 · exposure and ultrawide fixes

Download **sphere-0.1.0-preview.apk** and open it on your Pixel 8. It updates the existing app and preserves saved captures.

## Changes

- A slightly lower default meter (−0.25 EV) and **Smooth highlights** reduce over-bright chrome/environment previews. The shoulder preserves ordinary grey midtones and RGB ratios. The grey material remains 18% diffuse reflectance; both spheres and the environment share one exposure.
- **Scene 1×** replaces the raw **HDR 1×** setting that could turn a capture white. It establishes a scene-metered display reference, with the exposure slider operating relative to it. Display controls do not alter the saved HDR/EXR.
- Ultrawide discovery now includes Android's logical-camera zoom-out control, using the device's reported zoom range. It no longer requires HIGH_QUALITY correction if the camera exposes FAST, and physical-only cameras can inherit their logical camera's manual controls.
- Lens discovery refreshes after camera permission and on return to setup. **Lens details** shows a copyable capability report and explains rejected options.
- Native capture checks returned zoom, active lens and crop metadata. Android 15+ physical-crop metadata improves calibration where available. The chosen lens cannot silently switch during a capture.

## Verification

The release workflow runs 37 JVM checks, lint, and 31 installed-app tests against the exact signed candidate APK, including GPU checks for large HDR values, the highlight shoulder and 18% diffuse response. It also checks OpenEXR with an independent decoder.

The supplied daylight capture confirmed the old raw 1× bug: all channels clipped over 99% of the sphere. The new scene reference is derived from its actual saved radiance. Pixel 8 ultrawide stream negotiation, ISP correction and delivered image geometry still require a hardware check; emulator tests cannot establish those properties.

[Implementation, measurements and Android API sources](https://github.com/otdavies/android-hdri/blob/main/docs/FIELD-WORKFLOW.md)
