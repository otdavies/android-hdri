# sphere: catalog, imports and practical capture tools

Current product direction · 7 September 2026. Planning only; no website, connector or tracking export is implemented by this document.

## Product decision

Build accessible tools with specific inputs and inspectable results. The immediate goal is to browse a useful public HDRI catalog and bring an environment into Blender, Unity or Unreal with very little setup. Small learned models can help with a demonstrated task later. A conversational assistant and a general photo-relighting product are outside this launch scope.

The app remains the local capture tool. The website is the public catalog. Small editor integrations handle import and repeatable setup. This supersedes the private production-project subscription recommendation in [SERVICE-PLAN.md](SERVICE-PLAN.md); its storage architecture and cost assumptions remain useful reference material. Monetization should test inexpensive optional workflow tools, starting with the proposed $4.99 Blender connector, once they save users measurable effort. Ordinary public downloads remain available without that purchase.

## First complete user journey

1. Browse the website without an account. Search titles/tags and filter by place, lighting, resolution and ground treatment. Use transparent chrome thumbnails on the grid.
2. Inspect an asset in a panorama and a shared chrome/grey lighting view. Show its size, license and known quality limitations. Shared exposure controls support comparisons; viewing adjustments do not rewrite the radiance master.
3. Download HDR/EXR or choose the destination editor. Show the exact variant, dimensions and bytes. Completed downloads work offline.
4. In the editor, import the environment and apply it as an explicit, undoable action. Preserve the user's scene until they choose to change its lighting.

Start with a small operator-published collection, static pages/catalog data and direct object-storage downloads. Source permission-cleared assets; none of the user's private captures becomes public automatically. Browser search is sufficient for a few hundred metadata entries. Accounts and contributor intake can follow actual usage.

### Website to editor handoff

Use one documented, versioned asset manifest behind every integration. An asset page offers a small data-only import file containing its public identity/revision, variant URL, integrity hash, dimensions, projection, color/radiance convention and orientation. The connector accepts that file or an asset link. Plain HDR/EXR downloads remain a universal fallback.

This avoids a prerequisite desktop service or browser-specific launch scheme in the first release. A later paired connection can make the handoff more direct. The UI must not claim to open an editor unless the installed integration can complete that operation.

Never execute code embedded in a downloaded manifest. Bound sizes, validate supported fields, verify hashes and show download progress with cancellation. Use an explicit download policy for remote URLs and local paths. Cache each variant by its immutable hash, reuse it across scenes where appropriate, and support packing/copying the environment when handing a project to another person.

| Destination | Adapter behavior | First validation |
|---|---|---|
| Blender | Create a World with an equirectangular Environment Texture, preserve linear HDR data, expose rotation and relative exposure | Known light directions and linear values survive import; undo restores the previous World |
| Unity Built-in / URP | Set appropriate HDR texture import settings, create a Panoramic skybox material, make environment-lighting updates explicit | Correct sky orientation plus diffuse/specular illumination in the selected pipeline |
| Unity HDRP | Use the installed pipeline's cubemap import and HDRI Sky/Visual Environment controls | Correct sky and lighting with explicit volume/exposure settings |
| Unreal | Import an engine-supported HDR cubemap and configure a Sky Light; offer HDRI Backdrop when available | Correct orientation and intensity, without an accidental duplicate Sky Light |

