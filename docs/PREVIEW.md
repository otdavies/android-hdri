# sphere 0.1.0 · lighting and field workflow preview

Download **sphere-0.1.0-preview.apk** and open it on your Pixel 8. It updates the existing app and preserves saved captures.

## New in this preview

- A physically correct 18% grey reference with clearer exposure: meter received diffuse light, use the capture exposure, or inspect HDR at 1×. Reference sRGB is the default; Reinhard and an environment background are optional.
- Pinch and button zoom from 0.5× to 4× in **Explore HDR**.
- **Fill below me** skips the straight-down photo and fills the bottom 35° with approximate surrounding ground colour and texture. Synthetic ground is identified in the app and exports.
- Discovered rear lenses, including eligible ultrawide lenses, use native Camera2 with their own calibration and fixed physical-camera selection. Compact and higher-overlap pace choices recalculate the required stops.
- **One HDR master**: choose compressed EXR or Radiance HDR before capture. The other export format is converted only when requested. Existing captures can **Store as EXR** or **Store as HDR** in Storage.
- Clearer storage accounting, cleanup of abandoned photos and duplicate outputs, and a button to clear temporary exports. Removing original source photos remains optional and requires confirmation.

On the supplied captures, explicit cleanup leaves about **5.1 MB for the 2K ground-filled capture** and **17.4 MB for the 4K capture**, including preview and metadata. Size varies with image content. Installed app and native-library size is separate from each capture.

## Verification and limits

CI tests the exact signed APK before publication, with 32 JVM checks and a gate of 30 installed-app tests, plus independent OpenEXR decoding. Real-input host replays also exercise EXR masters and cleanup under a 192 MiB Java heap limit.

Ultrawide capture still needs a Pixel 8 hardware check: Android does not guarantee every physical-camera JPEG stream combination. Unsupported lenses fail visibly without silently switching cameras. Ground fill is an approximation. Lighting values remain relative, not calibrated lux; EXR conversion cannot restore clipped source detail or lost precision.

See [implementation, measurements and primary sources](https://github.com/otdavies/android-hdri/blob/main/docs/FIELD-WORKFLOW.md).
