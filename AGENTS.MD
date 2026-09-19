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

### One JAR, several loaders: duplicate-initialization guard

Because one JAR advertises Fabric, Forge, NeoForge, and FeatureCreep metadata, a host with more than one
loader installed can have more than one loader discover the same JAR and invoke its entrypoint, possibly
from different classloaders. Ordinary static state (including `NativeAccelerator.INITIALIZED`) is
per-classloader and cannot see the other copy, so it is not sufficient protection on its own.

`com.asbestosstar.nativeaccelerator.platform.ProcessInitializationGuard` provides the process-wide
run-once claim. It stores the claim in the JVM system property table, which every classloader shares,
under an atomic read-check-write, so the first loader entrypoint wins and every later one stands down
without repeating the native library load, the renderer probe, or any hook/benchmark/counter setup.

Rules to preserve:

1. Every loader entrypoint (`NativeAcceleratorFabric`, `NativeAcceleratorForge`,
   `NativeAcceleratorNeoForge`, `NativeAcceleratorFeatureCreep`) calls `NativeAccelerator.initialize()`
   and does nothing else. Never add per-loader initialization work that bypasses this path.
2. New run-once work goes through `ProcessInitializationGuard.claim(...)` with its own key rather than a
   fresh static `AtomicBoolean`, so it is protected across classloaders too.
3. `initialize()` must stay idempotent in both directions: a second call in the same classloader and a
   call from a second classloader must both be no-ops.
4. `run-duplicate-loader-test.sh` exercises this with two isolated classloaders and must keep passing.

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

The Java side of the runtime layout is `BitStorageAcceleration` (kernels package) plus the
`SimpleBitStorageMixin` seam, which injects at `HEAD` of `SimpleBitStorage.unpack(int[])` and cancels only
when the native path returns `true`. Keep that class Minecraft-free: it owns the eligibility checks (bit
width 1..32, non-null arrays, size gate) and nothing else, so it stays unit-testable without the game.

The size gate is `nativeaccelerator.bits.minElements` (default `DEFAULT_MIN_ELEMENTS = 2048`). It exists
because heap arrays must be staged through direct buffers before the downcall, so below the crossover the
plain Java loop wins. Measure it, do not assume it: `./run-bit-storage-harness.sh` runs the differential
check against the vanilla loop for every 1..32 bit width and prints a suggested gate. The value is host-
and backend-specific and the observed first-winning size is noisy (no width won below 512, but the same
width was seen first winning at 768 and at 2048 across runs), so the gate is deliberately kept at the top
of that band rather than tuned to a single measurement.

Keep concrete Minecraft interception in its own layer, but **Mixins are allowed and encouraged**.
Named 26.3-pre-2 targets are recorded in `Minecraft263AccelerationTargets`, and concrete named-source
Mixins may target Minecraft classes directly: the build compiles against the named 26.3 classes, so
`net.minecraft.*` targets are valid Mixin targets. Keeping the loader-neutral core (`NativeAccelerator`,
`SystemMixinGate`, the native API) free of direct Minecraft linkage is a layering rule for the universal
JAR, not a prohibition on Mixins. See **Mixins are allowed** below.

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


## Loader identity: one place, early-safe

`com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment` is the single place in the universal JAR
that knows mod-loader identities. Fabric, Forge, NeoForge, and FeatureCreep anchors are referenced only
as strings and resolved with a non-initializing `Class.forName(String, boolean, ClassLoader)`; no shared
code links against a loader API. That is what keeps the per-loader entrypoints trivial and removes any
need for per-loader mixin configuration.

This class must stay early-startup safe. The mixin config plugin runs before Minecraft or any loader
entrypoint is initialized, so nothing here may depend on initialized game state. Public surface:
`Loader` (FABRIC, FORGE, NEOFORGE, FEATURECREEP, UNKNOWN), `Loader.id()`, `present(String)`,
`current()`, `id()`, `gameDirectory()`, and `contextClassLoader()`.

