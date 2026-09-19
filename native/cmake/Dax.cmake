# Optional Solaris libdax support.
#
# Usage from native/CMakeLists.txt after creating the main library target:
#   include(cmake/Dax.cmake)
#   na_enable_solaris_dax(nativeaccelerator)
#
# Keep this optional. Non-Solaris targets, and Solaris toolchains without the
# committed libdax development interface, must continue to build unchanged.
function(na_enable_solaris_dax target_name)
    if(NOT CMAKE_SYSTEM_NAME STREQUAL "SunOS")
        return()
    endif()

    include(CheckIncludeFile)
    find_package(Threads REQUIRED)
    check_include_file(dax.h NA_HAVE_DAX_H)
    find_library(NA_DAX_LIBRARY dax)

    if(NA_HAVE_DAX_H AND NA_DAX_LIBRARY)
        target_sources(${target_name} PRIVATE
            ${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../src/os/solaris/dax_int.c)
        target_include_directories(${target_name} PRIVATE
            ${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../include)
        target_link_libraries(${target_name} PRIVATE ${NA_DAX_LIBRARY} Threads::Threads)
        target_compile_definitions(${target_name} PRIVATE NA_HAVE_LIBDAX=1)
        message(STATUS "Native Accelerator: Solaris libdax scan/select enabled")
    else()
        message(STATUS "Native Accelerator: Solaris libdax not found; DAX extension omitted")
    endif()
endfunction()
