# Vulkan renderer backend specification

## Normative source
https://registry.khronos.org/vulkan/ and https://registry.khronos.org/vulkan/specs/latest/html/vkspec.html ; selected API version and extensions reflected at runtime. SPIR-V environment must match Vulkan spec and declared capabilities; verify with SPIRV-Tools.

## Device and presentation
Instance, extensions/layers, surface platform integration, physical device queue family selection, limits/features, logical device, graphics/compute/transfer queues, swapchain/surface loss and rebuild, image views, formats, MSAA, depth-stencil, command pools/buffers, synchronization (including timeline semaphores where supported), descriptor sets/pools, pipelines/caches, sampler/images/buffers, memory allocation, staging uploads, fences, debug utils.

## Render graph compiler
Track image layout, usage flags, subresource ranges and last access; generate valid barriers and cross-queue ownership transfer as necessary; merge passes where legal; dynamic rendering vs render pass/subpass chosen by actual capabilities; prevent framebuffer read/write hazards; per-frame allocators/resource retirement. Manage queue submission and image acquire/present in correct order.

## Shaders
GLSL/HLSL/WGSL/historical frontends lower to validated target-environment SPIR-V if their semantics can be expressed. Reflect descriptor sets, bindings, push constants, vertex inputs/fragment outputs and specializations. Never assume OpenGL GLSL can be compiled as-is without Vulkan binding/stage/version rules. Validate shader stages and attachment formats against pipeline layout.

## Minecraft migration
Alternate backend must replace the normal GL command pathways including GUI and presentation; a Vulkan triangle shown in GL Minecraft window is not full Vulkan support. Handle GLFW Vulkan surface creation without incompatible active GL context where required. Port atlases, chunk uploads, draw passes and resource reload deterministically.

## Acceptance
Validation layers report no project-induced errors for scripted scenes; stable on native Vulkan and lavapipe when available; repeated resize/fullscreen, shader reload, device loss handling, texture uploads, transparency and depth, 1% low frame time, no GPU use-after-free. Compare canonical scene with OpenGL under documented precision tolerance.