Do not add loader knowledge elsewhere in shared code. If a new loader arrives, add its anchor class here
and to the entrypoint set; do not fork the initialization path.

## Renderer selection is evidence-based, never OS-based

`com.asbestosstar.nativeaccelerator.renderer.RendererPlatformPolicy` owns the client-renderer decision and
the single lazily loaded `RendererNativeApi` instance (reused by `NativeVulkanRenderer`). Two questions are
answered at runtime:

1. **Does this computer support Vulkan?** The renderer companion library opens a Vulkan loader and
   enumerates physical devices. A Mesa, vendor, or translation-layer build compiled without usable Vulkan
   support answers no even on an OS where Vulkan is common, and a machine that does provide Vulkan answers
   yes even when that is unusual for its OS.
2. **Which backend is Minecraft itself using?** The game own `options.txt` is parsed for the configured
   graphics backend. Native Accelerator has no OpenGL surface path, so a non-Vulkan backend means the client
   renderer rewrite has nothing to attach to.

`Role.SERVER_ONLY` means the evidence says the Vulkan client renderer gives no benefit on this machine. It
does **not** mean the operating system is unsupported. A server-only host still starts the client, still
plays normally, and keeps the full native compute accelerator; the renderer companion library still builds
and remains usable for headless/dedicated-server workloads. Preserve that distinction in code comments and
diagnostics.

Properties:

- `nativeaccelerator.renderer.platform.role` = `auto` (evidence), `client`, or `server`;
- `nativeaccelerator.renderer.minecraft.options` - explicit `options.txt` path for backend evidence;
- `nativeaccelerator.renderer.minecraft.gamedir` - game directory whose `options.txt` is used.

An explicit override is always honoured, so a user is never blocked by a wrong guess. Useful accessors:
`role()`, `clientEligible()`, `evidence()`, `describe()`, `vulkanDeviceDetected()`,
`minecraftOptionsDetected()`, `overridden()`, `setMinecraftGameDirectory(Path)`,
`setMinecraftOptionsPath(Path)`, and `adoptLoaderGameDirectory()`.

The native side mirrors this contract in `native/include/native_accelerator_renderer.h`:
`NAR_RENDERER_ROLE_CLIENT`/`SERVER_ONLY`/`AUTO`, the `NAR_RENDERER_EVIDENCE_*` bits, and
`nar_renderer_platform_role`, `nar_renderer_client_eligible`, `nar_renderer_platform_evidence`,
`nar_renderer_platform_name`, `nar_renderer_set_options_path`, `nar_renderer_options_path`, and
`nar_renderer_set_role_override`. Implementation lives in `native/src/renderer/common/platform_policy.c`.
Do not add an OS allow-list to either side.

## Mixins are allowed

Concrete Mixins are permitted in this project, including named-source Mixins that target Minecraft classes
such as `net.minecraft.util.SimpleBitStorage`, `net.minecraft.world.level.levelgen.synth.PerlinNoise`, and
`net.minecraft.server.MinecraftServer`.

What that means in practice:

1. **No remap-policy gate.** The earlier rule that a concrete Mixin could not be enabled until a deliberate
   mapping/remap strategy existed for every loader is withdrawn. The build compiles against the named 26.3
   classes, so Mixins may reference those names directly.
2. **Layering still matters.** Share loader-neutral logic in the core classes. A Mixin is an interception
   seam: it should do the minimum work (dispatch into a native kernel, size a pool, prewarm a cache) and hand
   off to code that does not link against Minecraft, so the same logic stays reusable and unit-testable.
3. **Config, not a new file.** Add new Mixins to `nativeaccelerator.mixins.json`. Entries go in `mixins`
   (common), `client`, or `server` as appropriate. The package of the Mixin class decides which gate
   applies, so no per-loader mixin config is required.
4. **Stay reversible.** Every interception must degrade to vanilla behaviour when the native backend is
   absent or a capability is missing, and must stay suppressible with the existing switches
   (`-Dnativeaccelerator.mixins=false`, `-Dnativeaccelerator.mixins.disable=...`, package gates).
