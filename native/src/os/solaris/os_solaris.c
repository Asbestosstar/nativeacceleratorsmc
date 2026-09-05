#include "native_accelerator.h"

#include <dlfcn.h>

/* Solaris/illumos-specific facilities; generic Unix behavior comes from os/posix. */
static int dax_library_available(void) {
    void *h = dlopen("libdax.so.1", RTLD_LAZY | RTLD_LOCAL);
    if (h == NULL) return 0;
    dlclose(h);
    return 1;
}

uint64_t na_os_capabilities(void) {
    uint64_t caps = na_posix_capabilities();
    if (dax_library_available()) caps |= NA_CAP_DAX_LIBRARY;
    return caps;
}

const char *na_os_backend_name(void) {
    return dax_library_available() ? "solaris-posix+dax-ready" : "solaris-posix";
}
