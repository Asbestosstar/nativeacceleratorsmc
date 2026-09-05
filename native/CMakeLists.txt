cmake_minimum_required(VERSION 3.20)
project(nativeaccelerator C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Reference/distribution builds can keep normal symbol visibility and debug
# information so the bundled native binaries remain inspectable. This is not
# required for correctness; it is enabled by scripts/build-native.sh.
option(NA_REFERENCE_SYMBOLS "Keep native symbols visible for reference/debugging" OFF)

add_library(nativeaccelerator SHARED
    src/common/native_accelerator.c
    src/common/platform_dispatch.c
    src/common/endian.c
    src/kernels/packed_bits.c
    src/kernels/quad.c
    src/kernels/image.c
    src/kernels/noise.c
)

target_include_directories(nativeaccelerator PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

# Optional Vulkan renderer companion library. It deliberately has no compile-time
# dependency on a particular window system or Vulkan SDK: the bootstrap probes
# a system Vulkan loader dynamically, and higher Vulkan objects are added behind
# this independent renderer ABI.
add_library(nativeaccelerator_renderer_vulkan SHARED
    src/renderer/common/renderer.c
    src/renderer/common/context.c
    src/renderer/common/arena.c
    src/renderer/common/scene.c
    src/renderer/common/voxel_mesh.c
    src/renderer/common/pipeline_cache.c
    src/renderer/vulkan/vk_loader.c
)

target_include_directories(nativeaccelerator_renderer_vulkan PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/renderer/common
)

if(WIN32)
    target_sources(nativeaccelerator_renderer_vulkan PRIVATE src/renderer/jobs/jobs_serial.c)
    target_compile_definitions(nativeaccelerator_renderer_vulkan PRIVATE NAR_NATIVE_WORKERS=0)
else()
    find_package(Threads REQUIRED)
    target_sources(nativeaccelerator_renderer_vulkan PRIVATE src/renderer/jobs/jobs_posix.c)
    target_link_libraries(nativeaccelerator_renderer_vulkan PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
    target_compile_definitions(nativeaccelerator_renderer_vulkan PRIVATE NAR_NATIVE_WORKERS=1)
endif()

if(MSVC)
    target_compile_options(nativeaccelerator_renderer_vulkan PRIVATE /O2)
else()
    if(NA_REFERENCE_SYMBOLS)
        target_compile_options(nativeaccelerator_renderer_vulkan PRIVATE -O3)
    else()
        target_compile_options(nativeaccelerator_renderer_vulkan PRIVATE -O3 -fvisibility=hidden)
    endif()
endif()

set_target_properties(nativeaccelerator_renderer_vulkan PROPERTIES OUTPUT_NAME "nativeaccelerator_renderer_vulkan")

string(TOLOWER "${CMAKE_SYSTEM_NAME}" NA_SYSTEM)
string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" NA_PROCESSOR)

include(TestBigEndian)
test_big_endian(NA_BIG_ENDIAN)
if(NA_BIG_ENDIAN)
    set(NA_ENDIAN_TAG "be")
else()
    set(NA_ENDIAN_TAG "le")
endif()
file(WRITE "${CMAKE_BINARY_DIR}/nativeaccelerator-endian.txt" "${NA_ENDIAN_TAG}\n")

# CPU architecture selection.  NA_ENDIAN_QUALIFIED is deliberately separate
# from actual host byte order: fixed-endian architecture IDs do not need an
# extra resource-name suffix, while architectures that can appear in either
# byte order do.
set(NA_ENDIAN_QUALIFIED FALSE)
if(NA_PROCESSOR MATCHES "^(x86_64|amd64|x64)$")
    set(NA_ARCH_ID "amd64")
    target_sources(nativeaccelerator PRIVATE src/arch/amd64/arch_amd64.c)
elseif(NA_PROCESSOR MATCHES "sparc|sun4")
    set(NA_ARCH_ID "sparcv9")
    set(NA_ENDIAN_QUALIFIED TRUE)
    target_sources(nativeaccelerator PRIVATE src/arch/sparc/arch_sparc.c)
elseif(NA_PROCESSOR MATCHES "^(ia64|itanium)$")
    set(NA_ARCH_ID "ia64")
    target_sources(nativeaccelerator PRIVATE src/arch/ia64/arch_ia64.c)
elseif(NA_PROCESSOR MATCHES "^(ppc|powerpc|ppc32|powerpc32)$")
    set(NA_ARCH_ID "ppc32")
    set(NA_ENDIAN_QUALIFIED TRUE)
    target_sources(nativeaccelerator PRIVATE src/arch/ppc32/arch_ppc32.c)
elseif(NA_PROCESSOR MATCHES "^(ppc64le|powerpc64le)$")
    # The architecture ID already encodes little-endian, so no extra -le tag.
    set(NA_ARCH_ID "ppc64le")
    target_sources(nativeaccelerator PRIVATE src/arch/generic/arch_generic.c)
elseif(NA_PROCESSOR MATCHES "^(ppc64|powerpc64)$")
    set(NA_ARCH_ID "ppc64")
    set(NA_ENDIAN_QUALIFIED TRUE)
    target_sources(nativeaccelerator PRIVATE src/arch/generic/arch_generic.c)
elseif(NA_PROCESSOR MATCHES "^(aarch64|arm64)$")
    set(NA_ARCH_ID "arm64")
    set(NA_ENDIAN_QUALIFIED TRUE)
    target_sources(nativeaccelerator PRIVATE src/arch/generic/arch_generic.c)
else()
    set(NA_ARCH_ID "${NA_PROCESSOR}")
    target_sources(nativeaccelerator PRIVATE src/arch/generic/arch_generic.c)
endif()

if(NA_ENDIAN_QUALIFIED)
    set(NA_RESOURCE_ARCH "${NA_ARCH_ID}-${NA_ENDIAN_TAG}")
else()
    set(NA_RESOURCE_ARCH "${NA_ARCH_ID}")
endif()
file(WRITE "${CMAKE_BINARY_DIR}/nativeaccelerator-resource-arch.txt" "${NA_RESOURCE_ARCH}\n")

# Shared POSIX source is composed with each Unix-family backend. Linux uses the
# same POSIX implementation as the BSDs, macOS and Solaris; Linux-specific code
# should only contain behavior that actually differs from POSIX.
set(NA_POSIX_SYSTEM FALSE)
if(NA_SYSTEM MATCHES "^(linux|freebsd|netbsd|openbsd|darwin|macos|aix|hp-ux|hpux)$" OR
   NA_SYSTEM MATCHES "sunos|solaris|illumos")
    set(NA_POSIX_SYSTEM TRUE)
    target_sources(nativeaccelerator PRIVATE src/os/posix/os_posix.c)
endif()

# Operating-system selection, independent of architecture.
if(NA_SYSTEM MATCHES "^linux$")
    target_sources(nativeaccelerator PRIVATE src/os/linux/os_linux.c)
elseif(NA_SYSTEM MATCHES "sunos|solaris|illumos")
    target_sources(nativeaccelerator PRIVATE src/os/solaris/os_solaris.c)
    if(NOT MSVC)
        target_link_libraries(nativeaccelerator PRIVATE dl)
    endif()
elseif(NA_SYSTEM MATCHES "^freebsd$")
    target_sources(nativeaccelerator PRIVATE src/os/bsd/os_bsd.c src/os/freebsd/os_freebsd.c)
elseif(NA_SYSTEM MATCHES "^netbsd$")
    target_sources(nativeaccelerator PRIVATE src/os/bsd/os_bsd.c src/os/netbsd/os_netbsd.c)
elseif(NA_SYSTEM MATCHES "^openbsd$")
    target_sources(nativeaccelerator PRIVATE src/os/bsd/os_bsd.c src/os/openbsd/os_openbsd.c)
elseif(NA_SYSTEM MATCHES "^(darwin|macos)$")
    target_sources(nativeaccelerator PRIVATE src/os/macos/os_macos.c)
elseif(NA_SYSTEM MATCHES "windows|mingw|msys")
    target_sources(nativeaccelerator PRIVATE src/os/windows/os_windows.c)
elseif(NA_POSIX_SYSTEM)
    target_sources(nativeaccelerator PRIVATE src/os/posix/os_posix_backend.c)
else()
    target_sources(nativeaccelerator PRIVATE src/os/generic/os_generic.c)
endif()

if(MSVC)
    target_compile_options(nativeaccelerator PRIVATE /O2)
else()
    if(NA_REFERENCE_SYMBOLS)
        target_compile_options(nativeaccelerator PRIVATE -O3)
    else()
        target_compile_options(nativeaccelerator PRIVATE -O3 -fvisibility=hidden)
    endif()
endif()

set_target_properties(nativeaccelerator PROPERTIES OUTPUT_NAME "nativeaccelerator")

message(STATUS "Native Accelerator OS: ${CMAKE_SYSTEM_NAME}")
message(STATUS "Native Accelerator arch: ${NA_ARCH_ID}")
message(STATUS "Native Accelerator endian: ${NA_ENDIAN_TAG}")
message(STATUS "Native Accelerator endian-qualified resource: ${NA_ENDIAN_QUALIFIED}")
message(STATUS "Native Accelerator resource arch: ${NA_RESOURCE_ARCH}")
message(STATUS "Native Accelerator POSIX layer: ${NA_POSIX_SYSTEM}")
message(STATUS "Native Accelerator reference symbols: ${NA_REFERENCE_SYMBOLS}")

include(CTest)
if(BUILD_TESTING)
    add_executable(nativeaccelerator_kernel_tests tests/kernel_tests.c)
    target_include_directories(nativeaccelerator_kernel_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    target_link_libraries(nativeaccelerator_kernel_tests PRIVATE nativeaccelerator)
    add_test(NAME nativeaccelerator-kernels COMMAND nativeaccelerator_kernel_tests)

    add_executable(nativeaccelerator_renderer_tests tests/renderer_tests.c)
    target_include_directories(nativeaccelerator_renderer_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    target_link_libraries(nativeaccelerator_renderer_tests PRIVATE nativeaccelerator_renderer_vulkan)
    add_test(NAME nativeaccelerator-renderer COMMAND nativeaccelerator_renderer_tests)
endif()
