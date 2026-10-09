# Metal performance hotfix — 2026-09-23

This pass targets the GT 650M / MacFamily1 compatibility path after logs showed severe frame-time
regressions despite SGI OpenGL recovering.

## Main fixes

- Migrate old persisted Metal bring-up defaults:
  - `renderer.metal.mac1FramesInFlight=1` -> `2`
  - `renderer.metal.mac1WaitForSwapchain=true` -> `false`
  - `renderer.metal.mac1SerializeBufferUploads=true` -> `false`
  - `renderer.metal.benchmark=true` -> `false`
- `MetalRenderPass.setPipeline()` now matches the OpenGL backend's important behavior: re-selecting
  the same pipeline is a no-op instead of a native pipeline bind plus new uniform arrays.
- Avoid redundant sampler/storage-buffer binds inside an unchanged pipeline.
- Avoid marking identical uniform bindings dirty repeatedly.
- Reuse push-constant direct memory instead of allocating a new direct buffer per call.
- Skip GPU debug-group native calls and label construction in production.
- Expensive per-draw binding validation is now opt-in with
  `renderer.metal.validateBindings=true`.

The old config migration is versioned (`config.revision=3`) so it runs once. Exact historic default
values are migrated; unusual custom values are preserved.
