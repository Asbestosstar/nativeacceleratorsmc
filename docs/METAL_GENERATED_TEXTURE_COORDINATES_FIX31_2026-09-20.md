# Metal generated-texture coordinate fix31

This build is based directly on clean fix30.

Observed split on the GT 650M/OCLP test host:
- uploaded/title textures render normally enough (title panorama, world thumbnails, clouds/sky);
- the original corruption remains in GUI-generated resources, lightmap-driven lighting, colors and
  transparency.

Minecraft 26.3 compiles GLSL to Vulkan-target SPIR-V. Native Accelerator translates that SPIR-V to
MSL itself. SPIRV-Cross exposes the common `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y` correction.

Fix31 deliberately does NOT apply this globally. A global raster change previously regressed the
Mojang logo/title panorama in fix29.

Only these generated-texture pipelines are affected:
- `minecraft:pipeline/animate_sprite_blit`
- `minecraft:pipeline/animate_sprite_interpolate`
- `minecraft:pipeline/lightmap`

For their vertex stages:
- SPIRV-Cross receives `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y=true`;
- the corresponding SDL graphics pipeline uses clockwise front-face winding because a Y flip reverses
  triangle winding.

Every other pipeline keeps fix30's counter-clockwise winding and unmodified MSL position convention.

A/B escape hatch:
`-Dnativeaccelerator.renderer.metal.generatedTextureYFlip=false`

Expected marker for each affected pipeline:
`[Native Accelerator] Metal coordinate marker: fix31; pipeline=...; vertexYFlip=true; frontFace=clockwise`

The fix30 regional-clear correction remains present and the optimized Native Accelerator atlas path is
unchanged.

