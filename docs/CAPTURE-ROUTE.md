# Canonical route and HDR scan thumbnails

## Capture route

`CaptureRoute` deterministically groups the planner's existing targets into latitude rings: horizon, upper rings in increasing elevation, top, lower rings in increasing downward tilt, and ground. Each ring proceeds clockwise. Its first target is nearest the preceding ring's exit heading; pole views do not reset that heading. The initial horizon stop is nearest the zeroed capture heading.

The engine selects the first unfinished route target, independent of where the phone points. Completed IDs are skipped, including captures made under an older nearest-dot policy. Target geometry, number of exposures, coverage margins, saved poses and the existing motion/focus gates are unchanged. A route is cached until the target list changes. Existing source files and unfinished-session anchors retain their identities.

The HUD shows one active dot and completed markers, an arrow, tilt/level aids, and a header with the current ring and stage progress. The full-screen directional tint and animated reticle halo are removed. Local text scrims remain for legibility. The automatic shutter still waits for alignment, focus and the existing forgiving dwell; moving near a later target no longer changes the assignment.

A level loop first provides a useful connected starting set in many scenes. Keeping neighboring views temporally adjacent and avoiding interleaved tilt changes is the design rationale. A measured quality improvement is not established yet; compare repeated real captures under both policies. The order does not eliminate handheld parallax or solve moving subjects.

## Chrome thumbnails

Finished/review captures render a 192×192 ideal mirror sphere from the current EXR or HDR master, with four subpixel samples and an alpha silhouette. Reflection geometry, linear-light sampling and the highlight shoulder follow the lighting viewer. Metering uses the same diffuse-reference exposure rule with lower angular integration resolution; it is a visual identifier, not a radiometric comparison instrument.

The reader retains at most a 512×256 RGB map. EXR decoding uses the existing bounded scanline/block reader. The new RGBE reader supports the app's standard planar-RLE `-Y +X` masters, both literal and repeat runs; it rejects malformed/truncated input. Display mapping never changes the master.

Work runs on the IO dispatcher with cancellation checks, serialized to bound memory, under the session file lease. Rows display generation progress; a failed thumbnail has an accessible retry button. Captures without a finished master retain the existing placeholder/status art.

PNG derivatives are stored in Android's regenerable cache, capped at 8 MiB across captures. A signature includes renderer version, master format, byte length and modification time. Rebuild/conversion replaces the signature; obsolete versions for that capture are removed. The cache is deliberately separate from the retained capture master and source bundle. Android may clear it, in which case thumbnails regenerate. A future sync protocol should use a content hash instead of filesystem metadata; see the platform roadmap.

## Verification

Local build: 48 JVM tests pass, zero failures/errors/skips; lint has zero errors and eight existing advisory warnings. Both release and instrumentation APKs compile. Seven new JVM tests cover stable ring order, gap/resume handling, unchanged ultrawide/filled-ground targets, mirror axes, alpha area and middle-grey transfer, streamed EXR/RGBE agreement, repeat runs and truncation.

The release gate requires 33 installed-APK tests. The new thumbnail test reads both masters, checks cache reuse and same-path replacement, verifies transparent PNG pixels and renders the actual production capture row. The capture HUD test checks ring/stage progress with the automatic shutter. CI includes screenshots and timings; a publication receipt follows after verification.

## Release-candidate review

The first installed-APK run passed the new route HUD and thumbnail tests. The screenshot review found the expanded header crowded the direction arrow on a small display, so the stage text is now folded into the ring counter and the redundant gyro label is removed. The arrow retains clear space around the reticle.

That run also exposed an existing lighting-test synchronization defect: one shoulder-mapped chrome channel was close enough to a later linear exposure to accept the previous frame. The test now waits for all channels on both probes and asserts the same copied buffer. Expected reflectance, exposure values and the three-code-value tolerance are unchanged. Publication still requires the entire installed-app suite to pass.

## Published Preview 23

[Release and APK](https://github.com/otdavies/android-hdri/releases/tag/v0.1.0-preview.23), from source `531f6fffb4e1a5296c06413e3152f2cd25b9fc82`. [GitHub run 34086749405](https://github.com/otdavies/android-hdri/actions/runs/34086749405) passed build, device verification and publication. All 48 JVM tests passed; lint reported zero errors and eight advisory warnings. All 33 installed-APK tests passed in 72.606 seconds. Independent OpenEXR 3.3.3 decoding confirmed exact float channels, ZIP/raw blocks, partial-block orientation and the 100000 / 1e-8 highlight/dim fixtures.

The final Android thumbnail fixture took 134 ms for EXR generation, 1 ms for a cache hit, 109 ms for HDR generation and 94 ms after replacement. It checked alpha, cross-format agreement, invalidation and the actual capture row. These are emulator measurements. The capture HUD and row screenshots were visually reviewed, including the cleared arrow area and shutter controls.

Two existing private capture masters also rendered successfully on the host with a 48 MiB Java heap: the 2K daytime environment produced a 58,788-byte PNG in 301 ms, and the 4K nighttime environment a 66,043-byte PNG in 564 ms. Both retained only a 512×256 light map and were visually reviewed. These are development-machine timings, not Pixel 8 timings; private images remain outside the repository.

Published APK: 124,036,857 bytes; SHA-256 `1221eca613be0a3c704d077b6f3f4f6df6d2b798e9bfbcdbb5e3e12e37aca6ec`. Checked device-verification archive SHA-256: `e6bbd67f05ea877ff73509cd1e36f42ace16d23260948885c1e85fdf37fa7cb7`.

Real scan comfort and comparative stitching success under the canonical route remain a physical-phone validation item. No change in capture quality or light calibration is claimed from these UI/thumbnail checks.
