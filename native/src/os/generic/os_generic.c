#include "native_accelerator.h"

uint64_t na_os_capabilities(void) {
    return 0;
}

const char *na_os_backend_name(void) {
    return "generic-os";
}
