# Performance rework — 2026-09-22

This source snapshot restores production-throughput defaults after the Metal bring-up/debug cycle.

## Cross-backend
- profiling mixins are transformed out unless explicitly enabled;
- chunk compiler workers back off instead of busy-spinning when the shared staging arena is full;
- OpenGL's hard-coded two-submit fence ring is configurable and defaults to three submits in flight;
- GUI item-atlas backend detection is cached.

## Metal / legacy Mac1 (GT 650M class)
- asynchronous submission: normal frame submit no longer immediately waits for the GPU fence;
- transient resources and retained texture transfers are fence-retired later;
- Mac1 defaults to two frames in flight;
- global GPU-idle-before-buffer-upload and pre-acquire swapchain waits are off by default;
- Metal trace and benchmark counters are off by default;
- large vertex/index terrain heaps no longer allocate equally-large CPU shadows;
- GPU-only buffer writes use bounded per-encoder staging snapshots and are flushed as a batch.

## SPARC / Solaris / DAX
- DAX submission concurrency defaults to an adaptive physical-core based cap instead of fixed 32;
- genuine zero-copy native MemorySegments use a lower DAX crossover (8192 ints by default);
- SPARC SMT8 worldgen defaults to six strands/core, with existing reserved-core policy retained.

## Useful A/B switches
- `-Dnativeaccelerator.renderer.opengl.framesInFlight=2` restores vanilla OpenGL queue depth.
- `-Dnativeaccelerator.renderer.chunkBackpressure=false` restores pure busy-spin behavior.
- `-Dnativeaccelerator.renderer.metal.mac1SerializeBufferUploads=true` restores the global-idle Mac1 diagnostic.
- `-Dnativeaccelerator.renderer.metal.mac1WaitForSwapchain=true` restores the pre-acquire swapchain wait.
- `-Dnativeaccelerator.renderer.metal.trace=true` and `...benchmark=true` re-enable diagnostics.

The provided project export does not contain the repository build scripts or native C source tree, so this package is a patched source snapshot rather than a verified Gradle-built JAR.
