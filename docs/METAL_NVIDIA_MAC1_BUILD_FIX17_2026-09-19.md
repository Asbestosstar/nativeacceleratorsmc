# Metal buildfix17 — NVIDIA MacFamily1 / OCLP-safe capability probing

This revision fixes a startup abort seen on legacy NVIDIA Metal devices such as the GeForce GT 650M when
running a patched/OCLP Metal stack.

## Rules

1. Any Metal device name containing NVIDIA, GeForce, or Quadro is hard-capped to `MAC1_COMPAT`.
   Advertised Mac2, Metal3/4, or Apple-family values cannot promote it.
2. Startup never allocates `MTLIndirectCommandBuffer` objects to discover support.
3. Graphics ICB support is queried with `supportsFeatureSet:MTLFeatureSet_macOS_GPUFamily2_v1` (raw value
   `10005`) and requires corroborating Mac2 or Apple7+ family evidence.
4. MacFamily1/NVIDIA keeps SDL's documented MacFamily1 compatibility property enabled, disables Apple's
   Metal validation layer by default, and uses Native Accelerator's CPU indirect decoder.
5. After SDL device creation, the SDL device name is checked again. If it is NVIDIA, native indirect is
   disabled even if preflight data was inconsistent.
6. `-Dnativeaccelerator.renderer.metal.mac1Validation=true` remains a diagnostics-only override. It must
   never be required for normal legacy NVIDIA operation.

## Expected GT 650M behavior

The startup log should identify `legacyNvidia=true`, select `MAC1_COMPAT`, report `icb=CPU-fallback`, and
create the SDL Metal device with `allowMacFamily1=true`. No graphics ICB is created by the preflight path.
