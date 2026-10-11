# Minecraft renderer integration specification

## Discovery and compatibility
Locate authoritative mod loader rendering hooks, version-specific core renderer, RenderSystem and GL state boundaries, native window/context setup, UI/font path, chunk meshing, lightmaps, atlas animations, render layers, translucency, entity model rendering, particles, sky, cloud, postprocessing, upload staging. Inventory direct GL use by Minecraft and dependencies. Identify game thread, render thread, chunk worker pool, reload threads.

## Migrating to backend neutrality
- Introduce `MinecraftFrameInput`, `SceneGeometryProvider`, `TextureAssetResolver`, `FrameGraphBuilder`, `PresentSurface` boundaries.
- Phase 1 independent backend-neutral test scene, without touching gameplay.
- Phase 2 route one opaque chunk mesh type through URD while keeping vanilla fallback.
- Phase 3 route all chunk layers with original sort/cull/lightmap behavior.
- Phase 4 animated models, skins, items, block entities, particles, fluids, sky/weather.
- Phase 5 GUI/text, overlays, postprocessing, screenshots/readbacks and presentation.
- True non-OpenGL backend support requires owning/remapping all relevant GL call sites; do not present mixed GL and Vulkan as fully native Vulkan.

## Geometry and materials
Retain block texture atlas UV semantics, block tint, ambient occlusion, packed light, normals where actually available, animation and biome tint. Define render-state categories opaque, cutout, blended, additive, depth-only, shadow. Batch by compatible material/pipeline while preserving visibility and translucent ordering. No per-block draw calls.

## Resource synchronization and lifecycle
Version-aware resource reload stages: background parse + CPU compile; correct backend thread/device operations for GPU uploads; atomic publication of new valid pipelines; defer retirements until GPU fences/timelines show safe. Support window recreate, fullscreen toggle, resize, MSAA, dimension changes, reload, device loss, shutdown.

## Shader source assets
Suggested host-only metadata `assets/<namespace>/universal_renderer/materials/*.json` referencing source language, source path, entrypoints/appearance, parameters and required capability profile. Never modify historical sources to inject Minecraft-specific syntax. Use namespace containment and source-size/complexity limits.

## Mod compatibility
Classify mods as API-observant, GL-direct, pipeline replacement, custom shader injector, framebuffer hook, or unknown. For Vulkan/Metal, unsupported direct GL mods must be isolated/disabled or supported through an explicit compatibility implementation; avoid falsely claiming support. Provide detailed startup diagnostics and safe vanilla renderer fallback when possible.

## Acceptance scenes
Flat world, cave, dense forest, translucent village, flowing water, many animated entities, UI inventory, particles and cloud/fog; debug overlays; screenshot pixel checks and GPU timings. Compare baselines by Minecraft revision, driver/API and shader profile. Clearly identify mismatches allowed by specifications.
