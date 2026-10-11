# Master specification — universal renderer and interchangeable shaders

## Objective
Replace or progressively abstract Minecraft Java Edition's rendering with a system in which **backend**, **shader source language**, **graphics API compatibility layer**, and **historical hardware profile** can be selected independently. Faithfully reproduce specification-defined behavior. Enable GLSL→Vulkan, ISL→Vulkan, Cg→Metal, ARB assembly→OpenGL/Vulkan, and comparable paths where possible; do not promise universal convertibility. Shaderpacks of all formats will be installable via a button called "Legacy Shaders" to distringish from the ones builtin to newer Minecraft Versions and the user will be able to select their shackerpacks there, shader packs are installed in the shaderpacks folder. 


Performance and accuracy are the top priority, you can rewrite as much of Minecraft as you want in native code to acheive this in the highest performance  rendering engine possible with maximum throughput and hardware utilization of the users specific hardware from different gpu, cpu and OS vendors and types to squeeze everything out of the users computer.

## Preliminary repository inspection
Inspect Minecraft version, loader, mappings, Java, LWJGL version, windowing/context code, texture atlas, terrain vertex formats, render commands, GUI, third-party mods, current GLSL, platform assumptions, Gradle tasks. Record findings and blockers in `docs/renderer-existing-system.md`. Do not assume the whole game is replaceable through a shader hook.

## Five independent layers
1. **Minecraft bridge:** geometry, chunks, clouds, entities, overlays, particles, fluids, sky, GUI, texture uploads, swap/present.
2. **Universal Render Device (URD):** instance/device/surface, command lists, graphics/compute pipelines, buffers, images, samplers, descriptors, barriers, timestamps, presentation; validation and lifetime rules.
3. **Shader frontend framework:** parse exact input language/profile, typed AST, diagnostics, source maps, semantic validation, interface/reflection.
4. **Semantic compiler + graph planner:** high-level appearance/light/renderer-service IR and lowerable GPU-stage IR; pass decomposition and capability negotiation; resource/interface layout.
5. **Backend executors:** OpenGL, Vulkan, Metal, D3D, software; adapters and device profiles are composable layers, not backend duplicates.

## Repository layout suggestion
```text
renderer-api/ renderer-minecraft/ render-graph/ shader-api/ shader-ir/
shader-glsl/ shader-isl/ shader-arb/ shader-cg/ shader-hlsl/ shader-wgsl/
shader-rsl/ shader-osl/ backend-opengl/ backend-vulkan/ backend-metal/
backend-d3d12/ backend-software/ compatibility-sgix/
compatibility-irisgl/ compatibility-hp/ compatibility-ibm/
conformance/ examples/ tools/ docs/
```
Do not create dozens of empty modules up front; make each module earn its existence with executable tests.

## Minimum interfaces (conceptual, not frozen Java APIs)
- `RendererBackend`: probe/capabilities, create device, create surface/swapchain, submit/present, loss/recovery.
- `RenderDevice`: resources, synchronization, pipeline builds, queue submission.
- `ShaderFrontend`: language/version detection, parse/compile, diagnostics, reflection, supported services.
- `ShaderProgram`: source provenance, entrypoints, stages, resource reflection, specializations, required features.
- `CompatibilityProfile`: target API/hardware state, constraints, emulation requirements, errors.
- `MaterialAppearance`: shader graph, textures, parameter values, render-state recipe, stage connections.
- `RenderGraph`: typed resources, lifetime, passes, read/write usages, hazards, barriers, attachments.
- `CapabilityPlanner`: returns NATIVE, EXACT_TRANSLATION, SEMANTIC_EMULATION, APPROXIMATION_OPT_IN, or UNSUPPORTED; report why.

## Semantic requirements
- Frontends preserve original grammar, versions, precision and resource/stage rules. No regex pseudo-compilers.
- Preserve historical framebuffer reads, multipass composition, light shader execution, implicit derivative or render service requirements. A simple fragment `main()` cannot express every historical shader.
- Avoid claiming numerical bit identity where the standard permits implementation-dependent floating-point behavior.
- No silent replacement of reflection, shadows, procedural shading, depth, derivatives, fog or lighting with unrelated effects.
- Custom shader/material manifest is external host metadata, not retroactively part of ISL, GLSL or Cg.

## Portability
- CPU side in portable Java where appropriate; native libraries isolated behind small replaceable ABI surfaces.
- No x86, little-endian, Windows/Linux, GL context or GPU assumptions in compiler code.
- Evaluate macOS/JDK restrictions and separate Metal bridge; legacy Solaris SPARC/AIX can target software Mesa or available native backends if dependencies actually exist.
- Software lavapipe is a Vulkan ICD, llvmpipe is a software OpenGL driver; they are not separate rendering API semantics.

## Cross-API constraints
Resource binding, clip/depth conventions, Y directions, sample locations, border colors, image formats, synchronization, texture derivatives, framebuffer fetch, mesh stages, ray tracing, compiler precision, and matrix conventions must be explicitly lowered or rejected.

## Definition of Done
- Full test scene through GL and Vulkan; independently verifiable command execution.
- GLSL source works on GL and Vulkan with correct reflection and descriptors.
- At least one historical source program executes unchanged through a modern backend.
- Minecraft selected terrain, entities, transparency, UI and presentation use same URD without unmanaged GL dependencies for the alternate backend.
- Device loss, resize, world unload, reload, error fallback, cleanup tested.
- Every claimed language feature and API operation has a source spec link and conformance tests.
