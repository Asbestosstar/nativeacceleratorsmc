# HP VISUALIZE / Starbase / PHIGS / PEX — fidelity specification

## Historical distinction
HP VISUALIZE is a **hardware graphics family**. Starbase is an HP graphics API; PHIGS and PEX are structured/retained graphics interfaces; HP-UX OpenGL is an OpenGL implementation. Implement these as separate adapters and hardware capability profiles over URD, not fictional shader languages. HP PowerShade functionality may be added only from authentic documentation.

## Normative sources
HP-UX Graphics Administration Guide https://h10032.www1.hp.com/ctg/Manual/lpv38781.pdf ; HP graphics/Starbase/PHIGS/PEX programming references (find and cite exact editions); OpenPA hardware overview https://www.openpa.net/pa-risc_graphics_visualize-fx.html (secondary, verify against HP manuals). Keep `docs/HP_VISUALIZE_CONFORMANCE.md` per device/API feature and source page. Do not hardcode speculative board specs as normative.

## Device profiles
VISUALIZE-EG, VISUALIZE-FX2, FX4, FX6, FXE, FX5, FX10 plus any verified models. Capture image/overlay/alpha planes, Z precision, colormaps, double buffering, display formats, texture acceleration and driver-exposed GL capabilities. Initial historical claims to verify: FX2 ~24 image planes/24-bit Z; FX4/FX6 ~48 image planes; FX6 16 alpha planes; 8 overlay planes commonly reported. Verify exact configurations and optional texture hardware before enforcing in strict mode.

## Shared geometry and framebuffer pipeline
Primitive processing, transformations with *API-specific* matrix conventions, viewport/clip, lighting, flat/Gouraud shading, texture sampling, fog, depth/alpha/color, color-indexed visuals, framebuffer and overlays. Reproduce plane and colormap behavior where observable. Separate image planes, overlay planes, transparent overlay indices, depth and alpha storage, HP Color Recovery only where actual algorithm documented (generic dithering is not equivalent).

## API adapters
- **Starbase:** documented device init/state, primitives, transforms, colors, fills, texturing/lighting where applicable, clipping, image transfers, device/state queries, errors. Historical names/signatures exactly when exposed; optional clean Java facade separate.
- **PHIGS:** workstation and structure store, hierarchy, structure editing/traversal, state inheritance, transforms, attributes, retained-mode updates, view/lighting. Do not flatten into stateless triangle draws before semantic validation.
- **PEX:** renderer/pipeline contexts, structure networks, graphics resources, X11 resource semantics; implement a host abstraction without claiming full PEX wire protocol or X server emulation.
- **HP-UX OpenGL:** actual OpenGL revision/extensions/limits and hardware/software fallback by device. Native OpenGL backend may provide execution but must preserve profile limits.

## Shader and Minecraft relationship
VISUALIZE profiles constrain URD rendering resources/capabilities and historical API behavior; they do not parse shaders. Minecraft chunk meshes, entities and textures enter through independent host adapters; atlas handling, lightmaps, animation, batching and transparency preserved. Do not invent 'VISUALIZE shading language'.

## GPU/CPU backend
GLSL backend reproduces supported semantics, with multipass/framebuffers where needed; deterministic software reference covers transforms, clipping, depth, lighting, texture, colormap and overlays. Report limits and numerical undefined/implementation-dependent differences. No arbitrary FPS throttling to imitate historical device speed.

## Tests
Per-device caps validation, initial state, Starbase/PHIGS/PEX retained behavior, indexed color and overlays, depth/alpha precision, light/material, optional textures and software fallbacks. Compare against original HP-UX VISUALIZE if available. Each device/API should be individually marked partial or conformant; no blanket 'HP VISUALIZE emulated'.
