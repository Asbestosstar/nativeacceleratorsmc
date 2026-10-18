# Shaderpack UI and profiling notes

## Current conventional shaderpack behavior

The legacy selector discovers packs and saves a requested pack ID. Conventional shader preparation is currently frontend-only; the saved option values are Native Accelerator preferences and are not consumed by a live conventional shader runtime. Selecting a pack or changing its options therefore does not promise a visual change. If a pack appears selected but does not alter the image, that is the current execution boundary, not a shader setting that failed to load.

The separate Native Accelerator URD fixture path is gated by `nativeaccelerator.urd.enabled`. It is not the conventional shaderpack runtime. When enabled, its fullscreen copy/draw work can add GPU cost. Leave it disabled for ordinary play unless intentionally testing that fixture path.

## Open the `native0` save for profiling

Minecraft 26.3 already supports singleplayer Quick Play. Add this to the launcher's game arguments:

```text
--quickPlaySingleplayer native0
```

`native0` must be the save-folder/level ID under the active game directory, not just the world's displayed title. Confirm the launcher instance's game directory contains the `native0` save. Without this argument, normal startup behavior is unchanged. No Native Accelerator world-autojoin mixin is needed.

## JFR

For a Java Flight Recorder profile, add the following to the launcher's JVM arguments (alongside the game's existing arguments):

```text
-XX:StartFlightRecording=filename=native0-renderer.jfr,settings=profile,dumponexit=true
```

Launch with `--quickPlaySingleplayer native0`, reproduce the renderer workload, then exit normally so the recording is finalized. `settings=profile` adds overhead; compare profiles against runs with the same workload and settings rather than treating a profiled run as a throughput benchmark.

## VisualVM

Start VisualVM before launching, then launch Minecraft with `--quickPlaySingleplayer native0` and attach to the Minecraft Java process. Use the sampler or profiler only for a short diagnostic capture; instrumentation can change scheduling and frame timing. Keep the save and game directory consistent between captures.
