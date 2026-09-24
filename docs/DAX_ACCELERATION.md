# Solaris/SPARC DAX acceleration

Native Accelerator treats Oracle DAX as an optional Solaris facility, not as a SPARC SIMD replacement.
VIS/VIS2/VIS3 remain the architecture vector backends for arithmetic such as Perlin noise. DAX is used
only for bulk operations matching the libdax execution model.

## Java 25 policy and optional DaxIntStream JAR

The historical Oracle `DaxIntStream` JAR is an optional dependency. When it is present and exposes the
expected Stream-like API, Native Accelerator **prefers it** for heap-backed `int[]` operations. The bridge
is reflection-safe so the universal JAR still loads when the dependency is absent. In `auto` mode the
dispatch order is:

1. Oracle `DaxIntStream.of(values).parallel()`;
2. the optional ABI-v3 native `libdax` adapter;
3. compact Java loops.

Oracle's standalone stream library has its own profitability checks and can fall back to core execution
when offload is not useful. The bridge therefore defaults `dax.jar.minElements` to zero and lets the
Oracle library make that decision. If the old JAR is present but fails linkage on Java 25, Native
Accelerator disables that path once and automatically continues with native libdax or Java.

Current operations:

- signed `int[]` inclusive range count via `filter(...).count()`;
- signed `int[]` inclusive range select/filter via `filter(...).toArray()`;
- direct `anyMatch` / `allMatch` / `noneMatch` when the DaxIntStream JAR is active;
- zero-copy native `MemorySegment` count/select entrypoints for future bulk Minecraft/native pipelines.

The JAR path always marks the stream `parallel()`: Oracle's published implementation only offloads
parallel streams to DAX.

The native extension symbols are optional ABI-v3 symbols:

```text
na_dax_i32_count_range
na_dax_i32_select_range
```

Older ABI-v3 libraries without the symbols continue to load; Java falls back automatically.

## Signed Java integers on libdax

libdax fixed-width comparisons are unsigned and big endian. On big-endian SPARC, a Java `int[]` can be
staged byte-for-byte with no native endian-conversion pass. On a little-endian Solaris target the native
adapter converts the staged source to big-endian DAX elements and converts selected output back to host
order. Ranges entirely below zero or entirely at/above zero preserve ordering under the unsigned
representation and use `DAX_GE_AND_LE`.

A range crossing zero wraps around unsigned order. For example `[-10, 10]` is represented as:

```text
unsigned(value) <= 10 || unsigned(value) >= 0xfffffff6
```

and uses `DAX_LE_OR_GE` with swapped comparison endpoints.

## Threading

A `dax_context_t` belongs to the thread that created it. `dax_int.c` therefore creates one context lazily
per native worker/calling thread using pthread TLS and finalizes it from that thread's TLS destructor.
Never share a DAX context between Java/worldgen workers.

## Output buffers

DAX scan masks and select destinations are allocated on 64-byte boundaries and sized with
`DAX_OUTPUT_SIZE`. Select output is copied back to the Panama staging buffer after completion. This extra
copy is intentional: a generic direct ByteBuffer is not guaranteed to satisfy libdax's 64-byte output
alignment/padding contract.

## Runtime switches

All names have the `nativeaccelerator.` prefix:

- `dax.mode=auto` (default) — prefer DaxIntStream JAR, then native libdax, then Java;
- `dax.mode=jar` — use the DaxIntStream JAR when available, otherwise Java;
- `dax.mode=native` — bypass the JAR and use native libdax when eligible;
- `dax.mode=off` — force Java/VIS/non-DAX fallback;
- `dax.jar.minElements=0` — minimum heap-array size before entering the JAR path; default lets Oracle's own heuristics decide;
- `dax.minElements=65536` — native-libdax crossover before Java heap/direct staging is attempted;
- `dax.allowEmulation=false` — allows a libdax-only environment to attempt the extension for diagnostics.
  Actual libdax software emulation is still controlled by Solaris/libdax (for example `DAX_EMULATE=1`).

The first threshold is conservative. Measure it on T7/M7/M8/S7 hardware; never assume DAX wins for small
arrays after Java-to-direct staging.

## Build integration

The main CMake file should include `native/cmake/Dax.cmake` after creating the `nativeaccelerator` target and call:

```cmake
include(cmake/Dax.cmake)
na_enable_solaris_dax(nativeaccelerator)
```

The helper checks for both `dax.h` and `libdax`, adds the Solaris source only when both exist, and links
`-ldax`. No stub symbol is emitted on unsupported systems so Java optional-symbol detection remains exact.

