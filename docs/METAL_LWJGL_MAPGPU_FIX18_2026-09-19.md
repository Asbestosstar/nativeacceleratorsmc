# Metal LWJGL transfer-buffer mapping fix 18 — 2026-09-19

## Failure observed

The NVIDIA/MacFamily1 compatibility path completed successfully on the test Mac:

- NVIDIA GeForce GT 650M classified as `MAC1_COMPAT`
- Mac2 and native graphics ICB disabled
- Metal validation disabled for the legacy/patched path
- SDL3 Metal device created
- graphics storage, texture arrays, fences and anisotropy runtime probes passed

Startup then failed while uploading the first dynamic texture because Native Accelerator reflected:

```text
SDL_MapGPUTransferBuffer(Long, Long, Boolean)
```

LWJGL's generated SDL binding does not expose that three-argument signature under the safe Java method name.

## Binding rule

The SDL C function has three arguments:

```c
void *SDL_MapGPUTransferBuffer(device, transfer_buffer, cycle);
```

Current LWJGL SDL bindings expose two Java shapes:

```text
SDL_MapGPUTransferBuffer(long device, long transfer, boolean cycle, long buffer_size)
    -> ByteBuffer

nSDL_MapGPUTransferBuffer(long device, long transfer, boolean cycle)
    -> long native address
```

The extra `buffer_size` belongs to LWJGL's safe ByteBuffer wrapper; it is not an SDL ABI argument.

## Fix

`MetalInterop.mapGpuTransferBuffer(...)` now owns this generated-binding difference.

1. Prefer the size-aware Java `SDL_MapGPUTransferBuffer(..., buffer_size)` overload.
2. Return its `ByteBuffer` directly.
3. If a generated LWJGL snapshot lacks the safe overload, fall back to
   `nSDL_MapGPUTransferBuffer(...)` and wrap the pointer with `MemoryUtil.memByteBuffer`.
4. All transfer upload/download paths call this helper. No renderer code reflects the nonexistent
   three-argument safe method anymore.

The fallback is selected only when method resolution fails. A real invocation failure from an
existing safe overload is not hidden by silently retrying another binding.

## Regression checks

A small isolated compile/runtime check was run against both generated shapes:

```text
safe 4-argument ByteBuffer binding: PASS
raw 3-argument nSDL fallback: PASS
```