5. **Document the seam.** When a Mixin is added, record the target class, the injected method, and the
   observable effect next to the existing target list in the integration package.

## Mixin gating by package convention

Native Accelerator ships one mixin configuration for every loader. Loader-specific mixins are not split
into per-loader configs; the companion `IMixinConfigPlugin` decides at class-transform time whether a mixin
applies. `com.asbestosstar.nativeaccelerator.mixinconfig.SystemMixinGate` supplies the default rules and
must only read signals that are safe that early: system properties and non-initializing class lookups. It
never references Minecraft classes by value, and it reuses `RendererPlatformPolicy.ROLE_PROPERTY` and
`LoaderEnvironment` rather than inventing parallel switches.

A mixin package decides which gate applies, so a new mixin is protected automatically:

```text
...mixin.client.*          applied only when a Minecraft client is present
...mixin.server.*          applied only on a dedicated server (no client classes)
...mixin.renderer.*        GPU/Vulkan client hooks; honour role and the renderer switch
...mixin.loader.<id>.*     applied only when that loader is running, e.g. loader.fabric or loader.neoforge
```

The `...mixin.loader.<loader>.*` form is the supported way to write a loader-specific mixin without a
per-loader mixin config. Properties: `nativeaccelerator.mixins.renderer=false` hard-disables the renderer
group, `nativeaccelerator.mixins.environment=client|server` forces the environment assumption,
`nativeaccelerator.mixins.loaders=fabric,neoforge` assumes exactly those loaders instead of probing, and
the project-wide `nativeaccelerator.renderer.platform.role` forces renderer mixins on or off. A renderer
mixin under `auto` role is deferred, not applied, until the evidence resolves.

## POSIX feature-test macros are named, not assumed

POSIX declarations (`setenv`, `unsetenv`, `dlopen`, `pthread_*`, `dirent`, `mkdir`, ...) are exposed only
once the matching feature-test macro is defined. Linux and macOS default builds happen to expose them, but
the strict toolchains used by the other Unix families (HP-UX `cc`, AIX `xlC`, Solaris Studio) and a strict
`-std=c11` do not. `native/CMakeLists.txt` therefore defines `_DARWIN_C_SOURCE` on Apple and
`_XOPEN_SOURCE=700` elsewhere on non-Windows hosts via `NA_POSIX_DEFINITIONS`. Keep the level we actually
depend on explicit rather than relying on a compiler default.

## Mesa tree selection on Unix-family hosts

`native/src/renderer/vulkan/mesa_select.c` answers one question: which single Mesa tree should the renderer
companion library load? A Unix-like machine often carries several trees at once (system, ports/pkgsrc,
third-party trees under `/opt`), and some are built without Vulkan. The auditable rule:

1. a tree is a candidate only when it really provides a Vulkan ICD: a manifest under
   `<prefix>/share/vulkan/icd.d` or `<prefix>/etc/vulkan/icd.d` whose referenced driver library exists;
2. among candidates the newest parsed version wins (`mesa-25.0.2` -> `25.0.2`);
3. a known version beats an unknown one;
4. ties fall back to owning its own Vulkan loader, then to discovery order.

Configuration: `NATIVE_ACCELERATOR_MESA_PREFIX` and `NATIVE_ACCELERATOR_MESA_SEARCH_PATH` (roots; a root
contributes itself plus immediate subdirectories), `NATIVE_ACCELERATOR_MESA_PIN=1` to force
`VK_DRIVER_FILES`/`VK_ICD_FILENAMES` from the selected tree, and `NATIVE_ACCELERATOR_MESA_NO_DEFAULTS` to
ignore platform defaults. This is not Solaris-only logic; the defaults, prefixes, and search roots are
configuration, so the selection is unit-tested on any POSIX host. Existing `VK_DRIVER_FILES` and
`VK_ICD_FILENAMES` values are never overwritten. The selected tree is reported by
`nar_vulkan_mesa_prefix()`/`nar_vulkan_mesa_loader()`; `nar_vulkan_mesa_apply_selection()` points the
loader at it and is called before the Vulkan probe opens a loader. Not thread-safe by design: this is
process-wide bootstrap state consulted once, before any worker thread exists.

