# Native Accelerator

**Project:** https://github.com/Asbestosstar/nativeacceleratorsmc  
**License:** The Unlicense  

**Native Accelerator** is an experimental performance engine for Minecraft that combines architecture-specific native compute kernels with an optional high-throughput Vulkan renderer.

The project is designed around a simple idea: do not move Java code to native code merely for the sake of being native. Move **large, repetitive, data-oriented workloads** across the Java/native boundary in batches, keep stable rendering data resident, and use the CPU and GPU features available on the machine.

The current development branch targets **Minecraft 26.3-pre-2 / Java 25** and uses the Java Foreign Function & Memory API (Project Panama). The native ABI is kept independent from Minecraft and from the mod loader so older Java branches can later use JNI without requiring a second native engine.

> **Status:** early development. The compute kernels and renderer foundation are real and testable, but the Vulkan renderer does **not yet own Minecraft's final surface/swapchain/presentation path**. Do not interpret the current codebase as a finished FPS replacement renderer.

---

## Goals

Native Accelerator is intended to improve more than one performance metric:

- **FPS and frame-time consistency** through a Vulkan rendering path designed around persistent data, batching, indirect draws, and GPU-side visibility work.
- **Chunk generation and loading throughput** through native worldgen, packed-storage, meshing, compression, and data-conversion kernels.
- **Render-thread throughput** by replacing large numbers of fine-grained Java operations with compact batched work submissions.
- **CPU scalability** through native worker jobs operating on contiguous snapshots instead of callbacks into Minecraft objects.
- **Architecture-specific performance** on AMD64, SPARC, PowerPC, IA-64, ARM64 and other targets as implementations become available.
- **Broad operating-system support** without assuming that a rendering API or optimization belongs to only one OS family.

The project deliberately separates CPU architecture, operating system, Minecraft integration, native compute, and rendering backend code so unusual systems do not have to be bolted on later.

---

## High-level architecture

```text
                         Minecraft
                             │
              compact snapshots / integration hooks
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
     Native compute engine          Vulkan renderer engine
     libnativeaccelerator           libnativeaccelerator_renderer_vulkan
              │                             │
     ┌────────┼────────┐          ┌─────────┼─────────────┐
     │        │        │          │         │             │
 packed    image     noise     scene DB   native       GPU scene
 storage   kernels    grids                meshing      + culling
     │        │        │          │         │             │
     └────────┴────────┘          └─────────┴──────┬──────┘
              │                                    │
              ▼                                    ▼
      architecture dispatch               Vulkan indirect draws

 AMD64: AVX2 / AVX-512
 SPARC: VIS family planned
 Power: AltiVec / VSX planned
 others: scalar / architecture-specific backends
```

The native compute engine remains usable even when the Vulkan renderer is disabled or unavailable.

---

## Current compute kernels

The current native ABI contains four first-wave Minecraft-oriented acceleration families.

### Packed block storage

Native bulk operations exist for two different Minecraft layouts:

- runtime `SimpleBitStorage`-style packed values; and
- dense DataFixer `PackedBitStorage`-style values that may cross 64-bit word boundaries.

Operations include pack, unpack, and repack paths. These are kept separate because treating the two layouts as the same representation would produce incorrect data.

### Quad processing

Native mesh helpers provide:

- quad centroid extraction from interleaved vertex buffers;
- distance-key generation;
- stable far-to-near ordering; and
- direct six-index-per-quad output.

This is intended to collapse several Java passes over mesh data into one large native operation.

### NativeImage bulk operations

Implemented primitives include:

- ARGB/ABGR channel swizzling;
- rectangle fill; and
- rectangle copy.

Minecraft's image storage already lives in native memory, making this a particularly good place for zero-copy SIMD implementations.

### World-generation noise

The native layer includes:

- Minecraft-compatible Perlin point batches; and
- additive regular-grid density evaluation.

The intended optimization model is **hundreds or thousands of samples per native call**, never one Panama call for every noise sample.

See [`docs/KERNEL_ROADMAP.md`](docs/KERNEL_ROADMAP.md) for the kernel roadmap.

---

# Independent Vulkan renderer

Native Accelerator also contains a separate optional Vulkan renderer subsystem.

It is designed for **throughput**, not as a thin translation of thousands of Java rendering calls into Vulkan calls.

The desired data flow is:

