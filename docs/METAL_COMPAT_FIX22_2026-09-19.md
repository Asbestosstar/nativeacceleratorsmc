# Metal compatibility fix22

The buildfix21 run proves that device creation, transfer mapping, render-pass creation and typed
shader creation all work on the OCLP NVIDIA GT 650M MacFamily1 path. The remaining pipeline
failures are portable-API differences:

1. SDL GPU exposes exactly four uniform slots per shader stage while Minecraft 26.3 terrain
   shaders can use five or more vertex UBOs.
2. SDL GPU has no triangle-fan primitive while several Minecraft sky/debug pipelines use
   `PrimitiveTopology.TRIANGLE_FAN`.

Fix22 lowers those differences rather than lying about capabilities.

## Uniform overflow

Logical UBO slots 0..2 remain ordinary SDL pushed uniforms. When a shader needs more than four
logical uniform resources, logical slots 3+ are packed into physical SDL slot 3 at fixed 512-byte
aligned subranges. The generated SPIRV-Cross MSL entry signature is rewritten from several
`constant T& [[buffer(N)]]` parameters to one `constant uchar* [[buffer(3)]]`, then local references
with the original names/types are recreated at the matching byte offsets. Helper functions remain
unchanged. The compatibility path caps one packed slot at 4096 bytes.

## Triangle fans

Metal graphics pipelines representing RenderPearl triangle fans are physically created as SDL
triangle-list pipelines. A prebuilt 32-bit index buffer expands non-indexed fans as
`(0,1,2), (0,2,3), ...` at draw time, preserving the existing vertex buffer. Vanilla 26.3's
currently failing fan pipelines are non-indexed. Indexed/indirect fan draws remain explicit
unsupported cases rather than silently rendering the wrong geometry.

Startup marker:

`[Native Accelerator] Build marker: metal-compat-fix22`

