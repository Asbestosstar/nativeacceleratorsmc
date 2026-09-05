#include "native_accelerator.h"

/*
 * Windows is deliberately a first-class non-POSIX backend even before its
 * optimized implementation exists. Do not route Windows through os/posix.
 * Future work belongs here: VirtualAlloc, QueryPerformanceCounter, processor
 * group/topology APIs, DLL discovery, and Windows-specific native dispatch.
 */
uint64_t na_os_capabilities(void) {
    return NA_CAP_WINDOWS;
}

const char *na_os_backend_name(void) {
    return "windows";
}
