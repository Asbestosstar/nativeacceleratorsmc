# Shaderpack support profile

## Purpose

Native Accelerator uses an explicit support profile rather than treating every file containing
`.glsl` as executable. A pack is executable only after its complete source dependency closure,
reflection, bindings, render graph, backend capabilities, and GPU pipeline set have been validated.
A catalog entry is metadata, not runtime evidence.

"100% GLSL support" means 100% conformance to the versioned feature matrix published here and to
the shaderpack manifest contract implemented by the runtime. It does not mean every undocumented
third-party pack, vendor extension, historical SGIX/ISL/Cg language, undefined behavior, or every
GPU driver.

## Initial declared profile

| Area | Declared contract | Current evidence |
|---|---|---|
| Containers | Bounded directory, ZIP, and JAR source views; normalized paths; no symlink or traversal escape | Unit-tested source provider |
| Source identity | Pack/source/dependency/compiler/backend/device/attachment/render-state hashes | Unit-tested compilation key |
| GLSL | Real RenderPearl compiler boundary; initial proven fixture is GLSL 460 | Frontend-only; native compiler may be blocked |
| Stages | Vertex and fragment for the initial URD material | Triangle fixture only |
| Includes | Bounded relative source includes and cycle detection | Unit-tested source resolver |
| Resources | Reflection model exists; general texture, sampler, UBO, SSBO, image, and push binding is not yet executable | Incomplete |
| Passes | Backend-neutral graph validator exists; pack pass graph is not yet wired to Minecraft | Incomplete |
| OpenGL/Vulkan/Metal | Must use Minecraft's created RenderPearl device; no duplicate context/device/surface/swapchain | Device ownership unit-tested; GPU evidence blocked/incomplete |
| SGI/SGIX | SGI is an OpenGL profile; SGIX extensions require separate extension-specific semantics and tests | Unsupported/incomplete |

## Outcomes

- `NATIVE`: executed directly by the active backend.
- `EXACT_TRANSLATION`: backend lowering preserves the declared semantics.
- `SEMANTIC_EMULATION`: explicit additional passes/services preserve the declared semantics.
- `APPROXIMATION_OPT_IN`: only selectable after explicit user opt-in; never implicit.
- `UNSUPPORTED`: the capability cannot be represented faithfully.
- `BLOCKED`: execution could not be proven on this host because a compiler, driver, device, or
  security budget was unavailable.
- `INVALID`: the pack is malformed or violates the source/manifest contract.
- `FRONTEND_ONLY`: source metadata or compilation exists, but complete pass execution is absent.

`EXECUTABLE` is assigned only after a complete immutable generation is prepared and published. A
failed reload retains the previous valid generation or returns to vanilla rendering. It never leaves
partially-created pipelines active.

## Backend policy

OpenGL, Vulkan, and Metal consume the same loader-neutral shader and pass declarations. OpenGL may
compile the declared GLSL profile directly. Vulkan requires target-environment validated SPIR-V.
Metal uses the existing RenderPearl/SPIR-V-to-MSL route with explicit resource remapping and device
feature checks. The native Vulkan scene companion is not a shaderpack device and must not create a
second Minecraft presentation path.

SGI/OpenGL compatibility is not a shader language. Historical SGIX state-machine behavior is a
separate compatibility project and is unsupported unless the extension is advertised and its own
conformance fixture passes.

## Security limits

Shaderpack input is untrusted. The source provider bounds entry count, individual source bytes,
total bytes, normalized paths, archive reads, include depth, and dependency expansion. Future
compiler stages must add budgets for preprocessing expansion, programs, passes, resources, backend
compilation time, and cache space. Static checks are not an operating-system sandbox; no pack-provided
native code or network resource is loaded.
