# Metal active-uniform packing fix23

Fix22 correctly lowered triangle fans and introduced uniform packing, but it assumed every logical
uniform/push-constant slot declared by the RenderPearl pipeline would survive SPIRV-Cross into the
MSL entry-point signature. Minecraft 26.3 terrain pipelines disproved that assumption: the pipeline
reports five logical vertex uniform resources, while generated MSL contains only buffers 0..3 because
the logical push-constant slot 4 is unused and optimized away.

Fix23 treats generated MSL as authoritative. `MetalUniformPacking.lower` packs only actual overflow
`constant T& [[buffer(N)]]` parameters that survived translation. It returns the active logical slot
set and the byte range actually required. `MetalSpirvCompiler` then suppresses CPU uploads for any
packed uniform or push-constant slot that is absent from the generated MSL.

For the observed terrain shaders this changes the layout from an assumed `[3,4]` / 1024-byte packed
region to actual `[3]` / 512 bytes, while retaining physical SDL uniform slots 0..3.

Startup marker:

`[Native Accelerator] Build marker: metal-uniform-active-fix23`
