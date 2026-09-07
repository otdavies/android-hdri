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
