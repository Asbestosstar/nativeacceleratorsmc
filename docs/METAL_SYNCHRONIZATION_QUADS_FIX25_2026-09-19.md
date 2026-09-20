# Metal synchronization and quad correctness — buildfix25

Fix24 proved that the major visible corruption is not caused by the uniform-packing compatibility
path: affected terrain shaders report `packing=none` and already fit SDL's native four uniform slots.

Fix25 addresses two independent correctness hazards.

## SDL resource cycling

The Metal backend previously allowed three frames in flight while every dynamic buffer upload and every
render-target attachment set SDL's `cycle` flag to false. SDL GPU's cycling model exists so an application
can overwrite a logical resource without clobbering a physical allocation that older pending work still
references.

Buffer writes are mirrored into `MetalGpuBuffer`'s CPU shadow, so fix25 uses a conservative rule:
when any GPU-readable buffer becomes dirty, upload its complete authoritative shadow to offset zero with
`cycle=true`. Full upload is intentional: cycling may select a fresh physical buffer and a partial upload
would leave the untouched range undefined.

Color/depth attachments are cycled only when their load operation clears prior contents. LOAD attachments
remain non-cycled because their previous pixels/depth values are semantically required.

Full-subresource texture uploads/copies use cycling; partial texture writes do not, because a newly cycled
texture would not contain the untouched texels. MacFamily1 defaults to one frame in flight for correctness
validation. The user can still override `nativeaccelerator.renderer.metal.framesInFlight`.

## Non-indexed QUADS

SDL GPU has no quad primitive. Minecraft normally supplies a sequential index buffer for quad rendering,
but a backend must still handle any direct non-indexed QUADS draw correctly. Fix25 adds a prebuilt 32-bit
quad index buffer using Minecraft's established order:

`0,1,2, 2,3,0`

and repeats it per group of four vertices. Non-indexed direct/indirect QUADS are translated to indexed
triangle-list draws. Already-indexed QUADS remain untouched, because their index buffer is already
triangulated by RenderPearl/Minecraft.

Startup marker:
`[Native Accelerator] Build marker: metal-sync-quad-fix25`