## Regression harnesses

Keep these passing; they are the executable form of the rules above.

- `run-duplicate-loader-test.sh` - two isolated classloaders; first claim wins, the second loader entrypoint
  stands down without re-initializing (16 checks).
- `run-mixin-gate-test.sh` - client/server/renderer/loader gating, wildcard disables, explicit-enable
  precedence, and broken-rule isolation (22 checks).
- `run-startup-timer-test.sh` - `StartupTimer` semantics that the startup Mixins depend on: the first
  occurrence of a stage or milestone wins (so runs stay comparable), `finish(...)` reports exactly
  once, and invalid stage names never throw (29 checks). The run-once guard is what makes the
  server-ready probe safe on `MinecraftServer#runServer()` writing `isReady = true` every tick, and
  the harness asserts that specifically.
- `test-macos.sh` - builds, bundles the macOS native libraries as `META-INF/native/macos-amd64/`, installs
  the mod JAR, and launches the real client.

## Verified in-game (Minecraft 26.3, Fabric Loader 0.19.5, macOS amd64)

The universal JAR was loaded by a real client and the mod initialized inside the game process. Observed:

```text
Loading 5 mods: fabricloader 0.19.5, java 25, minecraft 26.3, nativeaccelerator 0.1.0-SNAPSHOT
[Native Accelerator] Platform: macos-amd64
[Native Accelerator] Panama backend: macos-posix-amd64-scalar
[Native Accelerator] Capabilities: [native, posix, little-endian, macos, packed-bits, quad-sort, image-kernels, noise-kernels]
renderer skipped (evidence): vulkan=loader-only minecraft=non-vulkan (server-only). The client runs normally;
the native compute accelerator is unaffected. Set -Dnativeaccelerator.renderer.platform.role=client to attempt
the client renderer anyway.
```

No mod exceptions were raised; the client kept running the full session. The only errors in that run were
offline-login artifacts (`401`/`SignedJWT`) caused by launching without real credentials, not by this mod.
## High-throughput model preparation pipeline (startup performance pass 2)

Minecraft 26.3 model preparation is replaced at the two existing interception seams rather than by patching
thousands of individual resource tasks. `ModelLoadBatcher` now dispatches both raw-model and blockstate work through
`ModelWorkScheduler`, a persistent work-stealing pool. The default worker count is the JVM's available processor count
and is overrideable with `nativeaccelerator.model.workers`. Keep one coarse external future per model family and use
ForkJoin leaves internally; do not regress to one `CompletableFuture` per model/blockstate.

Ordinary blockstate JSON is compiled directly by `FastBlockStateDecoder` into the final identity map. Preserve these
semantics: full selectors resolve through `StateHolder.setValue`; partial selectors enumerate only unconstrained
property combinations; multipart constructs one shared `MultiPartModel.Unbaked`; resource-stack order remains
pack-order significant; and unsupported/failed fast decoding retries the vanilla Codec/dispatcher path.

Ordinary cuboid model JSON is decoded directly by `FastCuboidModelDecoder` into `CuboidModel` and its final element,
face, texture and transform records. Any unsupported/failed fast decode must reopen the resource and retry
`CuboidModel.fromStream`. Compatibility is more important than forcing a fast-path hit.

Relevant switches (all receive the `nativeaccelerator.` system-property prefix):

- `model.batchLoading=true` - master replacement-pipeline switch;
- `model.fastBlockstateJson=true` - direct blockstate compiler;
- `model.fastCuboidJson=true` - direct CuboidModel parser;
- `model.dedicatedPool=true` - persistent work-stealing pool;
- `model.workers=0` - zero means `Runtime.availableProcessors()`;
- `model.forkJoinGrain=0` - adaptive range split;
- `model.profiler=true` - aggregate pipeline profiling;
- `model.ioProfiler=false` - expensive per-read profiling, diagnostic only;
- `model.parseCache=false` - old exact-content experiment remains off by default.

