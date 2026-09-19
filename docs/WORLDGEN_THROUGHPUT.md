# NativeServer0 world-generation throughput pass

The current server target is cold FULL-chunk generation, not startup micro-optimization. Minecraft 26.3
runs the heavy `NoiseBasedChunkGenerator.buildTerrain` body on the background executor, but synchronous
server chunk requests can block waiting for the generation future. A "server is behind" message therefore
measures user-visible tick starvation even when much of the CPU work is on worldgen workers.

## A/B modes

`nativeaccelerator.noise.mode` provides three Native Accelerator modes:

- `off` — vanilla Java Perlin volume evaluation;
- `single` — the older per-`PerlinNoise.addToVolume` native path, with one heap/direct staging pair per layer;
- `stack` — default; one heap/direct staging pair for the entire private `NoiseStack$Perlin.addToVolume` call.

The `stack` path redirects each existing Perlin layer into one persistent direct float buffer. If any native
layer fails, completed native additions are copied back once and the current plus remaining layers continue
through vanilla Java. This keeps the optimization reversible and additive-parity safe.

Use `nativeaccelerator.noise.minCells=16384` as the initial crossover. It is deliberately conservative and
must be re-measured on each CPU/backend; do not tune it from one cold run.

## Low-overhead profiler

Profiling is disabled by default. Enable only for diagnostic runs:

```text
-Dnativeaccelerator.worldgen.profile=true
-Dnativeaccelerator.worldgen.profileReportEvery=256
```

When disabled, the profiler mixins do not mutate ThreadLocal timer state. When enabled they aggregate:

```text
status.structure_starts
status.structure_references
status.biomes
status.terrain
status.features
status.initialize_light
status.light
status.spawn
status.full
terrain.total
terrain.createNoiseChunk
terrain.doFill
terrain.buildSurface
terrain.generateCarvers
density.sampleVolume
noise.stack.accelerated
noise.stack.partialFallback
```

`status.*` and `terrain.total` are asynchronous wall times from scheduling/entry until future completion.
Their totals can overlap across workers and can include dependency waiting. The synchronous terrain subphase
metrics measure work actually executing inside the background terrain task. `density.sampleVolume` also
reports sampled cell count. A final cumulative report is printed on clean server shutdown.

Do not compare profiled and unprofiled runs as a performance result. Use profiling to locate the bottleneck,
then disable it for the paired timing run.

## Four-region cold workload

FULL generation has a worst-case accumulated dependency radius of 11 chunks in the 26.3 generation pyramid.
Use target regions separated enough that their 11-chunk dependency halos do not overlap.

Recommended target chunk regions:

```text
A: [0,0]   .. [15,15]
B: [48,0]  .. [63,15]
C: [0,48]  .. [15,63]
D: [48,48] .. [63,63]
```

Equivalent `/forceload add` block-coordinate rectangles are:

```text
/forceload add 0 0 255 255
/forceload add 768 0 1023 255
/forceload add 0 768 255 1023
/forceload add 768 768 1023 1023
```

Run the four commands sequentially, wait for each region to settle before submitting the next, flush saves,
and stop cleanly. Use a fresh world with the benchmark-contract seed for every compared mode.

## Comparison matrix

Run the same Java 25 build/server settings with:

```text
A  unmodded vanilla
B  Native Accelerator + -Dnativeaccelerator.noise.mode=off
C  Native Accelerator + -Dnativeaccelerator.noise.mode=single
D  Native Accelerator + -Dnativeaccelerator.noise.mode=stack
```

Interpretation:

```text
A vs B  non-noise Native Accelerator effect
B vs C  old per-layer native-noise effect
C vs D  staging reduction from whole-stack batching
B vs D  net value of the preferred native-noise path
```

Use a separate `stack + worldgen.profile=true` diagnostic run to decide what to optimize next. Do not raise
worldgen worker counts until the phase profile shows CPU headroom; density, features, structures, or lighting
may already be saturating cores/cache/memory bandwidth.

## Pass 2 — fill, surface and lighting

The 4,096-requested-chunk diagnostic run moved the priority away from `createNoiseChunk`. The measured
terrain cost is dominated by surface construction and fill, while the two light statuses are also a major
independent subsystem. Remember that `density.sampleVolume` is nested *inside* `terrain.doFill`; never add
those two totals together as independent work.

Pass 2 adds three independently suppressible throughput paths:

```text
-Dnativeaccelerator.worldgen.fastFill=true
-Dnativeaccelerator.worldgen.fastSurface=true
-Dnativeaccelerator.worldgen.fastLighting=true
```

