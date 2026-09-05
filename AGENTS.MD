# Native Accelerator — Agent / Architecture Notes

This file records architectural decisions that future contributors and coding agents should preserve.
The project is intentionally designed around unusual CPUs, unusual operating systems, multiple native
ABIs, and a single Minecraft mod JAR that can expose thin entrypoints for several loaders.

## Core design rules

1. **Keep Minecraft/loader code separate from the native acceleration engine.**
   Fabric, Forge, NeoForge, and FeatureCreep entrypoints should remain thin and converge on the same
   loader-neutral initialization path.
2. **Keep operating-system code separate from CPU-architecture code.**
   A platform is composed from an OS backend plus an architecture backend. Do not create a separate
   copy of the same CPU implementation for every OS.
3. **POSIX is a shared OS layer, not an alias for Linux.**
   Linux, macOS/Darwin, BSDs, Solaris/illumos, and other sufficiently POSIX-like ports should reuse
   common POSIX facilities where practical, then add only their OS-specific deltas.
4. **Windows is a first-class non-POSIX backend.**
   Do not force Windows through POSIX abstractions merely to reduce file count.
5. **Rare architectures remain explicit.**
   IA-64, SPARC, PowerPC, and future unusual targets should have explicit architecture identities even
   while they temporarily use conservative scalar implementations.
6. **Do not infer byte order solely from an architecture name unless that architecture/ABI is defined
   to have only one byte order in this project.**
7. **Do not create separate architecture codebases merely because an architecture can run in either
   endian mode.** Prefer one architecture implementation with endian-aware code and separate native
   binaries/ABIs.

## Intended native source organization

The long-term source tree should follow this model:

```text
native/src/
├── common/
│   ├── native_accelerator.c
│   ├── platform_dispatch.c
│   └── endian.c
├── arch/
│   ├── amd64/
│   ├── ia64/
│   ├── sparc/
│   ├── ppc32/
│   ├── ppc64/
│   ├── arm64/
│   └── generic/
└── os/
    ├── posix/
    ├── linux/
    ├── bsd/
    ├── freebsd/
    ├── netbsd/
    ├── openbsd/
    ├── macos/
    ├── solaris/
    ├── windows/
    ├── aix/
    ├── hpux/
    ├── openvms/
    └── generic/
```

Not every directory has to contain optimized code immediately. It is acceptable for an explicit
backend to begin as a scalar implementation or placeholder, as long as its existence prevents the
project from assuming that AMD64/Linux is the only meaningful environment.

## POSIX composition

POSIX-capable systems should share repeated facilities in `os/posix` whenever the semantics really
are portable. Candidate shared functions include:

- `mmap` / `munmap` / `mprotect`
- page-size queries
- monotonic clocks
- aligned allocation where a portable implementation is appropriate
- dynamic loading (`dlopen`, `dlsym`, etc.)
- CPU-count helpers
- POSIX shared memory
- generic POSIX thread helpers

An OS-specific layer should contain only behavior that differs from the POSIX implementation or uses
an OS-specific facility.

Examples:

```text
Linux SPARC       = os/posix + os/linux   + arch/sparc
Solaris SPARC     = os/posix + os/solaris + arch/sparc
FreeBSD AMD64     = os/posix + os/bsd + os/freebsd + arch/amd64
NetBSD PPC32      = os/posix + os/bsd + os/netbsd  + arch/ppc32
macOS PPC32       = os/posix + os/macos + arch/ppc32
Windows AMD64     = os/windows + arch/amd64
```

Linux has good POSIX support and should lean heavily on `os/posix`. `os/linux` should be reserved for
actual Linux-specific functionality rather than copies of ordinary POSIX code.

## Endianness policy

Endianness is a common data-format concern and must not be implemented as scattered architecture
`#ifdef`s.

The shared native layer should provide or preserve primitives equivalent to:

```text
host endian detection
bswap16 / bswap32 / bswap64
bulk 16/32/64-bit swapping
little-endian -> host conversion
big-endian    -> host conversion
host -> little-endian conversion
host -> big-endian conversion
unaligned LE/BE reads and writes where required
```

Algorithms should express the byte order of the data they consume. For example, prefer a helper such
as `na_le32_to_host(value)` over code that says "swap when SPARC".

### Architecture capability vs current process byte order

Keep these as separate concepts:

- **Current process byte order**: LITTLE or BIG.
- **Architecture can vary byte order**: a capability/property of the architecture family or supported
  ABI set.

A PowerPC or SPARC build may therefore be described as both:

```text
architecture = PPC64
host endian  = LITTLE
endian-variable architecture = true
```

