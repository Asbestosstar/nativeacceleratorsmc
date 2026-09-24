# Metal uniform native-four-slot fix24

Fix23 could start Minecraft and enter a world on the GT 650M, but rendering was visibly corrupted.
The corruption came from an unnecessary ABI rewrite.

A RenderPearl pipeline may declare five logical uniform-like resources while SPIRV-Cross optimizes
one of them (commonly push constants) out of a particular shader. In the observed terrain case the
generated MSL actually used only `buffer(0)` through `buffer(3)`. Those four buffers already fit SDL
GPU natively.

Fix23 still rewrote `buffer(3)` into `_naPackedUniforms [[buffer(3)]]` simply because the logical
count was five. Fix24 changes the rule:

- inspect the generated MSL entry signature first;
- if no active uniform parameter uses `buffer(4)` or higher, perform no source rewrite at all;
- keep active `buffer(3)` directly bound to SDL uniform slot 3;
- only when an active `buffer(4)+` really survives SPIRV-Cross do we pack active slots 3+ together;
- CPU-side upload routing is rebuilt from the same active-MSL slot set.

This removes the compatibility transform from the common terrain case and preserves the shader ABI
whenever SDL's native four slots are sufficient.

Startup marker:
`[Native Accelerator] Build marker: metal-uniform-native4-fix24`

