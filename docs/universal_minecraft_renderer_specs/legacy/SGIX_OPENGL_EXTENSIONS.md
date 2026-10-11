# SGIX OpenGL extension compatibility engine — detailed specification

## Scope
Reproduce original Silicon Graphics **SGIX OpenGL extensions** as extensions to the applicable historical OpenGL state machine. SGIX is a family of OpenGL features, not a language, rendering API replacement, or universal shader profile. Support native historical GL where actually advertised, modern shader/multipass emulation otherwise.

## Sources
Khronos registry https://registry.khronos.org/OpenGL/extensions/SGIX/ and each individual `.txt` specification; OpenGL 1.x base specifications and dependencies including EXT and SGIS extensions. Before coding each extension record: status (published vs incomplete), version, revision, commands and exact signatures, token values, dependencies, default state, state queries, effects, precise pipeline placement, errors, undefined behavior, and interactions. `SGIX_fragment_specular_lighting` is an incomplete proposal; do not advertise complete conformity.

## State-machine architecture
`SGIXExtensionRegistry`, `CompatibilityGLContext`, per-extension state, exact GL tokens/commands, OpenGL error queue and state query layer, graphics pipeline graph, capability checker, execution backend. Java wrappers are optional; historical API fidelity requires faithful signatures and state transitions, not made-up generalized commands.

## Priority functionality
1. Texture stages: `SGIX_texture_add_env`, `SGIX_texture_scale_bias`, `SGIX_texture_lod_bias`, `SGIX_texture_coordinate_clamp`, `SGIX_texture_mipmap_anisotropic`, `SGIX_cube_map`, `SGIX_clipmap`, `SGIX_pixel_texture` families; implement equation ordering, format-dependent behavior, sampling/completeness, texture coordinate restrictions and residency where defined.
2. Depth/shadows: `SGIX_depth_texture`, `SGIX_shadow`, `SGIX_shadow_ambient`; actual depth compare modes/results, filtering and ambient behavior. A modern shadow sampler is only valid if validated equivalent.
3. Lighting: `SGIX_fragment_lighting_space`, nonlinear lighting/spotlight variants and explicitly experimental fragment specular proposal; keep vertex vs fragment state, normals/space, material/light/color, pipeline post-texture ordering per spec.
4. Fog: `SGIX_fog_blend`, `fog_factor_to_alpha`, `fog_layers`, `fog_offset`, `fog_patchy`, `fog_scale`, `fog_texture`; per-extension formulas, compositing and interaction with alpha.
5. Blend/framebuffer: `SGIX_blend_alpha_minmax`, `blend_cadd`, `blend_cmultiply`, `framezoom`, `subsample`, `resample`; preserve blend destination reads and semantics.
6. Survey full SGIX registry and classify texture/geometry/display/instrumentation extensions. Reject unsupported GLX or hardware-specific operations with precise diagnostics.

## Pipeline fidelity
The implementation must place each extension at the position stipulated by its specification, not append everything as a postprocess. Use typed graph stages for transforms, lighting, texgen, sampling, texture environment, fragment lighting, fog, tests, framebuffer blend and presentation. Blend operations may require special framebuffer mechanisms; avoid illegal read/write texture feedback. Preserve clamping, fixed-point/precision limits where defined, defaults and state-change timing.

## Minecraft integration
Attach extension state to material/pipeline metadata without inventing historical API tokens. Support blocks/entities, textures and lightmaps, correct chunk batching and render layers; explicit mapping between Minecraft lighting and historical GL light state. Save/restore GL state or use isolated backend command streams. Detect shader-pack clashes and fallback safely.

## Tests and completeness
Per-extension API tests (defaults, valid/invalid, enable/disable, queries); mathematical CPU reference (texture combines, shadow compare, fog, blending); GPU offscreen pixel fixtures; dependency/interaction tests; leak-free reload/resizing; compare original IRIX driver outputs if possible. Track each extension as researched, partial, tested, or hardware-verified; never claim all SGIX complete from a few visual effects.
