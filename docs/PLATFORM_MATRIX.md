# Platform matrix

Native Accelerator composes an operating-system backend, an architecture backend, shared endian helpers,
and the common kernel ABI. Endian-variable architectures use endian-qualified native resource paths.

| Platform | OS composition | Arch backend | Canonical resource ID | Current status |
|---|---|---|---|---|
| Linux AMD64 | `os/posix` + `os/linux` | `arch/amd64` | `linux-amd64` | scalar + AVX2 + AVX-512 XOR dispatch; ABI v3 kernels |
| Linux SPARCv9 | `os/posix` + `os/linux` | `arch/sparc` | `linux-sparcv9-be` normally | SPARC base; VIS kernels next |
| Solaris/illumos AMD64 | `os/posix` + `os/solaris` | `arch/amd64` | `solaris-amd64` | AVX dispatch + DAX discovery |
| Solaris/illumos SPARCv9 | `os/posix` + `os/solaris` | `arch/sparc` | `solaris-sparcv9-be` | SPARC base + DAX discovery |
| FreeBSD AMD64 | `os/posix` + `os/bsd` + `os/freebsd` | `arch/amd64` | `freebsd-amd64` | explicit BSD/FreeBSD backend + AVX |
| NetBSD AMD64 | `os/posix` + `os/bsd` + `os/netbsd` | `arch/amd64` | `netbsd-amd64` | explicit BSD/NetBSD backend + AVX |
| OpenBSD AMD64 | `os/posix` + `os/bsd` + `os/openbsd` | `arch/amd64` | `openbsd-amd64` | explicit BSD/OpenBSD backend + AVX |
| NetBSD SPARCv9 | `os/posix` + `os/bsd` + `os/netbsd` | `arch/sparc` | `netbsd-sparcv9-be` normally | explicit NetBSD + SPARC base |
| macOS AMD64 | `os/posix` + `os/macos` | `arch/amd64` | `macos-amd64` | explicit Darwin backend; AVX subject to compiler/OS support |
| macOS PPC32 | `os/posix` + `os/macos` | `arch/ppc32` | `macos-ppc32-be` normally | scalar PPC32 placeholder; AltiVec planned |
| Linux PPC32 | `os/posix` + `os/linux` | `arch/ppc32` | `linux-ppc32-be/le` | scalar PPC32 placeholder; AltiVec planned |
| Linux IA-64 | `os/posix` + `os/linux` | `arch/ia64` | `linux-ia64` | explicit scalar IA-64 placeholder |
| Windows AMD64 | `os/windows` | `arch/amd64` | `windows-amd64` | explicit non-POSIX Windows backend |
| Windows IA-64 | `os/windows` | `arch/ia64` | `windows-ia64` | explicit Windows + IA-64 composition |
| Haiku AMD64 | generic/portable OS fallback for now | `arch/amd64` | `haiku-amd64` | architecture acceleration available; OS specialization pending |

The common ABI v3 packed-bit, quad, image, and Perlin kernels build for every backend selected by CMake.
Hand-written architecture specialization should be introduced behind the same exported ABI rather than by
forking the Minecraft integration code.

PPC64/PPC64LE should eventually use one shared `arch/ppc64` source tree compiled into separate ABI/endian
binaries; do not create independent architecture implementations for BE and LE PowerPC64.


## Vulkan renderer eligibility

The Vulkan renderer is not tied to a platform allow-list. Every native target is eligible to build the
renderer companion library. At runtime it opens a Vulkan loader, queries the loader API version, creates a
minimal instance, and enumerates physical devices. If that probe succeeds, the client may proceed with the
renderer; otherwise the ordinary renderer remains active and the compute accelerator is unaffected.

This intentionally leaves Solaris/illumos SPARC/AMD64/ARM64, NetBSD and other BSD combinations, Linux,
Windows, and future architectures on the same code path. Platform-specific code should be introduced only
for real surface/window-system or driver differences.