`fastFill` and `fastLighting` default to true. `fastSurface` defaults to false pending parity validation of
special surface extensions that can mutate heightmaps during the same buildSurface pass. The mixin config
plugin removes disabled fast-path mixins at transformation time for command-line benchmark switches, so an
A/B-off run does not retain a per-voxel redirect branch.

### fastFill

`NoiseBasedChunkGenerator#doFill` keeps the vanilla order and semantics:

```text
aquifer.computeSubstance
fallback to defaultBlock
write LevelChunkSection
update OCEAN_FLOOR_WG
update WORLD_SURFACE_WG
schedule fluid post-processing when requested by the aquifer
```

The difference is traversal. Unit-step 16xN x16 terrain volumes are consumed linearly from the existing
`DensityBuffer` float array, and `LevelChunkSection` is resolved once per vertical 16-block band rather
than once per voxel. Debug-aquifer visualization and unusual stepped volumes remain vanilla.

### fastSurface (experimental, default off)

`MaterialSystem#buildSurface` still uses Minecraft's ordinary `MaterialRuleContext`, compiled rule tree,
biome lookup, block reads/writes, badlands/frozen-ocean extensions and rule evaluation. The experiment caches
the 256 `WORLD_SURFACE_WG` heights for the current chunk and answers repeated starting-height and gradient
queries from that primitive array. Because special extensions may write blocks and change heightmaps while
the same surface pass is running, this path is not enabled by default until a block/heightmap parity harness
proves it safe. Deep profiling is the preferred next surface step.

### fastLighting

`ChunkSkyLightSources#fillFrom` still performs the same top-to-bottom edge-occlusion test for every X/Z
column. The fast path computes `LevelChunkSection#hasOnlyAir()` once per section instead of rechecking the
same section for up to 256 columns. It does not replace the light propagation engine.

## CPU-time and deep profiling

Elapsed time across background workers is not CPU time. Enable current-thread CPU accounting explicitly:

```text
-Dnativeaccelerator.worldgen.profile=true
-Dnativeaccelerator.worldgen.cpuProfiler=true
```

The report then includes both `elapsed ms` and `cpu ms` for synchronous spans. Asynchronous future/status
spans intentionally have no CPU attribution because completion can occur on another thread.

For a much more intrusive diagnostic split, also enable:

```text
-Dnativeaccelerator.worldgen.deepProfile=true
```

Deep-profiler mixins are transformation-gated and are absent entirely unless this property is true. They
add these metrics:

```text
surface.compile
surface.ruleEvaluation
surface.ruleBiome
surface.preliminaryLevel
surface.secondaryNoise
lighting.initializeLightSources
lighting.skySources
lighting.runUpdate.total
lighting.preTasks
lighting.propagation
lighting.postTasks
```

`surface.ruleEvaluation` and the MaterialRuleContext helper metrics time hot per-rule calls, so never use a
deep-profile run as a throughput result. Its purpose is to decide whether the next surface rewrite should
focus on rule dispatch, biome/noise predicates, or residual block traversal.

Likewise, the lighting split decides the next implementation step. If `lighting.skySources` or
`lighting.initializeLightSources` dominates, optimize scanning. If `lighting.propagation` dominates, work
on the light graph/queues instead; do not assume more worker threads will help.

## Pass-2 A/B matrix

Use the same fresh world, Java, heap, seed and non-overlapping region list for every row:

```text
A  Native Accelerator, noise.mode=off,
   fastFill=false fastSurface=false fastLighting=false

B  A + fastFill=true
C  A + fastLighting=true
D  A + fastFill=true fastLighting=true
E  D + noise.mode=stack

Experimental surface parity/performance run (not part of the default combined result):
F  A + fastSurface=true
```

Run profiling separately from A-F. Primary throughput metrics are total workload wall time and completed
FULL chunks per second; also retain median/p95 region completion and maximum tick stall. The server-behind
warning by itself is too noisy to rank changes.

## Pass 3 — surface-rule dispatch and high-SMT topology scheduling

The first deep-profile run reported very large CPU totals in `surface.ruleEvaluation`,
`surface.preliminaryLevel` and `surface.ruleBiome`. Treat the absolute values from that run as diagnostic,
not as unbiased CPU totals: the original deep profiler read both `System.nanoTime()` and current-thread CPU
time around every hot rule/helper call. Millions of calls therefore amplified profiler overhead inside the
very categories being measured.

Pass 3 samples hot calls instead. Exact invocation counts are still retained, but expensive wall/CPU timers
run once per N calls (default 256), and the reported aggregate time is an estimate scaled from those samples:

```text
-Dnativeaccelerator.worldgen.deepProfile=true
-Dnativeaccelerator.worldgen.deepProfileSampleRate=256
```

