# SGI OpenGL hotfix — 2026-09-23

The first performance-rework snapshot added `GlCommandEncoderThroughputMixin`, which changed RenderPearl OpenGL's command/fence window from 2 submits to 3. That was too invasive. `GlCommandEncoder` is not the only object built around a two-submit lifetime; `GlTransientMemory.PersistentMapping` also has a two-slot rotation array, and the fallback path relies on the original submit/rotate lifetime. Old macOS NVIDIA OpenGL drivers are especially sensitive to buffer deletion/reuse and implicit synchronization.

This hotfix therefore removes the OpenGL submit-window mixin entirely and leaves RenderPearl's native fence/transient-memory lifetime unchanged.

Retained changes include:
- Metal trace/benchmark production defaults off and guarded hot-path logging;
- asynchronous Mac1 Metal submission/resource retirement;
- removal of normal-path Metal GPU-idle/swapchain serialization;
- reduced Metal terrain CPU-shadow duplication and batched upload staging;
- chunk compiler/resort backpressure;
- profiling mixin gating;
- cached item-atlas backend detection;
- adaptive SPARC/DAX concurrency and lower zero-copy DAX crossover.

If OpenGL remains slow after this hotfix, capture an F3 screenshot after standing still for ~20 seconds and, ideally, a short Java Flight Recorder or Spark profile. The screenshot that triggered this hotfix showed ~4 FPS while server TPS remained normal, strongly isolating the problem to rendering rather than game simulation.
