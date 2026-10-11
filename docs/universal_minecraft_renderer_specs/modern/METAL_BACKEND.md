# Apple Metal graphics backend specification

## Sources
Apple Metal documentation https://developer.apple.com/metal/ ; Metal Shading Language and feature set tables https://developer.apple.com/metal/resources/ . Versioned Apple GPU family and OS capabilities are authoritative.

## Host/interop
Java platform layer through carefully scoped JNI/Panama or validated existing binding; Objective-C/Metal integration on macOS with CAMetalLayer/MTKView or compatible native window surface. Clearly document target JDK, architecture, deployment version, thread and autorelease-pool behavior. No assumption Metal is available on Solaris/Linux/Windows.

## GPU device
MTLDevice, command queues/buffers, drawable management, render/compute encoders, state/pipeline objects, depth-stencil, sampler/texture/buffer heaps when supported, sync/fences/events, staging/readbacks, loss/recovery and frame pacing. Map render graph hazards/storage modes and resource lifetimes to Metal behavior; do not naively import Vulkan barrier rules.

## Shaders
Generate version-valid MSL or supported metallib using verified toolchain; use SPIRV-Cross or another maintained converter only within documented supported features; map GLSL/HLSL resources, argument buffers, function constants, attribute indices, texture sampling, coordinate/depth conventions, bindless and subgroup capabilities. Strict validation of Metal language version/device family.

## Acceptance
Standalone triangle/texture/compute/fog/shadow, shader compile error source maps, transparent geometry, offscreen pixel compare to GL/Vulkan, resize/windowing correctness, resource cleanup and independent Minecraft GUI + terrain backend. Mark unsupported shader features rather than approximate without permission.