```text
Minecraft section state
        │
        ▼
compact primitive snapshot
        │
        ▼
native worker jobs
        │
        ├── palette/state unpacking
        ├── broad-phase hidden-face elimination
        ├── material classification
        ├── mesh generation
        └── packed vertex/index production
        │
        ▼
persistent GPU arenas
        │
        ▼
GPU section database
        │
        ▼
compute visibility/culling
        │
        ▼
indirect draw-command buffer
        │
        ▼
Vulkan rendering
```

## Renderer pieces already implemented

The renderer companion library currently contains:

- dynamic Vulkan loader discovery;
- Vulkan API-version probing;
- minimal Vulkan instance creation;
- physical-device enumeration;
- persistent native section-scene storage;
- hash-based section lookup/update/removal;
- CPU AABB/frustum and distance culling;
- material-mask filtering;
- Vulkan-layout indexed-indirect command generation;
- GPU-friendly structure-of-arrays scene export;
- a coalescing GPU-buffer suballocator;
- 16×16×16 voxel visible-face-mask generation;
- neighboring-section boundary planes for hidden-face elimination;
- native POSIX worker batching;
- pipeline-cache identity/blob helpers; and
- an original compute shader for section visibility and indirect-command generation.

The renderer is intentionally a companion library rather than part of the mandatory compute ABI:

```text
libnativeaccelerator
libnativeaccelerator_renderer_vulkan
```

A system can therefore use the compute accelerator without Vulkan.

## Runtime Vulkan policy

There is **no operating-system allow-list**.

The renderer asks the platform whether a Vulkan loader can be opened and whether an actual physical device can be enumerated. If those checks succeed, that OS/architecture combination is eligible for the Vulkan path. Surface/window-system differences are isolated later at the platform boundary rather than leaking into scene, meshing, culling, or arena code.

This is important to the project because Vulkan-capable Unix systems outside the usual desktop Linux/Windows combinations should not be rejected merely because of their OS name.

## Persistent scene and GPU arenas

The renderer is designed to avoid allocating one Vulkan object or GPU allocation per chunk section.

Long-lived buffers are intended to look roughly like:

```text
Terrain vertex arena
┌────────┬────────┬──────┬────────┬────────────┐
│ Sec A  │ Sec B  │ free │ Sec F  │ Sec G      │
└────────┴────────┴──────┴────────┴────────────┘

Terrain index arena
┌──────────────────────────────────────────────┐
│ persistent suballocated index ranges         │
└──────────────────────────────────────────────┘

Instance / scene metadata
┌──────────────────────────────────────────────┐
│ bounds, offsets, counts, material masks ...  │
└──────────────────────────────────────────────┘
```

When one section changes, only its affected ranges should be rebuilt and uploaded. Stable geometry remains resident.

## GPU-driven visibility

The scene exporter produces compact arrays containing section bounds and draw metadata. The included compute stage is designed to filter those records by visibility and emit indexed-indirect draw records.

The long-term goal is to reduce the amount of per-frame Java/render-thread work from approximately:

```text
inspect object → decide draw → bind state → issue draw → repeat
```

toward:

```text
update changed scene records
        ↓
GPU visibility pass
        ↓
compact indirect command stream
        ↓
batched terrain submission
```

A CPU visibility path remains useful for validation, fallback, debugging, and hardware where a particular GPU-driven feature is unavailable.

See [`docs/VULKAN_RENDERER_ARCHITECTURE.md`](docs/VULKAN_RENDERER_ARCHITECTURE.md).

---

## Will it actually be faster?

**The design has a strong chance of being substantially faster in the workloads it targets, but the project does not yet have the end-to-end benchmarks required to claim an FPS multiplier.**

The largest expected gains are not from “C instead of Java.” They come from doing less work and doing it at a larger granularity.

| Area | Why it can improve | Expected importance |
|---|---|---|
| Terrain submission | Fewer per-section/per-draw operations and indirect batching | Very high |
| Visibility | Compact scene data and GPU/parallel culling | Very high at large render distances |
| Chunk meshing | Whole-section native jobs rather than fine-grained vertex writes | Very high during movement/loading |
| GPU memory | Persistent arenas and dirty-range updates | High |
| Quad processing | Fewer passes and SIMD-friendly contiguous memory | Medium-high |
| Packed storage | Bulk pack/unpack/repack | High for chunk/data workloads |
| Worldgen noise | Batched SIMD sampling | Potentially very high for generation throughput |
| Image operations | Zero-copy bulk pixel kernels | Moderate; workload dependent |

