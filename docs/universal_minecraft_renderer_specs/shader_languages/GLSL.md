# OpenGL Shading Language (GLSL) — comprehensive frontend/target specification

## Normative references
GLSL 4.60 https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html ; earlier version documents from Khronos OpenGL registry https://registry.khronos.org/OpenGL/ ; GLSL for Vulkan (`GL_KHR_vulkan_glsl` and Vulkan GLSL extensions) from Khronos GLSL repository. Note **older GLSL versions are not always strict subsets of 4.60**; parse according to declared `#version` and profile.

## Language coverage
Build/integrate glslang or equivalent complete frontend rather than invent simplified grammar. Support appropriate versions (#version, core/compatibility/ES if selected), #extension behavior, preprocessing/macros/includes (include only where supported or explicitly host-managed), built-in variables/functions, scalar/vector/matrix/array/struct types, qualifiers, overloading, swizzles, stage interfaces, interface blocks, UBO/SSBO, samplers/images, precise/invariant, interpolation, derivatives, atomic/subgroup/compute/geometry/tessellation when capabilities permit. Validate profile-specific reserved words, precision rules, limits, binding conventions.

## Stages and outputs
Vertex/fragment required; optional geometry/tessellation/compute per backend capabilities; advanced mesh/ray stages only through explicit extensions/profiles. Validate location/index, attachment format, dual-source blending, workgroup sizes, image access, depth writing, interpolation and sample qualifiers. Track built-in gl_Position/gl_FragCoord and coordinate convention conversion.

## Cross-backend
- OpenGL native GLSL for compatible version/extensions.
- Vulkan: target SPIR-V environment, explicit descriptors and locations, specialization constants, matrix/resource layout; compile via glslang then SPIRV-Tools validate.
- Metal: supported SPIR-V→MSL transpilation plus binding assignment, feature gates and compile on target macOS.
- D3D12: translate to HLSL/DXIL through a verified toolchain and validate output; explicitly mark unsupported extensions.

## Reflection and diagnostics
Expose uniforms, blocks, samplers, images, interface layouts, specializations, fragment outputs, required extensions. Source-map diagnostics from compiler and target driver. Hash full shader source/version/options and included dependencies.

## Tests
Khronos glslang fixture suites as licensing permits, positive/negative compilation by version, stages, resource layout, UBO packing, precision, derivatives, texture samples, dynamic branches, sRGB/depth/alpha and render-image comparisons; tests for GLSL 1.10/1.20/3.30/4.60 representative fixtures, not just newest syntax. Acceptance: same GLSL material on OpenGL and Vulkan with correct rendering and semantics, plus documented translated Metal/D3D paths.
