# Metal MSL resource-layout fix27

This build starts from the actual buildfix24 Metal tree. It does **not** contain buildfix25's resource cycling, one-frame MacFamily1 policy, clear-target cycling, or quad experiment, and it does not contain buildfix26's vanilla-atlas diagnostic gate. The optimized Native Accelerator atlas path is restored unchanged.

## Generator correction

SDL GPU's MSL ABI requires active resource tables to begin at zero and be consecutive. The old translator assigned Metal indices from the RenderPearl pipeline's declared resource list before SPIRV-Cross optimization. If SPIRV-Cross removed an unused UBO, push constant, sampled texture, or sampler, the emitted MSL could contain gaps while `SDL_GPUShaderCreateInfo` still advertised the pre-optimization counts.

Fix27 treats the generated MSL entry signature as authoritative:

- active constant-buffer/push-constant arguments are compacted to consecutive `[[buffer(n)]]` slots;
- CPU uniform pushes are remapped to the same physical slots;
- active sampled textures and sampler objects are compacted independently to consecutive `[[texture(n)]]` and `[[sampler(n)]]` slots;
- CPU sampler binding is remapped to the same compact table;
- active texel-buffer resources are lowered only after the final uniform count is known, so SDL storage buffers begin immediately after active uniform buffers;
- optimized-away texel buffers no longer reserve gaps;
- `num_uniform_buffers`, `num_samplers`, and `num_storage_buffers` now describe the active generated shader rather than the pipeline superset.

This follows SDL_CreateGPUShader's MSL rule that each argument table starts at zero and contains no gaps, with uniform buffers first in `[[buffer]]` and storage buffers immediately after them.

Startup marker: `[Native Accelerator] Build marker: metal-msl-layout-fix27`.
