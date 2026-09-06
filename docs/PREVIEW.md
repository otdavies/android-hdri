# sphere 0.1.0 · device-test preview

Download **sphere-0.1.0-preview.apk** below and open it on your Pixel 8. It updates the existing app in place and preserves saved captures.

## New in this preview

- Clear interface text, the original green/ink palette and globe logo, with the circular adaptive/themed launcher icon.
- A prominent **Lighting spheres** button directly below completed previews: chrome and 18% grey under your captured environment.
- **Storage** shows each capture's total, source photos, processing files and finished files. Successful builds automatically clear temporary processing files. Older captures can **Clear processing files** from Storage.
- **Remove source photos** reclaims space after confirmation while keeping finished HDR/JPEG, lighting previews and final exports. It disables rebuilding; export the original capture bundle first if you need an archive.
- **Save OpenEXR (.exr)** alongside Radiance HDR and JPEG. EXR uses lossless ZIP compression and full 32-bit linear RGB, with visible progress and no extra permanent copy in the capture.

For the supplied test capture, retained storage falls from about 537 MB to 124 MB after clearing processing files, or about 34 MB without originals. The EXR export is 17 MB and decodes to exactly the saved HDR values. Sizes depend on the scene and capture settings; stitching still needs temporary working space.

See [storage, export and verification details](https://github.com/otdavies/android-hdri/blob/main/docs/STORAGE-EXPORT.md). Everything runs locally. This preview retains the previous camera, stitching and HDR lighting algorithms. Values remain relative radiance reconstructed from JPEG brackets; EXR does not recover clipped highlights or remove parallax already present in source photos.

CI tests the exact signed APK before publication, including storage deletion/recovery, lighting controls and independent OpenEXR decoding.
