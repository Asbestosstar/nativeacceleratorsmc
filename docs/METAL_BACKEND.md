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
3. Minecraft's ordinary `options.txt` is authoritative. The same key now round-trips four values:
   `preferredGraphicsBackend:"default"`, `"opengl"`, `"vulkan"`, or `"metal"`. The old
   `etc/nativeaccelerator-graphics-api.txt` file is migration-only when the standard option is absent.
4. `MinecraftStartupMixin` redirects the constructor's call to
   `PreferredGraphicsApi.getBackendsToTry()`. When `options.txt` selects Metal it returns Metal first
   and OpenGL second without touching Vulkan.
5. Metal crash-loop recovery is owned by Native Accelerator so a prior vanilla OpenGL value cannot
   silently become a permanent override of an explicit Metal selection.
6. `OptionsMetalRestartMixin` extends the ordinary restart-required warning to include a changed
   Native Accelerator graphics preference.

This path uses ordinary Mixins on `VideoSettingsScreen`, `Options`, and `Minecraft`. It no longer
depends on the Mixin config plugin's `ClassNodeHook` mechanism for graphics API selection.

## Performance diagnostics (buildfix11)

The diagnostic Metal build exposes low-overhead per-frame counters. See
`METAL_PERFORMANCE_BUILD_FIX11_2026-09-19.txt`. The most important correctness/performance rule is that
RenderPearl fences must map to asynchronous SDL GPU fences; they must never call `SDL_WaitForGPUIdle` on
creation, because Minecraft rotates fenced ring buffers continuously.


## Runtime capability verification and patched/spoofed macOS stacks (buildfix16 + buildfix17)

Metal feature policy is driven by the actual `MTLDevice`, never by SMBIOS/model allow-lists. This is
important for real Intel Macs, Apple Silicon, OCLP/legacy graphics stacks, Hackintoshes, dual-GPU Macs,
and eGPU configurations.

Before SDL creates its GPU device, `MacMetalCapabilities` queries both `supportsFamily:` and the
non-destructive `supportsFeatureSet:MTLFeatureSet_macOS_GPUFamily2_v1` capability bit documented for
macOS graphics ICB support. It never creates an ICB merely to test support: on legacy hardware Apple's
validation layer may turn that unsupported-resource attempt into a process abort rather than a recoverable
failure. Native ICB drawing requires the Mac2 feature-set result plus matching Mac2/Apple7+ family evidence.

macOS NVIDIA devices are a deliberate exception to advertised-family trust. Any `MTLDevice` name identifying
NVIDIA, GeForce, or Quadro is hard-capped to `MAC1_COMPAT`, even if an OCLP/legacy stack reports Mac2,
Metal3/4, or newer family bits. For that tier Metal validation stays off by default and indirect commands are
CPU-decoded into direct SDL draws. After SDL device creation the SDL-reported GPU is also compared with the
preflight device; a dual-GPU/eGPU mismatch disables native indirect because the preflight query covered a
different GPU. Mac model and macOS version are diagnostic strings only.

After SDL device creation, `MetalRuntimeCapabilities` verifies the portable facilities this backend actually
requires: graphics storage buffers, 2D texture arrays, command submission/fences, and sampler anisotropy.
Failure of a required facility aborts Metal initialization so Minecraft can use the existing OpenGL fallback.
Optional native indirect rendering and anisotropy are one-run blacklisted if they fail at use time.

RenderPearl still sees logical indirect-draw support on every otherwise-usable Metal device. When native
Metal/SDL indirect drawing is unsafe or unavailable (notably MacFamily1), Native Accelerator decodes the
standard 16-byte draw / 20-byte indexed-draw argument structures from the buffer's CPU shadow and emits
semantically equivalent direct SDL draws. This deliberately keeps Minecraft on its instanced
`prepareChunkRendersIndirect` terrain preparation path without creating a graphics ICB on legacy hardware.
Use `-Dnativeaccelerator.renderer.metal.forceCpuIndirect=true` to force this compatibility path for testing.