### Where the renderer should help most

The architecture is most likely to show a large improvement when the game is **CPU/render-thread limited**, for example:

- high render distance;
- many visible chunk sections;
- rapid movement causing section rebuild/upload pressure;
- CPUs with strong SIMD but relatively weak Java/JIT performance;
- systems where draw-call and state-management overhead dominate; or
- unusual architectures where explicit native kernels can outperform the available JVM code generation.

### Where it may not improve FPS much

A renderer rewrite cannot magically remove every bottleneck. Gains may be small when:

- the GPU is already fully saturated by shaders, resolution, or fill rate;
- a resource pack or shader workload dominates GPU time;
- entity/game simulation rather than rendering dominates the frame;
- native staging/copy costs exceed the work saved; or
- the new renderer has not yet replaced the relevant Minecraft path.

The project therefore treats **benchmarking as part of the implementation**, not as a marketing step after it.

Useful measurements will include:

- average FPS;
- 1% and 0.1% frame times;
- render-thread CPU time;
- GPU frame time;
- visible sections processed per frame;
- chunk sections meshed per second;
- bytes uploaded per second;
- number of Vulkan draw calls/indirect commands;
- worldgen chunks per second; and
- Java/native crossing count per frame.

---

## Current development status

### Implemented and testable

- Java 25 Panama bridge.
- Stable native compute ABI.
- Multi-loader bootstrap metadata.
- Conditional Mixin configuration plugin.
- ASM `ClassNode` pre/post hooks.
- AMD64 scalar/AVX feature dispatch foundation.
- Explicit SPARC, PPC32, IA-64 and generic architecture backends.
- POSIX/Linux/Solaris/BSD/macOS/Windows OS separation.
- Endianness helpers and resource-ID normalization.
- Four first-wave compute kernel families.
- Native kernel test suite.
- Vulkan companion library.
- Vulkan loader/device probe.
- Native scene database.
- Native culling and indirect-record generation.
- Persistent arena allocator.
- Native voxel face-mask batches.
- Renderer test suite.
- Java/Panama renderer wrapper classes.

### Foundation present, Minecraft replacement not complete

- RenderPearl integration mapping.
- Staged terrain replacement architecture.
- Persistent terrain residency design.
- GPU-scene export.
- compute visibility shader.
- pipeline-cache persistence helpers.

### Still required for the first real rendered frame

- platform Vulkan surface creation from Minecraft/SDL window state;
- queue-family and physical-device selection policy;
- logical device creation;
- swapchain creation/recreation;
- command pools/buffers and synchronization;
- actual Vulkan vertex/index buffers backed by the persistent arenas;
- descriptor/pipeline creation for Minecraft terrain;
- terrain texture/material binding;
- section snapshot extraction from Minecraft;
- final render-pass integration;
- indirect terrain submission; and
- presentation.

After that milestone, performance work becomes empirical: profile, identify remaining high-cost paths, then move or redesign only the paths that produce measurable gains.

---

## Multi-loader layout

One JAR contains thin entrypoints for:

- Fabric;
- Forge;
- NeoForge; and
- FeatureCreep.

All of them delegate to the loader-neutral:

```java
NativeAccelerator.initialize();
```

The native engine and renderer do not depend on a particular loader.

---

## Project layout

```text
native-accelerator/
├── AGENTS.MD
├── README.md
├── docs/
│   ├── CLEAN_ROOM_RENDERER.md
│   ├── KERNEL_ROADMAP.md
│   ├── PLATFORM_MATRIX.md
│   ├── PORTING_RULES.md
│   └── VULKAN_RENDERER_ARCHITECTURE.md
├── native/
│   ├── include/
│   ├── src/
│   │   ├── common/
│   │   ├── kernels/
│   │   ├── arch/
│   │   ├── os/
│   │   └── renderer/
│   │       ├── common/
│   │       ├── jobs/
│   │       └── vulkan/
│   └── tests/
├── scripts/
│   ├── build-native.sh
│   └── test-native.sh
├── src/main/java/com/asbestosstar/nativeaccelerator/
│   ├── integration/
│   ├── kernels/
│   ├── mixinconfig/
│   ├── nativeapi/
│   ├── platform/
│   ├── renderer/
│   └── loader entrypoints
└── src/main/resources/
    ├── loader metadata
    ├── nativeaccelerator.mixins.json
    └── nativeaccelerator/shaders/vulkan/
```

