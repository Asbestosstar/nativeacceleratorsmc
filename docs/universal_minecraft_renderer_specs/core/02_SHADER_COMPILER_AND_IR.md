# Compiler / semantic IR / cross-compilation specification

## Frontend contract
Inputs: source bytes, language, version/profile, include resolver, permitted resources, compilation options, provenance. Outputs: typed semantic module or structured diagnostics with exact file/line/column, reflective entrypoints and pipeline requirements. Parser, names, overload resolution, types, values and constants must follow each normative language version. Recursive bounded includes and deterministic dependency hashing.

## Two-level intermediate design
1. `SemanticIR`: surface/light shaders, appearance composition, render service calls, historical framebuffer state, vertex/fragment/compute entrypoints, Cg profiles, ARB register semantics, varying active-pixel execution, texture and coordinate semantics, tagged numeric modes.
2. `GPUExecutionIR`: stages, typed SSA/control flow, dataflow, resource accesses, stage interfaces, specializations, barriers, descriptor requirements, attachment reads/writes. Use SPIR-V in the supported Vulkan route; do not pretend it models RSL/OSL appearance networks or SGIX state by itself.

## Compilation pipeline
`frontend parse -> type check -> source semantic IR -> legality pass -> capability negotiation -> renderer-service lowering and optional passes -> GPU-stage IR -> backend-specific legalization -> backend binaries/source -> pipeline reflection and validation`.

## Binding and coordinate translation
Explicit maps for position/normal/UV, shader stages, matrix layout, vertex clip conventions, depth range, sample rates, lightmap, texture/sampler separation, descriptor spaces/sets, push constants, GL uniforms/UBOs, Metal argument indices, D3D registers and spaces. Reject collisions. Stable reflected manifests, not guessed slot indices.

## Backend outputs
- OpenGL: GLSL source for supported profile, GL programs, compatible fixed-function emulation.
- Vulkan: valid SPIR-V modules + descriptor layouts/pipeline state/render graph.
- Metal: versioned MSL or validated metallib via supported toolchains, accurate bindings.
- D3D12: DXIL through a verified compiler + PSO/root signature.
- Software: interpreter/reference evaluator for deterministic subsets.

## Tool candidates (not mandatory dependencies)
Khronos glslang, SPIRV-Tools, SPIRV-Cross, shaderc; Microsoft DXC; Tint for WGSL; LLVM/Mesa NIR only if worth build/portability cost; OSL libraries for actual OSL semantics. Verify current capabilities and licenses before vendoring or depending on them.

## Historical constraints and testing
ARB assembly has register and instruction profiles, Cg has profile semantics, ISL has uniform/parameter/varying and FB semantics, RSL has renderer closures/services. Each gets its own legality and runtime-service layer. Keep optimizer opt-out, deterministic reference tests and per-language feature/coverage tables.

## Cache identity
Input hashes + included files, frontend version, language profile, semantic flags, backend/API and driver capability key, target shader model/MSL/GLSL/SPIR-V environment, specialization constants, pipeline attachment signatures. Never persist raw driver handles. Diagnose cache misses and invalidation.

## Security
Shader programs and resource packs are untrusted: bounded source/includes/AST/instruction/control flow, compile time, number of passes and resource requests. Support cancellation and fail closed. GPU validation is still mandatory; neither static analysis nor shader execution is an OS sandbox guarantee.
