#include "native_accelerator.h"

/* Shared BSD-family behavior layered on top of the common POSIX code. */
uint64_t na_bsd_capabilities(void) {
    return NA_CAP_BSD;
}
