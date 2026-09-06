# sphere 0.1.0 · device-test preview

Download **sphere-0.1.0-preview.apk** below and open it on your Pixel 8. This updates Luma Sphere in place and preserves saved captures.

## New in this preview

- **sphere** branding, a warm neutral interface and adaptive/themed launcher icon.
- **Light check**: chrome and 18% grey spheres rendered from your actual HDR environment, with shared rotation, ±8 EV exposure and linear / highlight-roll-off display modes.
- **Explore HDR**: inspect the environment at different exposures. Viewing controls leave HDR exports unchanged.
- A slower, deliberate automatic shutter that accepts small tremor but rejects slow pans and waits for focus. Shorter native Camera2 exposures and tighter exposure-time motion checks target blurry source photos.

Open an existing completed capture and tap **Check the light** to try the spheres without recapturing. **Explore a sample capture** exercises the entire local pipeline. The improved Preview 9 stitching remains available through **Rebuild from saved photos**.

See [lighting and capture details](https://github.com/otdavies/android-hdri/blob/main/docs/SPHERE-UPDATE.md). HDR values are relative radiance reconstructed from JPEG brackets; lighting views are SDR inspection references, not calibrated photometry or ACES previews. Close-object parallax and clipped practical lights remain limitations. Fresh Pixel 8 capture is needed to judge the camera changes.

Everything runs on the phone, with visible progress and retained original exposures. Capture requires Google Play Services for AR; guidance uses fused gyro/gravity. No backend or app Internet permission. This is a signed development preview; CI tests the same APK that is published.
