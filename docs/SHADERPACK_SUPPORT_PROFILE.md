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

The catalog recognizes conventional OptiFine/Iris/ShaderMod-style layouts without requiring a
Native Accelerator-specific manifest. Recognition is not execution. A conventional pack may contain
`shaders.properties`, `program/` sources, and `final*`, `composite*`, `gbuffers_*`, `shadow*`, or
`deferred*` programs; conventional selection now enters the conventional inventory/preparation path
instead of being sent to the Native Accelerator manifest parser. That path currently reports exact
unsupported feature requirements or `FRONTEND_ONLY`. A highly constrained single shared
`final.vsh`/`final.fsh` candidate can be prepared and inserted as an in-frame `FrameGraphBuilder` pass
when the optional client hook resolves; this is an experimental fullscreen-only seam, not general
conventional compatibility. There is no validated end-to-end conventional execution milestone yet.
World-stage, shadow, deferred, temporal, custom-dimension, geometry/tessellation/compute, image, SSBO,
and multiple-render-target features remain unsupported until their frame-graph and backend conformance
tests pass.

The installed AstraLex V93.0 archive was inventoried read-only as a representative target (382 entries;
no pack source is redistributed here). It contains 101 vertex/fragment stage pairs across dimension
folders, including `gbuffers_*`, shadow, deferred, composite passes through `composite14`, and final;
its properties file has 413 key/value settings, nine profiles, custom options, conditional program
enablement, uniforms, variables, texture remaps, and blend/alpha policies. This exceeds the current
execution bridge substantially. The pack is recognized and routed to conventional preparation, but is
not supported for execution: its world/shadow/deferred lifecycle, property-driven program selection,
resources and framebuffer semantics are not implemented. Do not remove passes or apply only its final
shader as a substitute; that would not be compatible rendering.

| Area | Declared contract | Current evidence |
|---|---|---|
| Containers | Bounded directory, ZIP, and JAR source views; normalized paths; no symlink or traversal escape | Unit-tested source provider |
| Source identity | Pack/source/dependency/compiler/backend/device/attachment/render-state hashes | Unit-tested compilation key |
| GLSL | Real RenderPearl compiler boundary; conventional wrapper sources preserve their declared desktop version, including GLSL 130, and are accepted only when the backend compiler accepts that profile | Preparation path exists; native compiler may be blocked |
| Stages | Vertex and fragment for the fullscreen fixture | Manifest/parser and interface checks are unit-tested; GPU execution pending |
| Includes | Bounded relative source includes and cycle detection | Unit-tested source resolver and preprocessor |
| Resources | The first fixture has no descriptors or push constants; general texture, sampler, UBO, SSBO, and image binding is not executable | Narrow fixture only |
| Passes | One `fullscreen-postprocess` pass reading/writing Minecraft's `main` target is defined; live frame-graph attachment is pending | Preparation/parser path only |
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

## Conventional compatibility milestone

`ShaderpackConventionalScanner` is a metadata-only, loader-neutral inventory. It normalizes a
pack root, parses bounded `shaders.properties` key/value pairs, groups conventional stage files,
and reports world-stage and resource features requiring later adapters. It does not assign
`EXECUTABLE`, compile source, or promise compatibility from names alone. The native manifest fixture
remains available as a separate controlled smoke path.

## First executable fixture milestone

The repository includes a project-owned fixture at
`src/main/resources/nativeaccelerator/shaderpacks/fixture/`. It uses
`nativeaccelerator.shaderpack.json`, GLSL 460 vertex/fragment stages, no descriptors or push
constants, and one `fullscreen-postprocess` declaration for the `main` target. The parser and
preparation service can discover it, hash its source closure, preprocess includes, and invoke the real
RenderPearl GLSL compiler. The optional `scripts/run-metal-shaderpack-smoke.sh` then routes the
prepared modules through the existing borrowed-device URD path and validates an actual readback when
run inside the user's Minecraft 26.3 Metal client.

This fixture path is a controlled GPU smoke test, not a general Minecraft post-effect replacement:
the current implementation does not yet attach arbitrary shaderpack passes to `GameRenderer`'s
`PostChain`/`FrameGraphBuilder`, and it does not claim Iris/OptiFine shaderpack compatibility. A
missing LWJGL compiler native or unusable Metal device is reported as `BLOCKED`; no GPU evidence
marker is emitted in that case.


## GLSL normative references

The compatibility layer uses the real RenderPearl compiler for GLSL lexing, preprocessing, parsing,
type checking, linking, and SPIR-V generation. It does not claim that a project-side regular-expression
or partial parser implements the language specification. The normative references are:

- [GLSL 4.60.8 HTML](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html)
- [GLSL 4.60.8 PDF](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf)
- [KhronosGroup GLSL repository](https://github.com/KhronosGroup/GLSL)

The project-side preprocessor is limited to bounded shaderpack source resolution, wrapper defines,
conditional selection, source provenance, and target-directive extraction. A missing compiler native is
`BLOCKED`, not a successful fallback or conformance result.

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
