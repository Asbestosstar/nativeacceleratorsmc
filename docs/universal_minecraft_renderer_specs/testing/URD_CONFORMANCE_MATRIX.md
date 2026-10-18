# URD conformance matrix

Status values follow `CONFORMANCE_AND_TESTING.md`. A higher status is not implied by a lower one unless the evidence is listed.

| Feature | Status | Evidence / blocker |
|---|---|---|
| Existing RenderPearl/Minecraft audit | IMPLEMENTED | `docs/renderer-existing-system.md` |
| Backend-neutral resource/lifetime contracts | UNIT_TESTED | `renderer/universal/urd`; `UrdResourceLifetimeTest`, `UrdRenderGraphTest` |
| Render-graph hazard validation | UNIT_TESTED | `UrdRenderGraph`; uninitialized reads and read/write hazards covered by tests |
| Process-wide URD backend registry and borrowed-device ownership | UNIT_TESTED | `UrdBackendRegistry`; duplicate/stale-device rejection and wrapper-only cleanup are implemented |
| Capability outcomes and missing reasons | UNIT_TESTED | `CapabilityPlannerTest`; native/unsupported outcomes covered |
| GLSL source limits, provenance and SHA-256 identity | UNIT_TESTED | `ShaderSourceSpecTest`; material source hashes covered by `TriangleMaterialTest` |
| GLSL compiler integration | IMPLEMENTED | `GlslFrontend` delegates to RenderPearl `GlslCompiler`; plain-main runner reports `BLOCKED/DIAGNOSTIC` when LWJGL native shaderc is unavailable |
| GLSL reflection normalization | IMPLEMENTED | `ShaderReflection`; compiler fixture evidence pending |
| OpenGL URD adapter/offscreen scene | INCOMPLETE | Opt-in triangle execution path exists; `run-opengl-smoke.sh` reports `BLOCKED` until a real RenderPearl/Minecraft command is supplied |
| Vulkan URD adapter/validation scene | INCOMPLETE | Opt-in triangle execution path exists; `run-vulkan-smoke.sh` reports `BLOCKED` until a real RenderPearl/Minecraft command is supplied |
| Same GLSL material on OpenGL and Vulkan | INCOMPLETE | Requires both executable GPU paths and pixel comparison |
| Minecraft opaque geometry bridge | INCOMPLETE | No broad integration until standalone scene passes |
| Metal URD GLSL 460 triangle contract | UNIT_TESTED / GPU-BLOCKED | URD validates stages/interfaces/resources and pixel readback; actual SDL/Metal evidence remains `BLOCKED` until `run-metal-smoke.sh` receives a real-device command |
| Metal URD general shaderpack execution | INCOMPLETE | Arbitrary GLSL packs, includes, resource binding, render states, and Minecraft pass integration remain unavailable |
| Historical shader/API frontends | INCOMPLETE | Clean-room ISL lexer/profile foundation and SGIX capability registry are present; full grammar/appearance/API semantics remain incomplete (`docs/ISL3_CONFORMANCE.md`) |
| Native Vulkan companion CPU/ABI scene path | UNIT_TESTED | `run-native-vulkan-smoke.sh`; CTest kernel/renderer tests pass; real GPU probe remains blocked unless supplied |
| Hardware-specific SGIX/HP profiles | INCOMPLETE | No implementation claimed |
