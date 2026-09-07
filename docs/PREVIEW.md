# sphere 0.1.0 · guided capture route and chrome thumbnails

Download **sphere-0.1.0-preview.apk** and open it on your Pixel 8. It updates the existing app and preserves saved captures.

## Changes

- Follow one deliberate route: complete the horizon, then upper rings and the top, then lower rings and the ground if enabled. Rings proceed clockwise, with short transitions between them. The arrow stays assigned to the next unfinished stop instead of jumping between nearby dots.
- Capture guidance shows the current ring, saved views and stage progress. Future dots are hidden until their turn. Removed the directional vignette and glowing reticle; the arrow, leveling aid and automatic shutter ring remain.
- Finished captures get a transparent chrome-ball thumbnail rendered from their HDR environment. Existing captures populate automatically with visible progress. Thumbnails are 192px, generated off the UI thread using streaming HDR/EXR readers and a reusable cache capped at 8 MiB.
- Added a researched [platform roadmap](https://github.com/otdavies/android-hdri/blob/main/docs/PLATFORM-ROADMAP.md) toward a useful personal/team lighting library and, later, a curated public HDRI catalog.

## Verification

The workflow runs unit tests and lint, then 33 installed-app tests against the exact signed APK before publication. New checks cover canonical route order and resumption, unchanged coverage, mirror geometry and alpha, streaming HDR/EXR agreement, cache refresh and the production scan row. Existing camera orientation, stitching, lighting, storage and independent EXR-decoding checks remain required.

The route's effect on real capture success and comfort still needs a Pixel 8 scan. The platform roadmap is an investigation, not a claim of Poly Haven quality parity.