Do not add a native call per JSON document merely to claim native acceleration. The files are tiny and branch-heavy,
and their outputs are Minecraft Java objects. Native parsing is appropriate only as a bulk tokenizer/decoder with many
documents per crossing after profiling demonstrates a remaining crossover.


## Model reload throughput pass 3 (26.3)

The 26.3 client model pipeline now has a second layer of throughput work after the direct blockstate/raw-model decoders:

- `ClientItemLoadBatcher` replaces `ClientItemInfoLoader`'s future-per-item scheduling with `ModelWorkScheduler` and uses `FastClientItemDecoder` only for item-model shapes it can construct exactly. Unsupported shapes always reopen the resource and use the vanilla registry-aware codec.
- `ReloadResourceIndex` caches authoritative `ResourceManager` enumeration results for the duration of one reload. It never reconstructs pack priority/filter semantics itself.
- `ZipResourceIndex` keeps a lazy per-open-ZipFile manifest: one lightweight central-directory scan, then only requested directory prefixes are materialized. Central-directory order and directory entries are preserved.
- `SpriteLoaderThroughputMixin` replaces future-per-sprite-source scheduling with the shared work-stealing pool. Atlas stitching itself remains vanilla.
- `FastBlockStateDecoder` caches per-`StateDefinition` property/value state masks so partial selectors become bit-mask intersections rather than recursive state transitions and temporary selector maps.
- `ModelDiscoveryParentOrderMixin` reverses only the unresolved validation list before vanilla propagation. Discovery adds a parent after its child, so this makes the ordinary case parent-first without replacing vanilla cycle handling.
- `FastModelGroupCollector` preserves the 26.3 grouping rules while reducing identity-set and temporary allocation overhead.
- `ModelDagProfiler` records actual asynchronous wall-clock completion offsets. Do not confuse these with `ProfiledReloadInstance` preparation counters, which are accumulated task CPU and may overlap.

All pass-3 rewrites remain individually suppressible through the existing global mixin disable plus their feature properties. `model.verifyFastPaths=true` is an intentionally expensive validation mode: it reparses supported raw/item resources through vanilla and validates blockstate state coverage/dependency/equality-group partitions, falling back to vanilla on a mismatch. Do not enable it for throughput measurements.

## Model/resource throughput pass 5 — cold-cache safety

Pass 4 proved that persistent caches can help unchanged launches, but the first real cold-cache profile showed that synchronous write-through defeats the fast model decoders. Preserve these pass-5 rules:

- persistent cache **hits** may remain on startup's critical path;
- persistent cache **misses must not perform disk writes** on resource/model/sprite workers;
- decoded-texture/layout cache writes go through `DeferredCacheWriter` and are held until the first playable screen; later reloads hold them until reload completion;
- source-byte caching is off by default (`cache.resourceBytes=false`); if enabled, misses stream live through a tee and only stage deferred writes;
- the recursive `mods/` + `resourcepacks/` generation manifest remains mandatory before any persistent cache reuse;
- `ReloadResourceIndex` may reuse the authoritative ResourceManager result but must not eagerly clone one-shot first-touch results;
- do not restore the old eager all-prefix ZIP index. Pass 7 uses the lazy manifest form (`resource.zipIndex=true` by default): one entry/name array plus requested-prefix caches only;
- normal throughput runs keep `model.profiler=false` and `model.dagProfiler=false`; enable them only for diagnostic profiles;
- item fast decoding may grow only with exact parity. Pass 7 adds exact Transformation.EXTENDED_CODEC representations plus common condition/range/select property dispatchers; unsupported/context-heavy forms still fall back to vanilla.

