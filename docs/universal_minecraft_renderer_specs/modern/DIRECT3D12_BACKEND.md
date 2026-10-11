# Direct3D 12 renderer backend and HLSL/DXIL execution

## Sources
Microsoft Direct3D 12 docs https://learn.microsoft.com/en-us/windows/win32/direct3d12/direct3d-12-graphics ; DXC https://github.com/microsoft/DirectXShaderCompiler . Query supported feature levels/shader models, DXIL validation requirements and exact API availability.

## Device/host
Windows native bridge, DXGI adapter selection/factory/swapchain, D3D12 device/queues/command allocator/command list, fences, root signatures, descriptor heaps, heaps/resources, resource states/barriers, PSOs, render targets, depth-stencil, uploads/readbacks, device removal/recovery, HDR/sRGB/MSAA and window resize.

## Shaders
Compile HLSL to DXIL via DXC, preserve target shader model and root signature/resource layout; other frontends lower to HLSL/DXIL only when semantics permit. Validate generated DXIL/PSO/reflection; adapter generates legal registers/spaces and resource transitions. Explicitly handle D3D clip-space/depth, texture coordinates and matrix semantics.

## Acceptance
Independent D3D12 scene, zero debug-layer errors where applicable, resource lifetime tests, shader reflection, repeated resize and lost-device recovery, material tests, Minecraft complete render path verification. Unsupported Windows/non-D3D hosts fail with clear diagnostic.