---

## Native source composition

Operating system and CPU architecture are independent dimensions:

```text
native/src/
├── common/
├── kernels/
├── arch/
│   ├── amd64/
│   ├── sparc/
│   ├── ppc32/
│   ├── ia64/
│   └── generic/
└── os/
    ├── posix/
    ├── linux/
    ├── bsd/
    ├── freebsd/
    ├── netbsd/
    ├── openbsd/
    ├── solaris/
    ├── macos/
    ├── windows/
    └── generic/
```

Examples of composition:

```text
Linux AMD64      = POSIX + Linux   + AMD64
Solaris SPARC    = POSIX + Solaris + SPARC
NetBSD SPARC     = POSIX + BSD + NetBSD + SPARC
Windows AMD64    = Windows + AMD64
```

DAX belongs to the Solaris/illumos OS layer. VIS belongs to the SPARC architecture layer. They are separate capabilities.

See [`docs/PORTING_RULES.md`](docs/PORTING_RULES.md) and [`docs/PLATFORM_MATRIX.md`](docs/PLATFORM_MATRIX.md).

---

## Endianness

Endianness is treated explicitly because several target CPU families are bi-endian.

The project provides:

- host byte-order detection;
- 16/32/64-bit byte swaps;
- bulk byte swaps;
- LE/BE-to-host conversion helpers; and
- endian-aware native resource naming where the architecture genuinely needs it.

Fixed-endian architecture identifiers do not receive redundant endian suffixes.

PowerPC64/PPC64LE is intended to use one shared architecture source tree compiled into separate endian/ABI binaries rather than two unrelated ports.

---

## Panama

The 26.3 branch uses Java 25's `java.lang.foreign` API.

Launch with native access enabled:

```text
--enable-native-access=ALL-UNNAMED
```

Modern code does not use JNA. Older Java/Minecraft branches can eventually implement a JNI adapter over the same C ABI.

---

## Conditional Mixins and ASM access

`nativeaccelerator.mixins.json` uses `NativeAcceleratorMixinConfigPlugin`.

It supports:

- globally enabling/disabling Native Accelerator mixins;
- vetoing selected mixins at configuration time;
- runtime mixin-selection rules; and
- `ClassNode` access through Mixin `preApply` and `postApply` hooks.

Examples:

```text
-Dnativeaccelerator.mixins=false
-Dnativeaccelerator.mixins.disable=ExperimentalMixin,*DaxMixin
```

Class nodes should be inspected or modified only during the callback that owns them; they should not be retained as permanent application state.

---

## Vulkan renderer configuration

The Java renderer bootstrap supports:

```text
-Dnativeaccelerator.renderer.vulkan=auto
-Dnativeaccelerator.renderer.vulkan=off
-Dnativeaccelerator.renderer.vulkan=required
```

`auto` attempts the native Vulkan probe and falls back when the renderer cannot be used.

Worker count:

```text
-Dnativeaccelerator.renderer.workers=N
```

A value of zero selects the native/platform default.

A custom Vulkan-loader path/name can be supplied through:

```text
NATIVE_ACCELERATOR_VULKAN_LIBRARY
```

---

## Building

### Requirements

For the modern branch:

- Java 25;
- Maven;
- CMake;
- a C compiler suitable for the target platform; and
- Vulkan runtime/driver components when testing the optional renderer.

### Java/mod JAR only

```sh
mvn package
```

### Build and bundle native libraries

```sh
mvn -Dnative.build=true package
```

CMake determines the canonical architecture/endian resource identifier. Native libraries are packaged under:

```text
META-INF/native/<os>-<arch>/
```

For example:

```text
META-INF/native/linux-amd64/
    libnativeaccelerator.so
    libnativeaccelerator_renderer_vulkan.so
```

Library suffixes/names change appropriately on other operating systems.

The native build also embeds the C/header/assembly implementation sources in the compiled JAR for reference and porting, preserving their native-tree layout:

```text
META-INF/native-source/
    include/*.h
    src/**/*.c
    src/**/*.h
    src/**/*.s
    src/**/*.S
    tests/*.c
```

