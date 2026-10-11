# Capability negotiation and shader portability matrix

## Outcomes
- `NATIVE`: code and profile directly accepted and semantically valid on target.
- `EXACT_TRANSLATION`: frontend lowering preserves defined behavior under documented numeric tolerance.
- `SEMANTIC_EMULATION`: additional passes/textures/reference shader services preserve defined behavior.
- `APPROXIMATION_OPT_IN`: fidelity loss disclosed and explicitly enabled.
- `UNSUPPORTED`: cannot faithfully execute under current capabilities.

## Initial feasibility targets (not completion claims)
| Source | OpenGL | Vulkan | Metal | D3D12 | Software |
|---|---|---|---|---|---|
| GLSL | Direct/versioned | SPIR-V for Vulkan | SPIR-V→MSL feasible | Needs translation route | reference subset |
| HLSL | Translation | DXC→SPIR-V | translated MSL | native DXC→DXIL | reference subset |
| WGSL | translated GLSL | WGSL→SPIR-V | WGSL→MSL | WGSL→HLSL→DXIL | interpreter subset |
| NVIDIA Cg | profile-dependent | custom/profile lowering | custom/profile lowering | legacy compatibility | interpreter |
| SGI ISL | appearance backend | multipass/services | multipass/services | multipass/services | reference evaluator |
| ARB/NV assembly | compatibility GL/profile | assembly IR→SPIR-V | custom lowering | custom lowering | instruction interpreter |
| RenderMan RSL | renderer services required | renderer services required | renderer services required | renderer services required | renderer-specific |
| OSL | renderer services required | restricted supported subsets | restricted subsets | restricted subsets | OSL CPU viable |

This is a feasibility matrix only; each path defaults UNSUPPORTED until proven by tests.

## Capability descriptors
shader stages, target language versions, subgroup/wave capabilities, derivatives, bindless, storage images, atomics, ray tracing, framebuffer fetch/input attachments, attachment formats, MSAA, depth textures, texture formats, shadow compares, anisotropy, geometry/tessellation/mesh, integer/fp16/fp64 precision, framebuffer blending, coordinate transforms, resource binding limits.

## Diagnostics
On failure output requested frontend profile, required semantics/resources, backend capabilities, missing features, emulation alternatives and exact file location. Never silently use a visually similar shader.
