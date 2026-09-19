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
