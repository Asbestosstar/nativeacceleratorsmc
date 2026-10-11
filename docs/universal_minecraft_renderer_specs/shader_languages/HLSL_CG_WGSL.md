# HLSL, NVIDIA Cg and WGSL shader-language frontend specification

## HLSL
Microsoft HLSL/DXC specifications and documentation; compiler https://github.com/microsoft/DirectXShaderCompiler . Support stage semantics, structs, register/space binding, buffers, textures, sampling, derivatives, wave ops, Shader Model profiles, matrices/packing, resource barriers/stage limits. Prefer DXC and its SPIR-V backend, with explicit feature validation. HLSL→Vulkan via DXC is supported by DXC but not every D3D feature maps directly. DXIL to D3D12 requires validator and root signature compatibility.

## Cg
Separate historical NVIDIA Cg from HLSL although syntax overlaps; language version/profile-specific semantics, target registers, matrix and precision rules, historical builtins and CgFX only if specified. Inspect archived toolkit source/licensing/runtime before bundling. Build a source-compatible frontend if available compilers do not run on project platforms. Test across historical profiles and compare with original output where possible.

## WGSL
Authoritative W3C WebGPU WGSL spec https://www.w3.org/TR/WGSL/ and WebGPU API spec https://www.w3.org/TR/webgpu/ . Respect address spaces, uniformity analysis, alignment/layout, derivative legality, binding/group model, builtins and device feature limits. Use Tint/Naga or verified toolchain for WGSL→SPIR-V/MSL/HLSL/GLSL where supported, with target validation. Do not claim raw WGSL universally accepted by GL or Vulkan drivers.

## Common acceptance
Version-specific valid/invalid fixtures, reflection compatibility, semantic IO maps, resource descriptor validation, codegen to at least two chosen backends, numeric and image regression fixtures, unsupported-feature diagnostic taxonomy.
