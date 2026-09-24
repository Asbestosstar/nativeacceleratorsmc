# Metal state parity fix29

Fix29 starts from the confirmed-clean fix28 source tree. The optimized atlas, three-frame policy,
MacFamily1/NVIDIA handling, typed LWJGL bindings and active-resource MSL generator are unchanged.

This pass corrects four RenderPearl/Vulkan parity gaps in the SDL GPU Metal backend:

1. **Depth/no-depth pipeline variants.** RenderPearl's Vulkan backend creates a D32-compatible
   graphics pipeline even when depth testing is disabled, plus a no-depth variant. Metal pipeline
   compatibility also includes the depth attachment format, so fix29 mirrors this behavior and binds
   the variant matching the current render pass.

2. **Regional GUI atlas clears.** Minecraft's `GuiItemAtlas` clears one recycled slot with the
   regional `clearColorAndDepthTextures(..., x, y, w, h, mip)` overload. An SDL render-pass
   `LOADOP_CLEAR` clears the complete attachment, so the old Metal implementation erased unrelated
   GUI atlas slots. Fix29 clears the requested RGBA8_UNORM + D32_FLOAT subregion via tightly packed
   GPU texture uploads instead.

3. **Render-area scissor initialization.** Vulkan initializes every pass scissor from
   `RenderPassDescriptor.renderArea`. SDL begins with a full-target default scissor, so fix29
   explicitly sets RenderPearl's render area at pass creation.

4. **Sampler mip parity.** Vulkan selects mipmap filtering from `maxLod > 0.25` and clamps max LOD
   to at least 0.25. Metal previously derived mipmap mode from the minification filter. Fix29 mirrors
   Vulkan's sampler behavior.

Fix29 also defaults SDL rasterizer front-face winding to CLOCKWISE, matching RenderPearl's Vulkan
pipeline state under its top-left framebuffer convention. For A/B testing it can be switched without
rebuilding:

`-Dnativeaccelerator.renderer.metal.frontFace=counter_clockwise`

Runtime marker:

`[Native Accelerator] Metal state marker: fix29; frontFace=clockwise; depthPipelineVariants=true; regionalClears=upload; samplerMipParity=true`