or:

```text
architecture = PPC64
host endian  = BIG
endian-variable architecture = true
```

## Native resource naming

Do **not** append `-le` / `-be` to architectures that are single-endian for the targets supported by
this project. Redundant endian suffixes add noise and create unnecessary aliases.

Examples that should normally remain unsuffixed:

```text
linux-amd64
windows-amd64
macos-amd64
linux-ia64
windows-ia64
hpux-ia64
```

For an endian-variable architecture, the native binary should be endian-qualified when the base
architecture identifier alone would otherwise be ambiguous:

```text
linux-ppc32-be
linux-ppc32-le
linux-ppc64-be
linux-ppc64-le
freebsd-ppc64-be
freebsd-ppc64-le
solaris-sparcv9-be
```

If an external platform/JVM/toolchain reports a conventional name whose endian is embedded in the
name, normalize it internally instead of treating it as a separate CPU architecture. For example:

```text
external name: ppc64le
internal architecture: PPC64
internal endian: LITTLE
```

Likewise, `ppc64` on a conventional big-endian Linux system can normalize to:

```text
internal architecture: PPC64
internal endian: BIG
```

The architecture model should therefore describe the CPU family; the build/output model describes
its byte order and ABI.

## PowerPC 32-bit

PowerPC 32-bit is a distinct architecture backend:

```text
arch/ppc32/
```

This is particularly important for the planned Java 8 / older-Minecraft macOS PowerPC port and G4
systems. The backend may later contain AltiVec-specific kernels. PPC32 can also be reused by BSD or
Linux ports instead of cloning the CPU code into each OS directory.

Possible composition:

```text
macOS PPC32   = POSIX + macOS/Darwin + PPC32/AltiVec
NetBSD PPC32  = POSIX + BSD + NetBSD + PPC32/AltiVec
Linux PPC32   = POSIX + Linux + PPC32/AltiVec
```

## PowerPC 64-bit: one architecture implementation, separate binaries

**Do not create independent `arch/ppc64` and `arch/ppc64le` implementations.**

PPC64 and PPC64LE are fundamentally the same PowerPC 64-bit architecture family for the purposes of
our optimization source tree. Most native kernels, AltiVec code, VSX code, feature detection, and
algorithm selection should be shared.

The preferred structure is approximately:

```text
arch/ppc64/
├── arch_ppc64.c
├── altivec.c
├── vsx.c
├── ppc64_endian.h
├── vsx_common.c        # if useful
├── vsx_be.c            # only when a kernel genuinely differs
└── vsx_le.c            # only when a kernel genuinely differs
```

The project should compile this shared PPC64 codebase into separate binaries for each target byte
order and ABI, for example:

```text
META-INF/native/linux-ppc64-be/libnativeaccelerator.so
META-INF/native/linux-ppc64-le/libnativeaccelerator.so
```

The same principle can extend to other operating systems:

```text
META-INF/native/aix-ppc64-be/...
META-INF/native/freebsd-ppc64-be/...
META-INF/native/freebsd-ppc64-le/...
```

### Why separate binaries are still required

Endian-aware source does **not** mean one binary can simply be reused between BE and LE systems.
Separate native builds are required because differences may include:

- object-file byte order
- compiler target
- linker target
- native ABI
- calling convention details
- function-pointer/symbol representation
- stack/layout conventions
- SIMD load/store and permutation semantics

On Linux specifically, traditional big-endian PPC64 commonly uses the ELFv1 ABI, whereas PPC64LE
commonly uses ELFv2. This is another reason that the output artifacts must be separate even when the
source implementation is almost entirely shared.

### Endian-sensitive PPC64 code

Most PPC64 code should stay common and use shared endian helpers. Where compile-time specialization is
useful, a pattern equivalent to this is acceptable:

```c
#if NA_BIG_ENDIAN
    /* endian-sensitive implementation */
#else
    /* little-endian implementation */
#endif
```

Do not duplicate an entire source tree merely for this distinction.

### AltiVec / VSX caveat

SIMD is the main area where byte order can require more than a scalar `bswap`.

AltiVec/VSX vector element numbering, byte permutation, shuffle masks, loads, stores, and lane
interpretation can make a BE implementation differ from its LE counterpart. If a kernel genuinely
needs different vector code, split **that kernel** into small BE/LE units while keeping the rest of
PPC64 shared:

```text
arch/ppc64/
├── vsx_common.c
├── vsx_be.c
└── vsx_le.c
```

This is an exception for localized SIMD behavior, not justification for separate PPC64 and PPC64LE
architecture ports.

