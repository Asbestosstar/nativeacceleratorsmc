# Independent renderer development policy

The Vulkan renderer is an original Native Accelerator subsystem. Its design and implementation must remain
independent of third-party Minecraft renderer implementations.

## Permitted design inputs

Use these as design inputs:

- Minecraft/RenderPearl behavior and interfaces needed for compatibility;
- the Vulkan public specification and platform/window-system APIs;
- JVM/Panama documentation;
- CPU architecture manuals and compiler documentation;
- Native Accelerator's own profiling, traces, benchmarks, tests, and requirements;
- general rendering techniques that are not copied from a particular implementation.

## Do not use third-party renderer source as implementation input

Do not copy, translate, adapt, mechanically rewrite, or transplant third-party renderer code. Also avoid
reproducing distinctive internal class layouts, package layouts, method names, comments, constants,
algorithms expressed in implementation-specific form, or unique internal terminology merely because
another renderer uses them.

When solving a renderer problem, document the requirement first in Native Accelerator terms, then design an
implementation from the public API/specification and measured behavior. Prefer simple descriptive names tied
to our own data flow: section scene, GPU arena, face masks, visibility pass, indirect command buffer, etc.

## Repository hygiene

- Do not add third-party renderer source snapshots, patches, decompiled files, or copied snippets.
- Do not add source comments that cite another renderer as the origin of an implementation.
- Keep design documents centered on Native Accelerator requirements and public APIs.
- New optimizations should include a local benchmark or correctness test where practical.
- If provenance of a proposed code block is unclear, do not merge it until it has been independently
  reimplemented from a written requirement.

This policy is an engineering provenance rule for this repository. It is not a substitute for obtaining
project-specific legal advice when needed.
