# Verification, conformance and evidence policy

## Classification
Per language/version and backend/API/device profile tag every feature: SPEC_LOCATED, IMPLEMENTED, UNIT_TESTED, GPU_TESTED, CROSS_BACKEND_TESTED, HARDWARE_VERIFIED, INCOMPLETE, BLOCKED, UNDEFINED_BY_SPEC. Complete means each defined normative feature has traceable tests; hardware differential verification is a separate stronger status.

## Required suites
1. Source positive/negative grammar, typing, qualifiers, function overloads, preprocessor includes.
2. Semantic IR deterministic reference interpreter and optimizer equivalence.
3. State machine initial defaults, invalid enums/values/operations and query behavior.
4. Shader reflection, descriptor/resource layouts, IO, coordinate conventions.
5. Pixels: perspective interpolation, textures, sampling, sRGB, lightmaps, alpha, depth, fog, shadows, blending, multipass, framebuffer reads, resizing.
6. GPU lifecycle: creation/destruction, fence-based retirements, reloads, device loss, context invalidation, memory leaks.
7. Minecraft gameplay scenes: chunks, translucent sorting, fluids, GUI, entity animations, sky, particles, mods.
8. Cross-backend paired renders: OpenGL/Vulkan; optional Metal/D3D/CPU; interpret numerical differences per standards.
9. Platform testing (specific JVM, architecture, OS, drivers), with skip-not-pass when unavailable.
10. Input validation/attack resistance for shader packs, bounded resources, include containment and complexity.

## CI guidance
Run standalone compiler/unit tests on every commit; software OpenGL/Vulkan offscreen tests when runner offers drivers; platform-specific native graphics tests on appropriate hosts; nightly end-to-end Minecraft scripted scenes. Save compiler logs, GPU validation output, timings, screenshots/EXR and hashes with provenance. Do not claim tests were run unless executed.

## Initial acceptance checklist
- [ ] Repo audit and end-to-end renderer architecture documented
- [ ] URD/OpenGL sample renders with state/resource tests
- [ ] URD/Vulkan sample renders with validation layers
- [ ] One GLSL source drives both GL and Vulkan test scenes
- [ ] First Minecraft opaque mesh uses URD
- [ ] Full Minecraft GUI, particles, fluid and entity migration tested
- [ ] At least one verified historical language shader works
- [ ] SGIX individual extension test and historic precision metadata
- [ ] HP VISUALIZE device profiles source-linked and tested
- [ ] App resource reload, resize, shutdown leak-free
- [ ] Unsupported conversions return explicit reasons