## SPARC

SPARC architecture code owns CPU/VIS behavior:

```text
arch/sparc
    scalar SPARC
    VIS
    VIS2
    VIS3
```

Solaris/illumos-specific DAX support belongs to the OS backend rather than the SPARC architecture
backend:

```text
os/solaris
    DAX discovery
    DAX OS/library integration
```

This allows Solaris AMD64 and Solaris SPARC to share Solaris facilities while keeping SPARC vector
kernels reusable on Linux or NetBSD SPARC.

## IA-64 / Itanium

IA-64 is an explicit architecture target rather than an AMD64 or generic-x86 alias:

```text
arch/ia64/
```

It may initially use conservative scalar code. Keep the boundary explicit so future work can handle
EPIC-specific compiler behavior, ABI details, and operating-system differences (for example HP-UX,
Windows IA-64, or OpenVMS) without distorting the AMD64 backend.

IA-64 is not treated as an endian-variable resource target in this project; do not add redundant
`-le`/`-be` suffixes to its canonical resource ID unless a concrete supported target later requires a
change to this rule.

## AMD64

AMD64 is a single-endian target for this project and should use resource IDs such as:

```text
linux-amd64
windows-amd64
freebsd-amd64
macos-amd64
```

not:

```text
linux-amd64-le
```

Runtime CPU feature dispatch can still select scalar, AVX2, AVX-512, or future implementations from
within the same AMD64 binary where safe.

## Loader policy

The mod is intended to carry Fabric, Forge, NeoForge, and FeatureCreep metadata/entrypoints in a
single JAR. Loader integrations should remain deliberately shallow. If an older Forge/NeoForge API is
sufficient for the tiny entrypoint surface, using it as a compile-time API is acceptable rather than
forcing the native core to track every loader API revision.

All loader entrypoints should converge on the same core initialization rather than implementing
native probing separately.

## Panama and JNI

Modern Minecraft/Java branches use Project Panama / the Java Foreign Function & Memory API as the
primary native boundary.

The native C ABI itself must remain independent of Panama. Older Minecraft/Java 8 branches should be
able to add a JNI adapter that invokes the same native engine instead of forking the architecture and
OS implementations.

In other words:

```text
Minecraft + Java 25 -> Panama adapter -> stable Native Accelerator C ABI
Minecraft + Java 8  -> JNI adapter    -> stable Native Accelerator C ABI
```

## Guidance for adding a new target

When adding a port, identify these dimensions independently:

1. CPU architecture family
2. current byte order
3. whether that architecture family can vary byte order
4. operating system
5. POSIX availability
6. native ABI/toolchain
7. available SIMD/vector facilities
8. OS-specific accelerator facilities

Then compose the existing layers wherever possible.

For example, do **not** implement `linux_ppc64le.c` containing Linux calls, VSX code, endian swaps,
and loader logic all together. Instead compose:

```text
OS common:       os/posix
OS delta:        os/linux
Architecture:    arch/ppc64
Endian:          LITTLE
ABI/toolchain:   Linux PPC64 ELFv2 target
Vector backend:  shared VSX + LE specialization only where required
```

This separation is a project invariant and should be preserved as Native Accelerator grows.

## Mixin config plugin and ClassNode access

`nativeaccelerator.mixins.json` uses
`com.asbestosstar.nativeaccelerator.mixinconfig.NativeAcceleratorMixinConfigPlugin` as its Sponge
Mixin companion plugin. Keep this class outside the configured `com.asbestosstar.nativeaccelerator.mixin`
package because Mixin treats the configured mixin package specially during class loading.

The plugin has two jobs:

1. conditionally veto Native Accelerator mixins through `shouldApplyMixin`; and
2. expose the target ASM `ClassNode` supplied by Sponge through `preApply` and `postApply`.

Do not reference Minecraft classes from the config plugin, `MixinRule`, or early bootstrap conditions.
The plugin can be instantiated before ordinary mod initialization and while game classes are being
transformed.

Use `NativeAcceleratorMixinConfigPlugin.registerRule(...)` for early-safe conditional selection. A
rule returns `MixinDecision.DEFAULT`, `APPLY`, or `SKIP`. Use system properties or low-level platform
information when the decision must exist before loader entrypoints execute.

The whole Native Accelerator mixin set can be disabled with:

```text
-Dnativeaccelerator.mixins=false
```

Individual mixins can be suppressed with a comma-separated list. Simple names and `*` wildcard
patterns are supported:

```text
-Dnativeaccelerator.mixins.disable=ExperimentalMixin,*DaxMixin
```

