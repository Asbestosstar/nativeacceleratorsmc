# ARB assembly, NVIDIA NV programs, NVIDIA Cg — shader-language frontends

## Scope and normative sources
ARB vertex/fragment program grammar from the Khronos extension texts; NV vertex/fragment programs and register combiners from their actual NVIDIA/Khronos documents; NVIDIA Cg language and profiles from archived Cg Toolkit docs. These are distinct families. Treat each profile's instruction set, limits and precision as normative.

## ARB frontends
Implement independent parsers for `ARB_vertex_program` (`!!ARBvp1.0`) and `ARB_fragment_program` (`!!ARBfp1.0`) including declarations, temporary/input/output/environment/local parameters, aliases, swizzles, masks, relative addressing and instruction modifiers as documented. Validate instruction limits/profile-dependent restrictions; type and register legality; position invariance and fog/depth/sample-related behavior. Preserve saturate modifiers, precision and texture target operations. Compile to typed register-machine IR, then SSA/GPU IR with explicit semantics. Provide CPU instruction interpreter and grammar fixtures.

## NVIDIA NV families
Inventory exact `NV_vertex_program` version variants, `NV_fragment_program` variants, texture shader and register combiner extensions. Distinguish stage configuration via GL state from executable assembly. Preserve combiner constant/reg sources, scales/bias, dot products, mux, clamping, final combiner ordering, blending/depth interactions. Identify incomplete/hardware-constrained profiles and reject if no emulation possible.

## NVIDIA Cg
Treat Cg language, version and **target profile** separately (e.g. historical OpenGL/D3D vertex/fragment profiles). Parse Cg semantics and entrypoints; import historical examples without rewriting. Preserve parameter qualifiers, bindings, structures, semantic names, matrix conventions, profile resource limits and compiler diagnostics. Examine archived SDK/license prior to bundling original binaries; a maintained reimplementation may be necessary. No promise that old Cg binary executables run on Solaris/AIX/modern macOS.

## Cross-backend
ARB/NV/Cg -> semantic/profile IR -> GPU IR -> GLSL/SPIR-V/MSL/DXIL as supported. Build explicit mappings for registers, texture units, stage IO and constants. Renderer-wide state (register combiners) may need passes or pipeline state adapters, not just shader source. Unsupported precision and texture operations emit typed errors.

## Acceptance
Run canonical source fixtures per historical revision, differential compare to original supported GL driver if available, numerical opcode tests, GL/Vulkan target pair for at least one sample, invalid instruction diagnostics, shader cache and Minecraft material binding. Track conformance by **specific language revision/profile**, not one vague NVIDIA support flag.
