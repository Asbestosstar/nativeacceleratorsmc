# RenderMan Shading Language (RSL) and Open Shading Language (OSL)

## Key difference from ordinary GPU fragment programs
RSL and OSL rely on **renderer-provided shading services and evaluation models**. Parsing the source or lowering arithmetic to SPIR-V is not enough. Avoid claims that every RSL/OSL shader can run as a Vulkan fragment shader.

## Historical RSL
Locate authoritative RenderMan Interface Specification/RenderMan Shading Language revision and licensing; preserve surface, light, displacement, volume/atmosphere semantics for the selected version, shader parameters, geometry/state, coordinate spaces, noise/texturing, derivatives, illuminance loops, ray calls and renderer services as defined. Some shader types or services require dedicated render passes or a ray tracing/micropolygon renderer rather than a standard Minecraft forward fragment stage.

## OSL
https://github.com/AcademySoftwareFoundation/OpenShadingLanguage for maintained documentation and compiler/runtime. OSL is a shading-network language commonly evaluated in renderers; preserve shader groups, closures, derivatives and message/trace/texture services where available. Reuse existing OSL toolchain when legal/platform-viable rather than writing a partial parser and claiming full OSL support. CPU execution often more practical for reference validation; GPU subset only when the required services and architecture are present.

## Architecture
`RendererServiceInterface` for texture access, coordinate transforms, derivatives, traces, ray queries, closure evaluation, time/differentials, shading networks; pass graph can run displacement or atmosphere separately. Require feature-capability negotiation. Material mapping binds external scene data, not arbitrary additional source-language syntax.

## Acceptance
Documented supported revision and exact subset, compilation + real program execution, numerical/reference renderer tests, image comparisons and hard errors when a requested renderer service cannot be supplied. Never quietly replace ray tracing with environment color and label equivalent.
