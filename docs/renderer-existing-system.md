# Existing renderer audit

## Scope

This audit records the starting point for the universal renderer vertical slice. It is not a claim that Native Accelerator replaces Minecraft's renderer.

## Current ownership

- Minecraft 26.3 and RenderPearl provide the public `GpuDevice`, `GpuSurface`, command encoder, resource, pipeline, OpenGL backend, Vulkan backend, and GLSL compiler APIs.
- Native Accelerator's SDL3/Metal implementation is under `renderer/metal`. It is a RenderPearl backend adapter with Metal-specific state caching, SPIR-V-to-MSL lowering, indirect fallback, and MacFamily1 compatibility behavior.
- `RendererBackendRuntime` observes the backend Minecraft actually creates. It starts the optional native Vulkan companion only after a Vulkan backend succeeds; that companion currently owns loader/device evidence, CPU scene data, culling, arenas, and indirect preparation, not a Minecraft surface, swapchain, graphics pipeline, or presentation path.
- Existing OpenGL and Vulkan Minecraft rendering remain RenderPearl-owned. The new URD must wrap those devices rather than create a second context or silently route through the native companion.

## Toolchain

The Maven project targets Java 25 and currently includes LWJGL SDL, OpenGL, and SPIRV-Cross bindings. RenderPearl's client classes are supplied by the Minecraft client dependency. No project-owned glslang, shaderc, or SPIRV-Tools dependency is currently declared; the initial GLSL frontend therefore delegates to RenderPearl's real compiler and reports compiler/runtime failures explicitly.

## Incremental boundary

The first URD slice provides backend-neutral resources, command/lifetime contracts, render-graph validation, capability outcomes, and a GLSL frontend with reflection metadata. OpenGL and Vulkan adapters are opt-in and must consume the device Minecraft already created. A deterministic paired GPU scene, real offscreen readback, and full Minecraft migration remain separate evidence gates.

Historical languages and profiles, SGIX compatibility, HP VISUALIZE hardware emulation, Metal URD, and broad Minecraft migration are incomplete until their normative behavior has independent tests. No regex shader parser, fake SPIR-V, guessed binding map, or visually similar fallback is acceptable.

## Runtime evidence policy

Unit tests may use a fake URD device. GPU tests require a real OpenGL/Vulkan/Metal runtime and record driver, source hash, output, and validation evidence. The opt-in URD triangle contract is GLSL 460 vertex/fragment only, with no includes or descriptors; it validates the reflected interface and exact RGBA8 readback before emitting evidence. Missing drivers, compiler natives, or a supplied launcher are `BLOCKED`, not passed. Arbitrary shaderpacks and normal Minecraft pass migration remain incomplete. Existing Metal fallback behavior remains independent of this first slice.
