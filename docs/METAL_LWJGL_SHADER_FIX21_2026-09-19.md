# Metal LWJGL shader-create fix21

The GT 650M MacFamily1 path and render-pass binding now both pass startup.

The next crash occurred while creating `minecraft:pipeline/gui`:

`No compatible native binding: org.lwjgl.sdl.SDL_GPUShaderCreateInfo.code_size[java.lang.Long]`

This is a generated-binding shape issue, not a Metal 1 feature failure.

LWJGL's `SDL_GPUShaderCreateInfo` exposes `code_size()` as an instance getter, but the writable
form is `SDL_GPUShaderCreateInfo.ncode_size(long struct, long value)`. Its `entrypoint(...)`
setter accepts a null-terminated UTF-8 `ByteBuffer`, not a Java `String`.

Fix21 therefore makes shader creation fully typed:
- allocate `SDL_GPUShaderCreateInfo` directly;
- provide direct UTF-8 buffers for MSL source and entry point;
- set `code_size` through `ncode_size(ci.address(), ...)`;
- call typed `SDLGPU.SDL_CreateGPUShader(device, ci)`;
- retain the fix20 typed `SDL_BeginGPURenderPass` call.

`MetalInterop.set` also has a conservative fallback to a generated static `n<field>` setter when
an instance setter is absent, which protects similar LWJGL count-field shapes elsewhere.

