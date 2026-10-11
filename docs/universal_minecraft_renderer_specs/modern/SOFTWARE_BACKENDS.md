# CPU reference and Mesa software graphics backends

## Distinguish roles
Mesa llvmpipe supplies an **OpenGL implementation** using the CPU; Mesa lavapipe supplies a **Vulkan implementation** using the CPU. They are drivers beneath actual OpenGL and Vulkan backends, not new shader or API languages. Build a separate small `SoftwareReferenceRenderer` to verify deterministic math and pipeline semantics.

## Reference renderer scope
CPU scalar/color/matrix evaluation, fixed-function lighting/texture/fog/blend, triangle raster, depth/alpha, texture filtering, clamping, ARB opcode interpreter, ISL framebuffer/active-pixel execution where relevant; snapshot frames and expected numerical tests. Profiles define rounding/tolerance and undefined behavior. Not required to run full Minecraft at real-time speed.

## Test drivers
CI matrix may run headless EGL/OSMesa llvmpipe, Vulkan lavapipe under appropriate OS builds, plus GPU native API. Detect support at runtime, record driver/library versions, shader compiler chain and CPU architecture. Preserve portability for Solaris SPARC but never assume a Solaris Mesa build has a working Vulkan ICD without testing.

## Acceptance
Deterministic pixels in CPU reference; GL Vulkan sample scenes on llvmpipe/lavapipe when installed; conformance results match permitted tolerance, and a useful skip reason when driver absent.
