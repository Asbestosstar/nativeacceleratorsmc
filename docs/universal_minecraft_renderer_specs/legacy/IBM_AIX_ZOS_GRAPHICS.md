# IBM AIX / z/OS graphics compatibility profiles

## Clarification
AIX is an operating system, not a shader language; historic AIX workstations exposed OpenGL and IBM graPHIGS/PEX-related interfaces on supported graphics subsystems. z/OS GDDM addresses host presentation/graphics functions and is not equivalent to Vulkan 3D. Avoid claiming a native 3D driver where none exists.

## Sources and research
Collect IBM AIX OpenGL programming guides for exact release, supported Power graphics devices/drivers, OpenGL/GLX versions/extensions and limitations. For graPHIGS collect workstation/network/structure/reference docs. For z/OS GDDM collect IBM manuals and determine raster/vector/text, device/workstation, batch/display/print semantics. Tag each claim with product release and document edition.

## Implementation
`AIXOpenGLCompatibilityProfile` constrains OpenGL-compatible URD backend capabilities; `GraPHIGSAdapter` handles retained-mode structures, transformations, views, lighting and workstations; `GDDMAdapter` handles appropriate 2D/chart/image graphics commands and rendering surfaces. If selected for Minecraft 3D, define **software-hosted translation** and state explicitly that it is not native GDDM GPU shading. Keep emulation separate from JVM/platform portability.

## Testing
API/structure/store tests, coordinates, colormaps, device output assumptions, screenshots and deterministic images, actual IBM platform testing only when access exists. Unsupported 3D features clearly fail or use separately identified modernization layer.