The desired invariant is: an invalidated/new cache generation should behave approximately like the proven live fast-decoder path, while an unchanged generation may be faster. Never require a user to pay a large startup penalty merely because they added a mod/resource pack and correctly invalidated the cache.

## GUI atlas cache correctness rule

`minecraft:textures/atlas/gui.png` is a correctness exclusion from persistent decoded-texture and atlas-layout **replay** caching. GUI sprites carry scaling metadata and tiny UV/padding differences are visually obvious. `SpriteResourceLoaderCacheMixin` therefore keeps GUI PNG decoding on Minecraft's ordinary `SpriteResourceLoader`, and `SpriteLoaderParallelMipmapMixin` never restores a cached GUI placement.

The GUI atlas is still allowed to use `FastStitcher`: unlike cached-layout replay, `FastStitcher` computes a fresh placement from the current live sprite set on every reload and is deliberately placement-compatible with vanilla 26.3 (same padded holder sizes, sort order, first-fit child order, split order, power-of-two growth decision and gathered coordinates). Do not compensate for cache artifacts by changing vanilla widget/nine-slice rendering.

`nativeaccelerator.atlas.fastStitcher=false` is the rollback switch. `nativeaccelerator.atlas.verifyFastStitcher=true` is an expensive diagnostic mode that runs vanilla beside the fast implementation and falls back on any dimension/coordinate/padding mismatch. Keep GUI persistent placement replay disabled until an image/UV parity harness proves it safe.


## Model/resource throughput pass 7 — lazy manifests and dispatcher fast paths

Pass 7 follows the post-safety profile rather than reverting cache correctness:

- Model-discovery DAG and aggregate CPU timers use separate timestamps. Never subtract a `ModelDagProfiler.begin()` value unless DAG profiling is actually enabled; a disabled DAG profiler returns zero.
- `ReloadResourceIndex` reports its tiny memoization lookup separately from `resource.manager.list-resources` / `resource.manager.list-resource-stacks`. Do not label authoritative pack traversal as index overhead.
- ZIP-backed packs use a lazy per-open-ZipFile manifest. The first query copies each central-directory entry/name once; only requested prefixes are filtered/cached. Retain the weak manifest across reloads because it is keyed to the exact open ZipFile.
- Jar/non-default-filesystem `PathPackResources` use `PathResourceIndex`: one namespace scan, prebuilt `Identifier`s, then prefix filtering. Ordinary default-filesystem directory resource packs stay on Minecraft's live walker by default (`resource.pathManifest.defaultFs=false`) to avoid a cold whole-tree scan.
- `FastClientItemDecoder` supports common `condition`, `range_dispatch`, and `select` models plus exact Transformation.EXTENDED_CODEC object/matrix encodings. Any unsupported property/value/field still reopens and uses the vanilla registry codec.
- `FastBlockStateDecoder` accumulates variant output in dense StateDefinition index space and materializes the final IdentityHashMap once. Preserve vanilla overlap behavior: write the new root, then throw/stop the current variant if a state was already defined.
- Keep GUI decoded/layout replay exclusions and `FastStitcher` placement-parity rules from pass 6 unchanged.

Relevant resource switches:

- `resource.index=true` — reload-scoped authoritative query memoization;
- `resource.zipIndex=true` — lazy per-open-ZipFile manifest;
- `resource.pathManifest=true` — lazy manifest for jar/non-default-filesystem paths;
- `resource.pathManifest.defaultFs=false` — do not whole-tree index ordinary directory packs unless explicitly requested.


## Model/resource throughput pass 8 — scheduler isolation and CPU-time profiling

Pass 8 keeps the pass-7 lazy pack manifests, item decoder coverage, dense blockstate emission, safe texture cache semantics, and placement-compatible FastStitcher. The change is scheduling: atlas sprite-source and mipmap work no longer share `ModelWorkScheduler`'s CPU-wide pool. `AtlasWorkScheduler` uses a separate bounded, lower-priority daemon pool so atlas preparation can overlap without consuming every core needed by ModelManager.

