# Authoritative references and cautionary notes

## Modern specifications
- Khronos OpenGL/GLSL registry: https://registry.khronos.org/OpenGL/
- GLSL 4.60 HTML: https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.html
- Vulkan registry: https://registry.khronos.org/vulkan/
- Vulkan specification: https://registry.khronos.org/vulkan/specs/latest/html/vkspec.html
- SPIR-V registry: https://registry.khronos.org/SPIR-V/
- Apple Metal resources/MSL specification: https://developer.apple.com/metal/resources/
- Microsoft Direct3D12: https://learn.microsoft.com/en-us/windows/win32/direct3d12/direct3d-12-graphics
- DXC source and docs: https://github.com/microsoft/DirectXShaderCompiler
- W3C WGSL specification: https://www.w3.org/TR/WGSL/
- OSL project: https://github.com/AcademySoftwareFoundation/OpenShadingLanguage

## Historical sources
- SGIX registry: https://registry.khronos.org/OpenGL/extensions/SGIX/
- ARB programs (find within official ARB registry): https://registry.khronos.org/OpenGL/extensions/ARB/
- NVIDIA NV extensions: https://registry.khronos.org/OpenGL/extensions/NV/
- SGI ISL 3.0 historical manual (mirror; verify document edition): https://www.infania.net/misc1/sgi_techpubs/techpubs/007-4648-001.pdf
- HP-UX Graphics Administration Guide: https://h10032.www1.hp.com/ctg/Manual/lpv38781.pdf
- HP VISUALIZE hardware overview (secondary): https://www.openpa.net/pa-risc_graphics_visualize-fx.html

## Research requirements
- Check actual document title/version against PDF content; mirrored manuals and informal sources are not automatically authoritative.
- For official ISL 4.0 or later, require a verified original document before adding an `ISL_4_0_OFFICIAL` profile.
- Not all SGIX registry entries are completed implementable specifications. In particular `SGIX_fragment_specular_lighting` is an unfinished proposal.
- For Cg/RSL/Starbase/PHIGS/PEX/graPHIGS/GDDM, gather specific original editions before claiming exact API or language conformance.
- Do not redistribute copyrighted manuals/toolchains in this archive absent license permission. Links only.
