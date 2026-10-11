# Execution roadmap for Claude Code

## Operating method
For each milestone: inspect relevant existing code; link primary spec sections; implement real code; compile; run unit/integration tests; record artifact/evidence, performance and known gaps; only then claim success. Continue with next independently feasible milestone. Do not implement fictional stubs and claim them functional.

| Phase | Deliverable | Measurable gate |
|---|---|---|
| 0 | Repo + hardware/toolchain audit | Versioned architecture and dependency map |
| 1 | URD resources/commands + software tests | Buffers/textures/pipelines creation and lifetime tests |
| 2 | GL backend + GLSL | Offscreen triangle and textured mesh, lifecycle tested |
| 3 | Render graph/typed shader reflection | Automatic attachment dependency checks |
| 4 | Vulkan backend | Same deterministic scene on actual Vulkan runtime incl lavapipe |
| 5 | GLSL cross-target path | One GLSL material works on GL+Vulkan, expected pixels |
| 6 | Minecraft opaque chunks | Real chunk geometry through URD, batching preserved |
| 7 | Transparency/entities/UI | All critical game render paths ported/tested |
| 8 | ARB assembly frontend | Instruction tests + functioning world material |
| 9 | NVIDIA Cg frontend | Profile-validated program + generated target |
| 10 | ISL 3.0 frontend+appearance | Real historical program, surface/light, FB tests |
| 11 | SGIX compatibility | Individual extension conformance test suites |
| 12 | Metal + D3D12 | Native independent scene and lifecycle gates |
| 13 | HLSL / WGSL / OSL / RSL | Only supported features validated |
| 14 | HP VISUALIZE / IBM adapters | Device-specific documented tests and profiles |
| 15 | perf and release | Benchmarks, conformance matrix, supported-platform inventory |

## Hard distinctions
Scene rendering != Minecraft renderer replacement. Parsing ISL != executing appearance semantics. SPIR-V generation != valid Vulkan pipelines. Shader translation != bit-for-bit behavior. A historical GPU profile != native hardware emulation unless independently specified and validated.

## Final documentation
Build, installation, Java/native prerequisites, launcher flags, config schema, shader author guide, device support matrix, language conformance matrix, per-platform test log, known incompatibilities, troubleshooting, license/third-party acknowledgments.
