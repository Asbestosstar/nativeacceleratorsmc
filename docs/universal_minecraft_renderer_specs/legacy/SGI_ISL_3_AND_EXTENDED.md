# SGI Interactive Shading Language — specification fidelity and Minecraft integration

## Objective
Reimplement **original SGI ISL 3.0 (August 8, 2002)** exactly for all defined language and appearance behavior, while permitting genuinely documented newer ISL versions if found and *separately named* project extensions. Original `.isl` programs must compile without changes in strict compatibility mode. This is not GLSL with renamed keywords.

## Normative reference
Historical ISL 3.0 manual: https://www.infania.net/misc1/sgi_techpubs/techpubs/007-4648-001.pdf . Read *all* chapters including Files, Data Types, Variables and Identifiers, Uniform/Parameter/Varying Operations, Built-in Functions, Declarations, Statements, Functions, Level of Detail; plus original `islc`, `ipf2ogl`, `islAppearance`, `islShader`, `islCompileAction`, `islDrawAction` references where available. Generate `docs/ISL3_CONFORMANCE.md` mapping every clause/overload to implementation and tests. Treat the manual, not this project plan, as the rule when conflicts arise.

## Language frontend
Real preprocessor/includes, lexer (source locations), parser, AST, typed semantic analyzer, IR, optimizer preserving observable clamping and framebuffer behavior. Exactly one shader definition per `.isl` file as documented; include behavior according to original language. Types/qualifiers must include `uniform float/color/matrix/string`, `parameter float/color/matrix`, `varying color`, and permitted one-dimensional arrays; implement allowed conversions and restrictions, row-major 16-element matrices and 4-component colors as specified. Original uniform recompilation versus runtime parameter changes must be preserved. `varying color` components clamp to 0..1 at the specified semantic points.

## Original variables and functions
Implement predefined `FB`, `shadermatrix`, `lightVector`, `pi`, `numambientlights`, `numdirectlights` with their actual declared types/contexts, and every other documented builtin. Extract and test every Section VIII function overload, numerical semantics, side effects and texture restrictions. In strict mode reject computed-result dependent texture coordinates where the specification forbids them. Implement uniform/parameter/varying assignments, array and component access, actual control flow including `repeat` and varying active-pixel conditionals; do not substitute arbitrary GLSL statements.

## Shader kinds and appearance runtime
Support `surface`, `ambientlight`, `distantlight`, `pointlight`, and historical `atmosphere` function-type handling as documented for 3.0. Preserve initialization/meaning of `lightVector`, shader matrices, light iteration, shader ordering, surface composition and framebuffer `FB` reads/writes. Implement an appearance object/runtime, per-object parameters and texture names. If a shader requires pre-existing framebuffer values or composition/clamping at intermediate steps, plan accurate passes or equivalent proven fusion.

## Profiles
- `ISL_3_0_STRICT`: exactly documented 3.0 syntax and semantics; reject later extensions.
- `ISL_OFFICIAL_LATER`: only when an authentic later SGI specification exists, with documented version (do **not** assume official ISL 4.0 exists).
- `ISL_EXTENDED`: independently documented modern extensions (vertex/compute, HDR, PBR, dynamic texturing etc.) that never break strict profile.

## Backend integration
Frontend produces semantic appearance IR, which lowers into multiple GPU passes when needed. GLSL on GL, SPIR-V on Vulkan, MSL on Metal, DXIL on D3D where features match. For pure historical operations supply CPU reference evaluator; no Minecraft classes in parser/IR. Host metadata binds a Minecraft block/entity to an `islAppearance` and original ISL parameters; do not add Minecraft keywords to ISL.

## Testing
Lexer/parser valid+invalid fixtures; all types/conversions; builtin overloads; uniform versus parameter recompilation; `FB` and varying active-pixel behavior; texture restrictions; light shader vector semantics; multi-surface composition; shading equation reference tests; GPU-versus-CPU pixel comparison. Compare with original SGI SDK/IRIX hardware when available; otherwise claim specification-tested, not hardware differential verified. Flag undefined and implementation-dependent cases.

## Acceptance
Unmodified valid original ISL samples (provided or reconstructed from normative examples) parse, compile, render; invalid examples fail correctly; two surface shaders and at least one light shader work; framebuffer and clamping tests pass; runtime parameters change without recompilation; shader works through at least OpenGL and Vulkan when required capabilities permit; no false 'full compliance' until every normative feature is tested.