These are separate adapters to a common asset contract. The engines have different environment paths: [Blender World lighting](https://docs.blender.org/manual/en/latest/render/lights/world.html), [Unity Panoramic skybox](https://docs.unity3d.com/6000.0/Documentation/Manual/shader-skybox-panoramic.html), [HDRP HDRI Sky](https://docs.unity3d.com/Packages/com.unity.render-pipelines.high-definition%4016.0/manual/create-an-hdri-sky.html), and [Unreal HDRI Backdrop](https://dev.epicgames.com/documentation/unreal-engine/hdri-backdrop-visualization-tool-in-unreal-engine?lang=en-US). Declare and test supported editor/pipeline versions instead of assuming those APIs are stable.

Keep radiance/exposure and the display transform separate. Different renderers and tone mapping will not produce identical screenshots by default. Use an analytic HDR with identifiable directions and known values, then real interiors/outdoors, to detect axis, transfer-function and scaling errors. Do not infer absolute lux or add a guessed sun light from an uncalibrated capture.

## Delivery order

| Milestone | Complete when |
|---|---|
| Public catalog and ordinary downloads | Someone can find, inspect and download a useful licensed environment from a phone or desktop |
| Blender connector | Asset link/import file becomes an editable World with progress, caching, cancellation and undo; test the $4.99 convenience purchase hypothesis |
| Unity and Unreal adapters | The same manifest works through documented, tested pipeline-specific setup; publish plain-file instructions while the integrations are developed |
| Camera-to-editor transfer | A completed local capture can take the same import path; optional direct pairing improves transfer without changing the file contract |

Measure successful imports and time spent on setup with consented testers. Prefer that evidence over download counts alone. Keep the business tied to useful workflow tools; cloud accounts, paid seats and broad hosted inference are not dependencies of these milestones.

## Next independent experiment: transfer a placement into Blender

The first tracked workflow targets a stationary placement: the user taps where a virtual prop should sit, captures a reference frame and exports that setup. Blender opens the matching camera, image background, placement transform, a simple ground/shadow receiver and a selected existing HDRI. Replacing the placeholder with a model should preserve its intended position and scale.

ARCore exposes camera poses and image intrinsics. Export intrinsics for the actual saved image, accounting for its crop and display rotation; do not substitute preview coordinates or pair an arbitrary still image with an unrelated pose. [ARCore Camera API](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera).

Anchors adapt as ARCore revises its world estimate. Store camera/object transforms relative to a shared nearby anchor using the same captured frame, and record tracking state. Local anchors have session scope; an exported transform is a scene snapshot, not automatic relocalization on a later visit. [ARCore anchors](https://developers.google.com/ar/develop/anchors).

The transfer must preserve:

- Image dimensions/crop, intrinsics, timestamp and matching camera pose.
- Placement transform, coordinate convention, units and a user-checkable size reference.
- HDRI revision, orientation relative to this shot, and relative exposure. A previously captured sphere has no automatic alignment to a fresh AR origin. Offer manual matching to a recognizable direction first and save that offset.
- Optional plane/depth data with its confidence and capture transform. A plane can receive approximate shadows; convincing occlusion around existing objects needs more geometry or a mask.

Test a known-size box on a marked table from several views and report reprojection displacement, scale error and drift. Add a short moving-camera clip only after the still-frame workflow works, with synchronized frame poses and visible lost-tracking segments. Moving real-object tracking is a separate capability, not an assumed result of tapping an AR anchor.

Keep this session separate from the successful gyro/gravity photosphere guidance. It must not reintroduce positional guidance errors when capturing the sky. Begin with a verified ARCore camera path, then test other lens paths explicitly.

## Where splats and small models could help

A captured scene representation could later add a navigable location reference for checking camera positions, framing and prop placement. Conventional Gaussian splats represent captured appearance and do not automatically provide clean surfaces, editable materials, collision or reliable new-light interaction. Those require additional reconstruction or rendering work. The [original 3D Gaussian Splatting project](https://repo-sam.inria.fr/fungraph/3d-gaussian-splatting/) is a useful reference for its view-synthesis purpose, not a promise of an editable film set.

Evaluate each addition against a specific manual task: a small depth model to improve a placement surface, a foreground mask to make an inserted prop occlude correctly, or a blur estimator to help capture only useful frames. Keep corrections available and measure benefit on real captures. Prototype splats only when looking around the location solves a problem the simple camera/plane/HDRI export cannot.

The next implementation priority is the public catalog and Blender import. Tracked placement is a bounded follow-on experiment; broader scene reconstruction and learned models follow demonstrated need.
