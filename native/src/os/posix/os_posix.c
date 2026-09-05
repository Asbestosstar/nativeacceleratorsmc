#include "native_accelerator.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>

/*
 * Shared Unix/POSIX layer. Linux, Solaris/illumos, BSD, macOS, AIX and HP-UX
 * should put duplicated Unix behavior here and keep their os/<name> files for
 * facilities that genuinely differ.
 */
uint64_t na_posix_capabilities(void) {
    return NA_CAP_POSIX;
}

size_t na_posix_page_size(void) {
    long size = sysconf(_SC_PAGESIZE);
    return size > 0 ? (size_t)size : (size_t)4096;
}

uint64_t na_posix_monotonic_nanos(void) {
#if defined(CLOCK_MONOTONIC)
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        return (uint64_t)ts.tv_sec * UINT64_C(1000000000) + (uint64_t)ts.tv_nsec;
    }
#endif
    return 0;
}
