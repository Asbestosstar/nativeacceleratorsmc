#include "native_accelerator.h"

/*
 * macOS/Darwin-specific behavior belongs here.  Keep POSIX-compatible work in
 * os/posix so this file can focus on Mach APIs, sysctl CPU discovery and old
 * PowerPC Darwin differences when those implementations are added.
 */
uint64_t na_os_capabilities(void) {
    return na_posix_capabilities() | NA_CAP_MACOS;
}

const char *na_os_backend_name(void) {
    return "macos-posix";
}