Increase the sample rate (for example 1024) if profiling itself is still visible in whole-workload wall time.
Always compare a non-deep-profile run for real throughput.

### Fast surface-rule compiler

`-Dnativeaccelerator.worldgen.fastSurfaceRules=true` is on by default. It does **not** replace Minecraft's
material predicates or special surface behavior. It only removes interpreter-like composition overhead from
the rule tree:

- nested `SequenceRule` nodes are flattened while preserving first-non-null ordering;
- consecutive `ConditionRule` nodes become one condition array, evaluated in the same outer-to-inner order;
- repeated references to the same built-in `MaterialCondition` object share its compiled evaluator, allowing Minecraft's
  own `LazyXZCondition`/`LazyYCondition` cache to be shared across branches; third-party conditions compile independently;
- a terminal `BlockRule` returns its constant state directly after those conditions pass;
- unknown/complex leaves compile through Minecraft unchanged;
- any compiler failure recompiles the complete vanilla tree.

This keeps biome, noise, Y/stone-depth, water, preliminary-surface and mod-provided leaf semantics in Java and
Minecraft while reducing nested lambda/interface dispatch. It is independently suppressible for A/B testing:

```text
-Dnativeaccelerator.worldgen.fastSurfaceRules=false
```

### High-SMT terrain scheduler

Minecraft 26.3's global background executor already targets approximately `availableProcessors() - 1` workers.
On a fully visible 32-core/256-strand SPARC M8 that can therefore approach 255 workers, but it has no knowledge
of which logical processors share one physical core. Pass 3 adds an optional topology-aware terrain pool rather
than simply creating still more threads.

Solaris discovers the explicit `chip_id`/`core_id` -> logical CPU mapping from `kstat -p cpu_info`; Linux uses
sysfs, which also makes the same scheduler infrastructure usable by future POWER tests. Worker CPU order is
**core first, then sibling strand level**. For example, on three SMT8 cores, SMT4 is ordered as:

```text
C0S0 C1S0 C2S0  C0S1 C1S1 C2S1  C0S2 C1S2 C2S2  C0S3 C1S3 C2S3
```

The dedicated terrain executor is a `ForkJoinPool`, so workers retain local deques and work stealing rather
than contending on one central queue. On Solaris, Java 25 FFM calls `processor_bind(P_LWPID, P_MYID, ...)` to
bind each terrain worker to its selected logical processor when binding is enabled. Failure is harmless and
leaves the worker unbound.

Controls:

```text
-Dnativeaccelerator.worldgen.smtScheduler=true
-Dnativeaccelerator.worldgen.smt.strandsPerCore=4
-Dnativeaccelerator.worldgen.smt.reserveCores=1
-Dnativeaccelerator.worldgen.smt.bind=true
-Dnativeaccelerator.worldgen.smt.pinServerThread=true
```

`strandsPerCore=0` means automatic. The initial SMT8 automatic policy is deliberately conservative at four
strands/core; it is a starting point, not a claim that SMT4 is optimal. On high-SMT hosts one physical core is
reserved by default and the dedicated server thread is best-effort pinned to its first strand on Solaris.
This is **not hard isolation**: Minecraft/JVM pools outside the terrain scheduler may still migrate onto that
core unless the administrator also uses Solaris processor sets/affinity policy.

On ordinary SMT1/2 systems the dedicated SMT scheduler defaults off, retaining Minecraft's normal executor.

### Required SMT8 sweep

For an M8/T8, benchmark the same fixed cold-world workload at:

```text
strands/core = 1, 2, 4, 6, 8
```

Keep `reserveCores`, Java, heap, seed, world, native kernels and feature switches fixed. Run deep profiling only
as a separate diagnostic. Rank configurations primarily by:

```text
FULL chunks / wall-second
terrain chunks / wall-second
workload wall time
median and p95 region completion
max tick stall
```

Then compute physical-core-normalized throughput. Do not assume SMT8 wins merely because all strands are busy;
if SMT6 is within a few percent of SMT8 while reducing contention, prefer the configuration that gives the
server control thread and JVM more headroom.

`./scripts/run-smt-layout-test.sh` is hardware-independent and verifies core-first placement/reservation. The
actual 1/2/4/6/8 performance sweep must run on the target high-SMT machine.

### DAX concurrency under SMT8

DAX submission is separately bounded with `-Dnativeaccelerator.dax.maxConcurrent` (default 32). Both the
optional `DaxIntStream` path and direct/native `libdax` integer scan/select paths use the same non-blocking
permit budget. When the budget is saturated, callers fall through to the next backend/Java instead of letting
hundreds of SMT workers queue accelerator requests. Tune this independently from CPU strands/core.
