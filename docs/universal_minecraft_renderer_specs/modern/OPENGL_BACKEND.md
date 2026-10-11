# OpenGL Core, Compatibility and SGI historical profiles

## Standards
Khronos https://registry.khronos.org/OpenGL/ ; GL 4.6 core/compat where supported; also historical GL 1.x/2.x specs per SGI/HP profiles and GLX/WGL/EGL/CGL context requirements. Version, profile and extension set determined at runtime; never hardcode 4.6.

## Backend responsibilities
Create contexts/capability reports; textures/renderbuffers/FBOs, buffers/VAOs, samplers, programs, UBO/SSBO where supported, draw/compute, fences, PBO uploads/readbacks, swap/present, debug callbacks, timer queries, state cache, barrier/invalidation handling. Distinguish compatibility functions (fixed-function, glBegin, matrix stacks) from modern core context. Context ownership and render-thread discipline are mandatory.

## Shader pipeline
GLSL compile/link/reflection, stage interface verification, bind buffers/samplers, transform resources from canonical reflection. Versioned GLSL output; older GL profile may require multipass/fixed-function adapter or CPU fallback. Driver compiler diagnostics must map back to originating shader file/line.

## State and life cycle
Depth/stencil/blend/raster state isolation, texture completeness, per-attachment formats, multisampling, resize/reload/device reset, fences before deletion; safe fallback for invalid shaders. Modern OpenGL backend + historical SGIX state emulation can be composed, not conflated.

## Validation
KHR_debug where available, GL error/state tests, offscreen triangles/textures/transparency/depth/readback, intentionally invalid GLSL diagnostics, GPU resource leak checks, OpenGL software llvmpipe testing. Test core and compatibility separately; native SGIX only if advertised and verified.