Mipmap scheduling is pixel-weighted rather than sprite-count weighted. Small atlases run as one low-priority task; larger atlases are greedily balanced into a small number of bins by estimated mip texel work. This avoids treating a tiny 16x16 sprite as equal to a large texture and avoids the 64+ fine-grained mip leaves observed in the pass-7 regression.

Relevant switches (all with the `nativeaccelerator.` prefix):

- `atlas.dedicatedPool=true` — isolate atlas work from the model work-stealing pool;
- `atlas.workers=0` — automatic bounded atlas parallelism (`min(4, availableProcessors/4)`, at least one);
- `atlas.workerPriority=4` — lower OS/JVM priority than normal model workers;
- `atlas.dynamicChunkSize=2` — sprite-source pull chunk;
- `atlas.mipmapSerialPixels=393216` — estimated generated mip pixels below which one atlas task is used;
- `atlas.mipmapSerialSprites=24` — small-sprite-count serial threshold;
- `atlas.mipmapChunksPerWorker=2` — upper bin count multiplier for large atlases;
- `atlas.parallelMipmaps=true` — master optimized mip path, still independently A/B switchable.

`ModelPipelineProfiler` also reports `.thread-cpu` companion counters on supported JVMs. Elapsed aggregate time can include descheduling/CPU contention; current-thread CPU time does not. Use the pair to distinguish genuinely slower decoding from workers being preempted. Thread CPU accounting is not enabled at all when `model.profiler=false`.

Empty blockstate variant selectors now bypass the global selector-layout/syntax caches entirely and fill the dense state accumulator directly when there is no prior overlap. Preserve vanilla overlap semantics when an empty selector follows an earlier variant.

## Model/resource throughput pass 9 — topology-aware scheduling

Pass 9 replaces `Runtime.availableProcessors()`-only atlas heuristics with native OS topology detection. Do not add OSHI to this path. Preserve package/physical/logical/JVM-visible counts separately; JVM visibility is the hard process budget. Solaris/SPARC topology comes from `kstat cpu_info`, so hardware strands must never be treated as independent physical cores.

Automatic scheduler rules:
- ordinary SMT1/2: model pool may use JVM-visible logical CPUs; atlas pool uses effective physical cores capped at 8;
- SMT4+ / high-strand CPUs: model pool defaults to two strands per effective physical core; atlas pool uses a bounded physical-core subset capped at 16;
- explicit `model.workers` / `atlas.workers` remain authoritative;
- nominal clock may tune the mipmap serial/parallel threshold only on amd64/arm64. Never compare GHz across architectures to infer equivalent core performance.

Profiler topology/capacity must be emitted as last-value gauges, not summed operation counters.

## Pass 10 invariants
- Content-addressed blockstate caches store loader-neutral syntax/unbaked data only; never retain BlockState/StateDefinition/reload registries.
- Create a fresh blockstate UnbakedRoot on every bind so visual-equality/baking-key identity remains reload/resource scoped.
- Decoded texture cache hits must always return a newly owned NativeImage; never share mutable pixel storage.
- GUI, animation, texture-metadata, additional-metadata, and font-sensitive sprites stay outside decoded-pixel reuse unless separately parity-validated.
- CPU cache information is advisory/diagnostic. Do not derive worker counts from GHz/cache size alone; physical topology and JVM-visible CPUs remain authoritative.
---

## NativeServer0 reproducible benchmark contract

Use this contract for all Native Accelerator dedicated-server performance work.

