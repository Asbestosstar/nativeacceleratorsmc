#include "native_accelerator.h"

/* Generic Unix-family backend for POSIX systems without a more specific OS file yet. */
uint64_t na_os_capabilities(void) {
    return na_posix_capabilities();
}

const char *na_os_backend_name(void) {
    return "posix-generic";
}
