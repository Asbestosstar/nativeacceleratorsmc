# Independent Vulkan renderer architecture

Native Accelerator has two independent performance layers:

1. the portable compute/game-engine acceleration ABI (`libnativeaccelerator`); and
2. the optional Vulkan renderer (`libnativeaccelerator_renderer_vulkan`).

The Vulkan renderer is selected by runtime capability, not an operating-system allow-list. If a target
has a working Vulkan loader and physical device, it is eligible. If it does not, the ordinary game
renderer remains available while the compute accelerator can still operate.

## Design goals

The renderer is designed around throughput rather than merely replacing individual Java arithmetic
operations with native calls:

- keep terrain and other stable geometry resident in long-lived GPU buffers;
- update only dirty ranges;
- maintain a compact native section scene database;
- batch section preparation rather than emitting one vertex attribute at a time from Java;
- export scene data in GPU-friendly structure-of-arrays buffers;
- perform section visibility in a compute stage;
- generate indexed indirect draw commands;
- minimize render-thread state changes and Java/native crossings;
- use native worker threads for bulk section preparation;
- persist driver pipeline-cache blobs under a correctly scoped identity;
- preserve a CPU culling/indirect path for validation and fallback.

## Implemented renderer-core pieces

The current renderer companion library implements:

- dynamic Vulkan loader discovery with `NATIVE_ACCELERATOR_VULKAN_LIBRARY` override;
- Vulkan API-version probing;
- minimal Vulkan instance creation and physical-device enumeration;
- a persistent native section database keyed by a 64-bit section id;
- AABB frustum and distance culling;
- output of the Vulkan `DrawIndexedIndirectCommand` five-field binary layout;
- GPU-scene export as bounds-min, bounds-max, and 20-byte draw-metadata arrays;
- a coalescing offset allocator intended for large persistent vertex/index arenas;
- 16x16x16 voxel visible-face masks including six neighboring boundary planes;
- multithreaded face-mask batches on POSIX targets;
- binary pipeline-cache read/write helpers and deterministic cache-key hashing;
- an original compute shader at `nativeaccelerator/shaders/vulkan/section_cull.comp` that performs
  frustum/distance/material culling and emits indirect commands.

These components are deliberately useful before the renderer owns presentation. They can be tested
against the ordinary renderer while the Vulkan device/swapchain and Minecraft integration are developed.

## GPU scene representation

The persistent native scene is authoritative for section residency. Before GPU culling, it can export:

```text
boundsMin[] : vec4 per section
boundsMax[] : vec4 per section
drawMeta[]  : 20 bytes per section
    indexCount
    firstIndex
    vertexOffset
    firstInstance
    materialMask
```

The compute culling shader consumes these buffers and produces a compact indirect-command buffer plus a
visible-count value. The Vulkan submission layer should use `vkCmdDrawIndexedIndirectCount` when the
selected device exposes the required core/extension functionality, otherwise use a compatible fallback.

## Persistent GPU arenas

Avoid one Vulkan allocation per section. Create a few large buffers and suballocate offsets:

```text
terrain vertex arena
terrain index arena
instance arena
transient/frame ring
```

`nar_arena_*` already provides the CPU-side free-list/coalescing allocator. The Vulkan device layer will
bind these offsets to actual `VkBuffer` storage. Rebuilding one section should allocate or reuse only its
ranges, upload them, update scene metadata, and retire old ranges after the GPU can no longer reference them.

## Native section preparation

The first native meshing primitive is a 4096-byte occupancy input to a 4096-byte six-bit face-mask output.
The section index is:

```text
x | (z << 4) | (y << 8)
```

For boundary blocks, six optional 16x16 neighboring occupancy planes suppress hidden faces. This lets the
caller batch broad-phase face elimination before material/model-specific quad generation.

The next meshing stages should be added behind this interface rather than exposing thousands of tiny
Panama calls:

1. unpack palette/state ids in bulk;
2. classify render material/model ids into compact native arrays;
3. build visible face masks;
4. compact visible faces;
5. generate packed terrain vertices/indices by material;
6. allocate persistent arena ranges;
7. upload dirty ranges and update scene records.

## Native worker model

`nar_context` owns a persistent worker group on POSIX targets. `nar_voxel_face_masks_batch` already uses it.
Future chunk-compilation work should submit whole sections or section batches to this same context instead
of creating threads for each rebuild.

Avoid callbacks into Minecraft objects from those workers. Snapshot the required primitive data in Java,
then let native workers operate on stable contiguous inputs.

## Minecraft 26.3 integration seams

`Minecraft263RendererTargets` records the current named integration points without linking the renderer
core to game classes. The important seams are the RenderPearl command encoder/render-pass backend, staged
vertex upload, mesh data, buffer construction, and render-system device selection.

Integration should happen in layers:

### Phase 1 — shadow/validation mode

Keep the ordinary renderer active. Feed section snapshots to the native scene/mesher, run CPU culling, and
compare counts/bounds/geometry against the normal path. No frame output changes.

### Phase 2 — native terrain residency

Use persistent Vulkan vertex/index arenas for terrain. Keep higher-level game state and resource/model
selection in Java. Replace fine-grained terrain buffer construction with batched native section output.

### Phase 3 — GPU-driven terrain visibility

Upload the exported scene arrays, run `section_cull.comp`, and feed the generated command buffer to Vulkan
indexed-indirect draws. Keep a CPU culling mode for validation and driver fallbacks.

### Phase 4 — broader batching

Add instance-oriented paths for repeated models, particles, and other compatible geometry. Game logic,
resource packs, model definitions, animation decisions, and mod-facing state remain Java-side; only compact
render data and GPU work move into the renderer.

## Pipeline cache identity

Persist a Vulkan pipeline cache only under an identity that includes at least:

- GPU vendor/device;
- driver version;
- shader hash;
- vertex layout;
- render state;
- Native Accelerator version.

Never reuse an opaque driver cache blob under a mismatched identity.

## Platform policy

Do not encode assumptions such as "Vulkan is only available on these operating systems." Probe the loader
and device at runtime. Platform-specific differences should be isolated to actual surface/window-system or
driver requirements, not to the scene database, meshing, arena allocator, culling logic, or indirect layout.

The renderer can therefore be built for Solaris/illumos, BSD families, Linux, Windows, macOS translation
layers, and other targets without changing the architecture merely because the OS name differs.
