# Metal LWJGL render-pass fix20

The 17:14 crash log still showed the buildfix18 call path:

`MetalInterop.sdlLong("SDL_BeginGPURenderPass", command, colors, count, depth)`

That cannot match LWJGL's friendly Java binding. Current LWJGL exposes:

`SDL_BeginGPURenderPass(long, SDL_GPUColorTargetInfo.Buffer, SDL_GPUDepthStencilTargetInfo)`

and the raw C-shaped binding separately as:

`nSDL_BeginGPURenderPass(long, long, int, long)`.

Buildfix20 changes `MetalCommandEncoder` to use the typed friendly overload directly, avoiding
reflection for this call entirely. It also prints:

`[Native Accelerator] Build marker: metal-renderpass-fix20`

near the beginning of startup so test logs prove that the rebuilt JAR came from this tree.