- **Source distribution:** `~/Downloads/nativeserver0` (override only with `NATIVESERVER0_DIR`). It is read-only input: never add mods, worlds, EULA files, logs, or properties to it.
- **Minecraft/Fabric version:** 26.3 / Fabric Loader 0.19.5, requiring **Java 25 or newer**.
- **Fixed world seed:** `-4630982771278649347`. It is written by `scripts/setup-nativeserver0-benchmark.sh`; do not change it when comparing results.
- **Comparable settings:** creative mode, one local player maximum, online mode off, view and simulation distance 10, no RCON/query, identical `-Xms2G -Xmx2G` default JVM flags, and a freshly generated `benchmark-world` for every cold-start sample.
- **Isolation:** benchmark instances are disposable directories under `target/nativeserver0-benchmark`; results are written under `target/nativeserver0-results`. Neither directory is source-controlled.
- **Paired procedure:** run `scripts/benchmark-nativeserver0-throughput.sh 3` with Java 25 selected (for example `NATIVESERVER0_JAVA=/path/to/java`). Each iteration runs an unmodded Fabric baseline then the same server with `target/native-accelerator-0.1.0-SNAPSHOT.jar` installed. Compare only successful paired runs from the emitted TSV.
- **Metrics:** wall-clock readiness is measured for both modes. Native runs also capture the in-process `server.ready` and `server.init` timing stages from `logs/nativeaccelerator-startup-times.tsv`. The baseline readiness log line is used only because it has no Native Accelerator probe.
- **Performance rule:** retain behavior parity and this workload contract. Do not claim an improvement from a changed seed, persisted world/cache, changed heap, different Java runtime, or a timeout/failed run.

Before a test series, build the mod JAR and verify its Fabric metadata. The harness copies that JAR into the isolated native instance; it never edits the supplied server directory.


## Worldgen throughput pass — whole-stack noise, phase profiling, Solaris DAX

Cold FULL-chunk generation is the current NativeServer0 server-throughput target. Preserve these rules:

- `noise.mode=stack` is the preferred Perlin path: stage one `DensityBuffer` once per private
  `NoiseStack$Perlin.addToVolume` invocation, accumulate every native Perlin layer into that direct buffer,
  then copy back once. `single` preserves the older per-layer A/B path and `off` is vanilla noise.
- A failed native layer must commit already-completed additive native layers exactly once and continue the
  current/remaining work in vanilla Java. Never lose or double-apply a layer.
- `worldgen.profile=false` is the throughput default. Disabled profiling must not touch ThreadLocal timer
  state. Diagnostic profiling records chunk-status completion plus `terrain.total`, `createNoiseChunk`,
  `doFill`, `buildSurface`, `generateCarvers`, and `density.sampleVolume`.
- Chunk-status/future totals overlap across background workers and may include dependency waiting; never sum
  them into a claimed wall-clock duration.
- The 26.3 FULL generation dependency radius is 11 chunks. Use the non-overlapping 16x16 target regions in
  `docs/WORLDGEN_THROUGHPUT.md` for cold A/B runs.
- DAX is a Solaris OS accelerator, not a SPARC SIMD implementation. Perlin/vector arithmetic remains in the
  SPARC VIS/VIS2/VIS3 backend. `libdax` is used only for operations matching its scan/select/extract/
  translate/logical model.
- The historical Oracle `com.oracle.stream.DaxIntStream` JAR is an optional accelerator dependency. If it
  is present, prefer `DaxIntStream.of(int[]).parallel()` for supported heap-int stream operations. Keep the
  linkage optional/reflection-safe so non-SPARC/non-Solaris builds do not require the JAR. In `auto` mode
  dispatch JAR -> optional ABI-v3 `libdax` symbols -> Java fallback; a JAR linkage failure on a newer JVM
  disables only that path. Native-memory callers still use the libdax ABI directly.
- A `dax_context_t` is thread-bound. Keep one lazy context per calling/native-worker thread; never share it.
- DAX output buffers require 64-byte alignment/padding. Do not pass an arbitrary Panama/direct buffer as a
  DAX output unless that contract is explicitly satisfied.
- libdax compares fixed-width values unsigned and big-endian. Signed Java int ranges crossing zero require
  the wraparound OR predicate documented in `docs/DAX_ACCELERATION.md`.
- `scripts/run-dax-adapter-test.sh` uses a fake libdax surface to validate signed-range wraparound,
  endian conversion and select ordering on development hosts without DAX hardware. Keep it passing.