Early bootstrap code can also call `disableMixin`, `enableMixin`, or `clearMixinDecision`, provided
that it does so before the relevant mixin is selected.

For bytecode work, implement `ClassNodeHook` and register it with
`registerClassNodeHook`. The hook receives the live target `ClassNode` immediately before and after a
Native Accelerator mixin application. It may inspect or deliberately modify that node when the
transformation requires it. Never cache or retain a supplied `ClassNode` after the callback returns;
the transformation pipeline owns the node and its lifetime.

A config plugin only controls its companion mixin configuration. Do not assume this plugin can veto
arbitrary mixins from unrelated mods or unrelated Mixin configs.

## First-wave performance kernels (ABI v3)

The first concrete Minecraft-oriented kernels are implemented under `native/src/kernels`:

- `packed_bits.c`: both runtime `SimpleBitStorage` (non-crossing values) and DataFixer
  `PackedBitStorage` (dense cross-long values);
- `quad.c`: MeshData centroid extraction, stable far-to-near distance sorting, and six-index quad output;
- `image.c`: numeric ARGB/ABGR red-blue swizzle plus NativeImage-style 32-bit fill/copy operations;
- `noise.c`: Minecraft-compatible Perlin point batches and additive DensityVolume traversal.

Do not merge the two bit-storage layouts. They have different binary representations even though both
store fixed-width integers in `long[]`.

Keep concrete Minecraft interception separate from the kernels. Named 26.3-pre-2 targets are recorded in
`Minecraft263AccelerationTargets`; do not hard-link the core native API to Minecraft classes. The universal
loader build still needs an explicit mapping/remap policy before concrete named-source Mixins are enabled.

For native/direct buffers, prefer zero-copy Panama calls. For heap arrays, ordinary downcalls cannot expose
movable heap storage. Staging is acceptable when benchmarked, but do not misuse critical downcalls for
large loops merely to avoid copies.


## Independent Vulkan renderer invariants

The Vulkan renderer is a separate native subsystem from the portable compute ABI. Preserve this split:

```text
libnativeaccelerator                    portable compute/game kernels
libnativeaccelerator_renderer_vulkan    optional Vulkan renderer
```

Do not gate Vulkan by operating-system name. Probe a loader and physical device at runtime. Solaris/illumos,
BSD, Linux, Windows, macOS translation layers, and future targets should all follow the same eligibility
rule; only actual surface/window-system deltas belong in platform-specific code.

The renderer architecture is throughput-oriented:

- persistent section scene database;
- large vertex/index/instance arenas rather than per-section GPU allocations;
- batched native section preparation;
- GPU-friendly scene export;
- compute visibility and indirect command generation;
- minimal Java/native crossings;
- CPU validation/fallback culling;
- persistent native workers;
- correctly namespaced pipeline caches.

Do not move Minecraft object graphs into native code. Snapshot compact primitive inputs, process them in
native workers, and return/upload compact render data. Game logic, resource/model definitions, and
mod-facing state remain Java-side.

The current native renderer ABI is defined in `native_accelerator_renderer.h`. `nar_scene_export_gpu` and
`nativeaccelerator/shaders/vulkan/section_cull.comp` define the first GPU-driven visibility data flow.
`Minecraft263RendererTargets` records named 26.3-pre-2 interception seams without introducing direct game
linkage into the renderer core.

## Renderer implementation provenance

The renderer must be independently designed from project requirements, Minecraft/RenderPearl compatibility
needs, public Vulkan/platform specifications, CPU/JVM documentation, and our own profiling/tests. Do not
copy, translate, adapt, or structurally imitate third-party Minecraft renderer source. Do not import its
distinctive package/class layout, internal names, comments, constants, or implementation-specific data
structures.

Keep third-party renderer source snapshots, copied snippets, and patches out of the repository. If the
origin of proposed renderer code is uncertain, write down the requirement and reimplement it independently.
See `docs/CLEAN_ROOM_RENDERER.md`.

## Native binary inspectability

Distribution/native-reference builds must remain inspectable. `scripts/build-native.sh` uses `RelWithDebInfo`, enables `NA_REFERENCE_SYMBOLS`, and must not strip the packaged native libraries. Where platform tooling permits, also package split debug information beneath `META-INF/native-debug/<platform>/` (`.debug` on ELF/toolchains supporting objcopy, `.dSYM` on macOS, and `.pdb` on MSVC Windows). Human-readable symbol inventories may also be bundled. The split files are supplemental; do not strip debug data from the runtime library merely because a companion debug artifact exists.

