# sphere: light checks and clearer capture

The app is now **sphere**, with a warm neutral palette, a split-sphere launcher mark and Android themed-icon support. Its application ID, signing identity, session storage and HDR format remain compatible with previous Luma Sphere builds. Existing captures open without conversion or rebuilding.

## A lighting reference on location

Open a completed capture and tap **Check the light**. The default view renders an ideal mirror sphere beside an **18% neutral Lambertian sphere**, using the saved `environment.hdr`. **Explore HDR** shows the same environment directly. Dragging rotates the common reference; both materials share exposure from −8 to +8 stops. Reset restores orientation and 0 EV.

The renderer uploads linear RGB32F textures to OpenGL ES 3 and filters them explicitly, so it does not rely on optional float-linear filtering or discard bright sources at the half-float limit. The reflection map retains the saved 2K/4K panorama resolution, with a texture-size fallback for older GPUs. Decoded native image buffers are released before diffuse integration, and texture uploads stream in 64-row blocks rather than allocating another full panorama. Diffuse lighting uses solid-angle-weighted binning to 128 × 64 and positive cosine integration into a 64 × 32 map. The output is `0.18 / pi × integral(L × max(n dot w, 0) dw)`. This avoids the negative lobes that a short spherical-harmonics approximation can create around concentrated practical lights. Diffuse integration, texture preparation and first render have visible progress; all work remains local.

At 0 EV the solid-angle-weighted mean environment luminance is normalized to one. This is a repeatable viewing reference, not calibrated exposure or photometry. Display choices are component-wise Reinhard followed by sRGB, or linear radiance clipped to display white followed by sRGB. The chrome ball is an ideal unit mirror, not a measured chrome BRDF. The grey sphere is perfectly diffuse; neither ball has a synthetic key light, contact shadow or specular clearcoat. Exposure and view changes never modify exported HDR radiance. These are SDR inspection renders of HDR data, not an ACES or calibrated HDR-monitor preview.

The model follows [PBRT's Lambertian reflection](https://pbr-book.org/4ed/Reflection_Models/Diffuse_Reflection) and [NVIDIA's irradiance environment-map formulation](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-10-real-time-computation-dynamic).

## Fixing capture before stitching

The old 2.2° pose envelope could accept a slow pan. The new gate fits signed angular drift over 450 ms, requires at least 300 ms of settled history, then fills the 450 ms shutter ring. It accepts modest oscillating tremor while rejecting continuing turns. Movement clears shutter credit; the optional button also waits for a settled moment. The 4.5° entry / 6° exit aiming tolerance is retained.

Camera2 preview results now gate on fresh data and a settled focus distance. Before the JPEG burst, a short manual-focus preview verifies that the physical lens has reached the requested setting. Featureless sky has a timed distant-focus fallback with a recorded review finding, rather than an unbounded autofocus hunt. The camera rechecks gyro motion after focus settles.

Handheld exposure time is capped at 1/16 second, down from 1/4 second, and metering can use ISO up to 800 to shorten exposures. ISO is constant within each bracket and actual sensor shutter/gain remain recorded for radiance reconstruction. Supported FAST noise-reduction and edge modes are requested explicitly. The exposure-window motion rejection threshold is now 0.3°, with a review finding above 0.12°. Bracket-wide rotation still has a separate, wider alignment budget. Rejected motion prompts another automatic attempt.

These choices follow the reported [Camera2 capture state](https://developer.android.com/reference/android/hardware/camera2/CaptureResult) and [manual capture controls](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest). They do not reproduce the Pixel Camera app's proprietary multi-frame image processing. Shorter exposures trade some shadow signal for less blur. Glare caused by a smudged lens, flare or saturated sensor data cannot be reliably removed by stitching; setup now includes a lens-cleaning reminder.

## Validation and remaining work

New tests cover uniform-light diffuse normalization, high-intensity point-source energy and hemisphere orientation, slow pans, stopping before capture and clearing partial shutter credit. Installed-APK tests read real RGBE fixtures and check GPU-rendered chrome/grey pixel values, exposure, linear display and EGL recreation; a UI test exercises both viewing modes and saves screenshots.

The Preview 9 stitching algorithm is retained. This update targets acquisition sharpness and truthful lighting inspection; it does not claim to repair the remaining curtain parallax in old photos. Real Pixel 8 focus timing, noise and highlight handling need a fresh physical capture to assess. Automated sensor traces and emulator pixels cannot establish that result.
