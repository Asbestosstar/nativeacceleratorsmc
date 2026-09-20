# Metal LWJGL render-pass binding fix 19

The NVIDIA/MacFamily1 capability clamp and transfer-buffer mapping fix are preserved.

Minecraft 26.3 with the LWJGL SDL snapshot exposes `SDL_BeginGPURenderPass` using the
Java-friendly generated signature:

```text
SDL_BeginGPURenderPass(long commandBuffer, SDL_GPUColorTargetInfo.Buffer colors,
                       SDL_GPUDepthStencilTargetInfo depth)
```

The native C API still has the explicit `num_color_targets` argument and LWJGL exposes that
shape as `nSDL_BeginGPURenderPass(long, long, int, long)`. Calling the friendly Java name with
four C-shaped arguments therefore cannot resolve through reflection.

`MetalInterop.beginGpuRenderPass(...)` now owns this generated-binding difference. It prefers
the safe Java overload (letting LWJGL derive the color count from the Buffer) and falls back to
the raw `nSDL_` entry point with native addresses only when necessary.

Do not call `SDL_BeginGPURenderPass` reflectively with an explicit count. More generally, when
an LWJGL generated overload accepts a `Struct.Buffer`, check whether LWJGL has elided the
corresponding C count parameter before mirroring the C signature in reflection code.
