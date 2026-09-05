#include "native_accelerator.h"

/* Linux-specific probes/syscalls go here; common Unix machinery stays in os/posix. */
uint64_t na_os_capabilities(void) {
    return na_posix_capabilities() | NA_CAP_LINUX;
}

const char *na_os_backend_name(void) {
    return "linux-posix";
}
