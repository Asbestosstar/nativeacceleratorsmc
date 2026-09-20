# Metal regression rollback fix30

Fix29 introduced an immediate startup visual regression: the Mojang logo disappeared and the title
panorama became black, while both were visible on the verified clean fix28 baseline.

Fix30 is built directly from clean fix28 and rolls back all fix29 state changes that can affect an
ordinary textured draw:
- front face is again SDL_GPU_FRONTFACE_COUNTER_CLOCKWISE;
- no fix29 depth/no-depth pipeline variants;
- no constructor-time render-area scissor;
- no fix29 sampler rewrite.

The optimized atlas and the active MSL resource-layout generator are unchanged.

The only retained fix29 idea is the isolated regional color+depth clear correction. A partial clear
cannot be represented by attachment LOADOP_CLEAR because that clears an attachment rather than one GUI
slot. For the RGBA8_UNORM + D32_FLOAT GUI item target, fix30 uploads just the requested subregion.
It reports `Metal regional clear marker: subresource-upload-fix30` the first time this path is used.
