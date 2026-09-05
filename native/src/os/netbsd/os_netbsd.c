#include "native_accelerator.h"

uint64_t na_os_capabilities(void) {
    return na_posix_capabilities() | na_bsd_capabilities() | NA_CAP_NETBSD;
}

const char *na_os_backend_name(void) {
    return "netbsd-posix";
}
