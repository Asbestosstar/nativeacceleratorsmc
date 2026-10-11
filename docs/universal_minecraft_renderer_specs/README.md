# Universal Minecraft Renderer — Claude Code specification pack

**Purpose:** Implement independent renderer backends, shader-language frontends, historical API adapters, and hardware profiles for Minecraft Java Edition. A shader source may execute through a different backend when a correct translation or explicit emulation exists.

**This archive is a set of engineering instructions and specifications, not implementation code.** It includes expanded, consolidated versions of the earlier ISL, SGIX, and HP VISUALIZE requests; these are project specifications referencing the original historical standards, not substitutes for the standards themselves.

## Start here

1. `core/00_MASTER_ARCHITECTURE.md` — give this to Claude Code first.
2. `core/01_MINECRAFT_INTEGRATION.md` — establish an achievable renderer migration strategy.
3. `core/02_SHADER_COMPILER_AND_IR.md` and `core/03_CAPABILITY_MATRIX.md` — ensure shader/backend independence.
4. `core/04_IMPLEMENTATION_ROADMAP.md` — implement a working vertical slice before expanding.
5. Relevant backend and shader language files; use independent tasks/agents when possible.
6. `testing/CONFORMANCE_AND_TESTING.md` — evidence requirements.

## Directory map

- `core/`: master specification, compiler architecture, material model, interfaces, planning.
- `legacy/`: SGI ISL, SGIX, HP VISUALIZE, ARB/NVIDIA programs, legacy IBM/AIX/zOS.
- `modern/`: OpenGL, Vulkan, Metal, Direct3D, software backends.
- `shader_languages/`: GLSL, HLSL/Cg/WGSL, RenderMan/OSL.
- `testing/`: conformance requirements and initial acceptance matrix.
- `reference/`: normative specification links.

## Terminology (non-negotiable)

- **Rendering backend:** OpenGL, Vulkan, Metal, D3D12, software renderer.
- **Shader language frontend:** GLSL, ISL, HLSL, Cg, WGSL, RSL, OSL, ARB/NV assembly.
- **Graphics API compatibility adapter:** IRIS GL, SGIX, Starbase, PHIGS, PEX, graPHIGS, GDDM.
- **Hardware profile:** HP VISUALIZE-FX, SGI graphics subsystems, etc. Profiles constrain observable capabilities; they are not shader languages.
- **Intermediate representation:** typed program/appearance semantics plus a lower-level executable representation when feasible. SPIR-V is not a universal representation for all historical/renderer-service shading systems.

## What counts as success

- Same materials and Minecraft scene render on two real independent backends (OpenGL and Vulkan), with cross-backend shader portability demonstrated.
- At least one historical shader frontend compiles unmodified valid source and runs via a modern backend.
- Unsupported conversions generate reasons, never fake results.
- Full Minecraft compatibility must be verified separately from sample-scene demos.
- Historical conformance claims require specification-linked tests and disclosure of hardware-dependent/undefined behaviors.

## Important boundaries

- **Do not claim this is an official SGI/HP/NVIDIA implementation.**
- Exact SGI ISL 3.0 behavior is the compatibility baseline; verified newer ISL documents may extend it; custom extensions must carry non-official names.
- SGIX is an OpenGL extension family, not a shader language; HP VISUALIZE is a hardware family, not a graphics API.
- z/OS GDDM is not a drop-in 3D graphics backend. Legacy IBM APIs need adapters and potentially CPU rendering.
- Only source code and documentation authored for this project are in the archive; official manuals are linked, not redistributed.
