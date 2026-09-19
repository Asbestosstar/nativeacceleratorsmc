# SDL3 / Metal RenderPearl backend

This tree adds an experimental Metal backend for Minecraft 26.3 RenderPearl using the SDL3 GPU API.

## Integration

Native Accelerator exposes its own four-value Graphics API selector in `VideoSettingsScreen` and redirects Minecraft's constructor-time backend-list lookup. This avoids modifying the JVM's fixed `PreferredGraphicsApi` enum while still preserving explicit OpenGL and Vulkan selections and Minecraft's boot fallback behavior.

The backend implements the RenderPearl backend interfaces under:

`src/main/java/com/asbestosstar/nativeaccelerator/renderer/metal/`

It provides SDL GPU device/surface management, buffers, textures, samplers, transfer/copy operations, render passes, pipelines, indirect draws, fences, and SPIR-V to MSL translation through LWJGL's typed SPIRV-Cross bindings (`org.lwjgl:lwjgl-spvc:3.4.3`).

## Shader path

RenderPearl supplies SPIR-V modules. `MetalSpirvCompiler` translates them to MSL and remaps resources to SDL GPU's Metal binding convention before `SDL_CreateGPUShader`.

SDL GPU exposes four pushed uniform-data slots per stage. This implementation rejects a shader stage requiring more than four UBO/push-constant slots rather than silently misbinding resources.

## Minecraft 26.3 texel-buffer / cloud path

Minecraft 26.3 binds `CloudFaces` as `UniformType.TEXEL_BUFFER` with `GpuFormat.R8_SINT`. SDL GPU does not expose a texel-buffer binding category, but it does expose read-only graphics storage buffers on Metal.

The Metal shader path therefore performs a narrow lowering:

1. SPIRV-Cross is asked to emit MSL 2.1 native `texture_buffer` syntax for the SPIR-V texel buffer.
2. `MetalTexelBufferLowering` rewrites an `R8_SINT` `texture_buffer<int>` argument to `device const char*` in the MSL source.
3. Each `texture_buffer.read(index)` is rewritten to an `int4` reconstructed from the signed byte in the storage buffer, preserving texel-fetch component semantics for the cloud shader.
4. The buffer is declared after pushed uniform buffers in the MSL `[[buffer]]` table, matching SDL GPU's Metal resource convention.
5. `MetalRenderPass` binds the same RenderPearl `GpuBuffer` with `SDL_BindGPUVertexStorageBuffers` or `SDL_BindGPUFragmentStorageBuffers`.

No cloud-specific draw suppression remains and no extra cloud texture upload/copy is performed.

The lowering currently supports `R8_SINT` texel buffers only. A future RenderPearl texel-buffer format should add an explicit format-specific lowering rather than silently treating all formats as byte arrays. SDL's storage-buffer binding is whole-buffer only, so a non-zero-offset texel-buffer slice is rejected; Minecraft's current `CloudFaces` path binds the complete ring-buffer allocation.

## Selection property

`-Dnativeaccelerator.renderer.metal=auto` (default)

`off`, `false`, `0`, or `disabled` prevents Metal from being offered. Other values still require the SDL capability probe to pass.

## Validation status

The implementation was checked against the supplied Minecraft 26.3 decompiled RenderPearl contracts and current SDL3 GPU/LWJGL/SPIRV-Cross API shape. SPIRV-Cross is a normal Maven compile dependency, not reflection; add `org.lwjgl:lwjgl-spvc:3.4.3` to the real project POM (see `MAVEN_DEPENDENCIES.md`). The supplied project export did not contain `pom.xml`, so a real project compile and runtime macOS launch could not be executed in this environment. Treat the first macOS launch as integration validation and keep vanilla OpenGL/Vulkan fallback enabled.

## Minecraft graphics-settings integration

Minecraft 26.3's vanilla Graphics API option is hard-wired to the three constants in
`PreferredGraphicsApi`: Default, OpenGL, and Vulkan. Earlier builds attempted to inject a fourth
Java enum constant from the Mixin config plugin. That proved too fragile in a real launch and has
been removed.

The current integration does not alter the enum at all:

1. `VideoSettingsMetalOptionMixin` redirects only the `preferredGraphicsBackend()` lookup used by
   `VideoSettingsScreen.displayOptions()`.
2. `MetalGraphicsOption` supplies a normal `OptionInstance<GraphicsApiChoice>` containing Default,
   OpenGL, Vulkan, and Metal.
3. Metal is persisted in `etc/nativeaccelerator-graphics-api.txt`; the vanilla `options.txt` mirrors
   Metal as Default, so removing the mod leaves a safe vanilla configuration.
4. `MinecraftStartupMixin` redirects the constructor's call to
   `PreferredGraphicsApi.getBackendsToTry()`. When the Native Accelerator preference is Metal and
   vanilla selection is Default, it returns Metal first and OpenGL second.
5. A forced/crash-recovery OpenGL or Vulkan selection is not overridden, preserving Minecraft's
   recovery path.
6. `OptionsMetalRestartMixin` extends the ordinary restart-required warning to include a changed
   Native Accelerator graphics preference.

This path uses ordinary Mixins on `VideoSettingsScreen`, `Options`, and `Minecraft`. It no longer
depends on the Mixin config plugin's `ClassNodeHook` mechanism for graphics API selection.