Native distribution builds are intentionally reference/debug friendly. `scripts/build-native.sh` configures CMake as `RelWithDebInfo`, enables normal symbol visibility, never invokes a stripping step, and packages the resulting unstripped native libraries. On platforms with suitable tooling it additionally emits companion debug artifacts under:

```text
META-INF/native-debug/<platform>/
```

Typical contents are:

```text
Linux / Solaris / BSD / compatible toolchains:
    libnativeaccelerator.so.debug
    libnativeaccelerator.so.nm.txt
    libnativeaccelerator_renderer_vulkan.so.debug
    libnativeaccelerator_renderer_vulkan.so.nm.txt

macOS:
    *.dSYM/
    *.nm.txt

MSVC Windows:
    *.pdb
    *.nm.txt when a compatible nm is available
```

The split debug artifact is **additional**: the copy under `META-INF/native/<platform>/` remains unstripped and retains its own debug/symbol information. If a target lacks `objcopy`, `dsymutil`, or equivalent tooling, the build warns but still keeps the full debug information in the packaged native binary.

These source and debug files are reference resources only; the runtime loads the compiled libraries from `META-INF/native/<platform>/`.

### Native tests

```sh
./scripts/test-native.sh
```

This configures a Release native build with testing enabled and runs the CTest suite.

---

## Platform philosophy

Native Accelerator is intentionally designed for both mainstream and unusual systems.

Current source trees explicitly account for combinations involving:

- AMD64;
- SPARCv9;
- PowerPC32;
- IA-64;
- Linux;
- Solaris/illumos;
- FreeBSD;
- NetBSD;
- OpenBSD;
- macOS;
- Windows; and
- generic fallback targets.

Additional architectures such as PPC64/PPC64LE and ARM64 are intended to fit the same composition model.

A platform appearing in the architecture does **not** mean every optimized kernel or renderer feature has already been implemented or tested there. See the platform matrix for the actual current status.

---

## Development and provenance policy

The Vulkan renderer is developed as an independent implementation from public API specifications, Minecraft behavior, profiling, testing, and the project's own design requirements.

Do not copy, translate, adapt, or intentionally imitate third-party Minecraft renderer source code, distinctive implementation structures, internal naming schemes, comments, constants, or private design organization.

The repository's detailed policy is in [`docs/CLEAN_ROOM_RENDERER.md`](docs/CLEAN_ROOM_RENDERER.md).

---

## Near-term roadmap

### Renderer milestone 1 — first independent terrain frame

1. Create Vulkan surface from the game window.
2. Select physical device and queue families.
3. Create device, swapchain, command infrastructure and synchronization.
4. Back persistent arenas with real Vulkan buffers.
5. Snapshot chunk sections into compact primitive data.
6. Generate/upload terrain meshes natively.
7. Create terrain pipelines/descriptors.
8. Render terrain from the persistent scene database.
9. Present through the Vulkan swapchain.

### Renderer milestone 2 — GPU-driven terrain

1. Upload scene bounds/draw metadata.
2. Run compute visibility.
3. Generate compact indirect commands.
4. Use indexed indirect submission where supported.
5. Maintain CPU visibility fallback.
6. Benchmark render-thread and GPU frame times.

### Compute milestone

- hand-written AVX2/AVX-512 versions of the highest-value kernels;
- SPARC VIS kernels;
- PowerPC AltiVec/VSX kernels;
- broader native chunk meshing;
- lighting experiments;
- faster compatible compression/checksum paths; and
- profiling-guided network/world-loading work.

---

## Project rule of thumb

Before moving a Minecraft operation into native code, ask:

1. Is it hot enough to matter?
2. Can the work be expressed over contiguous primitive/native data?
3. Can hundreds or thousands of operations cross the native boundary at once?
4. Can we avoid callbacks into Java while the native job is running?
5. Can architecture-specific SIMD or GPU execution make the batch materially cheaper?
6. Can we prove the improvement with a benchmark?

If the answer is mostly **no**, the code probably belongs in Java.

If the answer is mostly **yes**, it is a good Native Accelerator target.


## License

Native Accelerator is released under **The Unlicense**. The complete public-domain dedication and warranty disclaimer are in [`LICENSE`](LICENSE).

Project repository: https://github.com/Asbestosstar/nativeacceleratorsmc
