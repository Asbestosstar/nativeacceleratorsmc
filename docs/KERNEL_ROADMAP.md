# First-wave native kernels for Minecraft 26.3-pre-2

This document maps the first performance targets to the native ABI. The C kernels are implemented now;
Minecraft-specific interception remains a separate layer so the universal Fabric/Forge/NeoForge/
FeatureCreep JAR does not hard-link to one loader's mapping environment.

## 1. Packed bit storage

Two layouts exist and must not be confused:

- `net.minecraft.util.SimpleBitStorage` is the runtime layout. Each 64-bit cell holds
  `floor(64 / bits)` complete values. Values never cross a long boundary.
- `net.minecraft.util.datafix.fixes.PackedBitStorage` is a dense DataFixer layout. Values are a
  continuous bit stream and can cross a long boundary.

Native ABI:

- `na_simple_bits_unpack_u32`
- `na_simple_bits_pack_u32`
- `na_simple_bits_repack`
- `na_packed_bits_unpack_u32`
- `na_packed_bits_pack_u32`
- `na_packed_bits_repack`

The Java `NativeKernelBridge` has staging helpers for heap `long[]`/`int[]`. Benchmark before replacing
small Java operations: the runtime storage is heap-backed, so a normal Panama call needs staging copies.
Do not mark these potentially non-trivial loops as `Linker.Option.critical(true)` just to expose the heap.

## 2. MeshData quad centroids and distance sorting

26.3-pre-2 computes a center for every quad from vertices 0 and 2, then sorts quad IDs from farthest to
nearest and writes six indices per quad.

Native ABI:

- `na_decode_quad_centroids`
- `na_sort_quad_indices_distance`
- `na_sort_quads_distance_write_indices`

The combined call is the preferred target because `ByteBufferBuilder` data is already native/direct,
allowing a zero-copy Panama transition.

## 3. NativeImage bulk pixels

`NativeImage` already owns native memory, making it a strong zero-copy target.

Native ABI:

- `na_swizzle_argb_abgr_u32`
- `na_image_fill_u32_rect`
- `na_image_copy_u32_rect`

`NativeKernelBridge.fillNativeImage` and `copyNativeImageRect` accept the native pixel address exposed by
Minecraft's `NativeImage.getPointer()` and create a bounded MemorySegment view.

## 4. Worldgen Perlin volume evaluation

26.3-pre-2's `PerlinNoise.addToVolume` walks a regular `DensityVolume` in Z -> X -> Y order. The native
kernel preserves that layout and Minecraft's 16-entry gradient table, permutation lookup, smoothstep,
and far-coordinate wrapping behavior.

Native ABI:

- `na_perlin3_batch`
- `na_perlin3_volume_add`

The volume kernel is intentionally additive so it can match `DensityBuffer.addTo` and be composed for
`NoiseStack` layers. `PerlinNoiseMixin` now routes large `addToVolume` calls through the existing ABI using
`NoiseAcceleration` as the heap-staging size gate; small calls and any failed/unsupported native path fall
back to vanilla Java. The current implementation is scalar C compiled with the target compiler's optimizer;
hand-written AVX/VIS/VSX versions can replace the inner kernel behind the same ABI.

## Mapping / Mixin policy

Concrete named-source 26.3 Mixins are supported. Keep each Mixin as a thin interception seam and put the
loader-neutral eligibility/dispatch policy in ordinary Java classes; a missing capability or failed native
operation must always fall back to vanilla behavior.

For direct-memory targets, prefer one large native call over per-element Panama calls. For heap-backed
targets, benchmark staging-copy cost and keep a tunable size gate around the measured crossover.

### Mixin selection gate (already in place)

`nativeaccelerator.mixins.json` declares the common/client/server Mixins and the
`NativeAcceleratorMixinConfigPlugin`. `SystemMixinGate` registers the built-in rules from the plugin
`onLoad`, so every interception remains suppressible by the project-wide and per-Mixin switches.

A mixin package is its own gate. Put a new mixin in the matching package and it is filtered
automatically:

- `...mixin.client.*` - applied only where a Minecraft client is present; skipped on a dedicated server.
- `...mixin.server.*` - applied only on a dedicated server; skipped on a client.
- `...mixin.renderer.*` - GPU hooks; skipped when `-Dnativeaccelerator.mixins.renderer=false` or when
  `-Dnativeaccelerator.renderer.platform.role=server`.

Switches, all early-startup safe (system properties and non-initializing class lookups only):

- `-Dnativeaccelerator.mixins=false` - disable this whole mixin config.
- `-Dnativeaccelerator.mixins.disable=Name,*Pattern` - suppress by simple name, FQN, or wildcard.
- `-Dnativeaccelerator.mixins.renderer=false` - hard-disable the renderer group.
- `-Dnativeaccelerator.mixins.environment=client|server` - force the environment assumption.
- `-Dnativeaccelerator.renderer.platform.role=client|server` - the project-wide renderer role.

Precedence: global off > explicit enable/disable in code > `mixins.disable` patterns > registered
rules (in order) > apply. A rule that throws is logged and skipped rather than aborting startup.

Registration API for code that must decide before mixins are selected:
`NativeAcceleratorMixinConfigPlugin.registerRule(...)`, `registerClassNodeHook(...)`,
`enableMixin(...)`, `disableMixin(...)`, `clearMixinDecision(...)`.

`src/test/java/.../SystemMixinGateTest.java` is a plain-main harness (no JUnit dependency) that
exercises every switch and the precedence order; run it with `./run-mixin-gate-test.sh`. There is
still no remapping strategy, so per the policy above no concrete mixin is registered yet.

