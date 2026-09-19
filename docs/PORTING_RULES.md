# Porting rules

1. **Architecture is not operating system.** SIMD/instruction-set kernels go in `arch/<cpu>`.
2. **POSIX is not Linux.** Reusable Unix behavior goes in `os/posix`; Linux-only behavior goes in `os/linux`.
3. **BSD is a family layer, not three copies.** Shared BSD behavior goes in `os/bsd`; FreeBSD, NetBSD and OpenBSD deltas stay in their own directories.
4. **macOS is POSIX plus Darwin/Mach behavior.** Common calls stay in `os/posix`; Mach, sysctl and old-PPC-Darwin differences stay in `os/macos`.
5. **Windows is not a POSIX fallback.** Windows APIs remain in `os/windows`.
6. **Always know host byte order, but do not always put it in the filename.** Runtime conversion uses `na_host_endian()` and the endian helpers.
7. **Only endian-variable architecture IDs need `-le`/`-be` resource suffixes.** AMD64 and IA-64 use plain IDs; `ppc64le` already encodes its order.
8. **Bi-endian capability is not current byte order.** `NA_CAP_BIENDIAN_ARCH` describes an architecture family; `NA_CAP_BIG_ENDIAN`/`NA_CAP_LITTLE_ENDIAN` describe this process.
9. **Keep rare architectures explicit.** IA-64 and PPC32 have their own backends even while their first kernels are scalar.
10. **Keep the C ABI loader-neutral.** Panama and future JNI adapters must call the same native core.
11. **Prefer runtime dispatch over whole-library ISA flags.** A native library should start safely on the oldest supported CPU and select AVX/VIS/AltiVec/etc. only after detection.
