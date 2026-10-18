# ISL 3 conformance status

Native Accelerator keeps SGI ISL as a separate historical frontend. It is not treated as GLSL, and
ISL source is not silently lowered to SPIR-V.

## Current status

| Area | Status | Evidence |
|---|---|---|
| Explicit `ISL_3_0_STRICT` profile identity | IMPLEMENTED | `IslProfile` |
| Source-location diagnostics | IMPLEMENTED | `IslDiagnostic`, `IslLexer` |
| Clean-room tokenization foundation | UNIT_TESTED | `IslFrontendTest` |
| Preprocessor/includes | INCOMPLETE | Not implemented |
| Full ISL grammar/parser/AST | INCOMPLETE | Not implemented |
| Typed semantic analysis | INCOMPLETE | Not implemented |
| `FB` framebuffer semantics and clamping | INCOMPLETE | Not implemented |
| Uniforms/parameters/varyings | INCOMPLETE | Not implemented |
| Light shader and appearance runtime | INCOMPLETE | Not implemented |
| CPU reference evaluator | INCOMPLETE | Not implemented |
| GPU/backend lowering | INCOMPLETE | No lowering claimed |

The current frontend intentionally returns an explicit informational diagnostic stating that parsing,
typed appearance semantics, and runtime behavior are incomplete. A module with that diagnostic is not
valid. This prevents a lexer fixture from being mistaken for ISL 3 compliance.
