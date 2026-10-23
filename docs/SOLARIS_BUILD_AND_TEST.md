# Solaris / illumos build and test guide

This guide covers building Native Accelerator and running the Vulkan harnesses on Solaris or illumos
(AMD64 and SPARCv9). It lists every environment variable the scripts and native code read, and which
of them must be **exported** so the test processes see them.

Status: the Solaris loader probe has not yet passed end to end. The most recent Solaris failure was
`ExceptionInInitializerError` from LWJGL's `DynamicLinkLoader.ndlopen` binding; the probe now prints
the full cause chain. Treat this guide as the procedure for reproducing that result, not as proof
that Vulkan works on Solaris.

## 1. Requirements

| Item | Requirement |
| --- | --- |
| JDK | JDK 25 or newer (`maven.compiler.release` is 25). Set `JAVA_HOME` to it. |
| Maven | `mvn` on `PATH`, or set `MVN=/path/to/mvn`. |
| Python | `python3` on `PATH` (used to build the Minecraft LWJGL classpath). |
| C toolchain | `cc`/`gcc` and `cmake` for `scripts/build-native.sh`. |
| Minecraft | A launched Minecraft 26.3 install (Fabric 0.19.5 profile is preferred). The harnesses read its LWJGL jars and natives; they never use LWJGL from `~/.m2`. |
| Vulkan | A Vulkan 1.3 loader (`libvulkan.so.1`) and a Vulkan driver (Mesa lavapipe or a vendor ICD) built for the **same** ABI as the JVM. |

Check the ABI first. A 64-bit JVM needs 64-bit libraries:

```sh
java -XshowSettings:properties -version 2>&1 | grep -E 'os.arch|sun.arch.data.model'
elfdump -e /opt/vulkan-1.3.250/lib/sparcv9/libvulkan.so.1 | grep -E 'ELFCLASS|ELFDATA'
```

On SPARC the JVM reports `sparcv9` / `sparc64`, and the loader must be `ELFCLASS64 ELFDATA2MSB`.

## 2. Build

```sh
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"

# Java classes and the Minecraft compile dependency
./scripts/run-vulkan-loader-probe.sh      # compiles main/test sources as a side effect

# Native library (optional for the Java loader probe, required for the native renderer)
NA_VULKAN_LIBRARY_PATH=/opt/vulkan-1.3.250/lib/sparcv9 \
NA_VULKAN_LIBRARY_NAME=libvulkan.so.1 \
./scripts/build-native.sh
```

`scripts/build-native.sh` uses `RelWithDebInfo` by default. Override with `NA_BUILD_TYPE`. The
`NA_*` values are configure-time hints only; they add no link-time Vulkan dependency.

## 3. Environment variables

### 3.1 Must be exported to the test process

Variables are only seen by programs started from the shell that exported them. Setting one as a
prefix on `./scripts/...` works for that single command. Setting it only during the Maven build does
not affect the runtime.

| Variable | Purpose | Example (SPARCv9) |
| --- | --- | --- |
| `JAVA_HOME` | JDK 25 used by Maven and the harness. | `/opt/jdk-25` |
| `LD_LIBRARY_PATH_64` | Solaris runtime linker search path for **64-bit** objects. Needed so the 64-bit JVM finds the 64-bit `libvulkan.so.1`. Plain `LD_LIBRARY_PATH` is ignored for 64-bit lookups. | `/opt/vulkan-1.3.250/lib/sparcv9` |
| `LD_LIBRARY_PATH` | Fallback for 32-bit objects and LWJGL helper libraries. Set it only when a 32-bit object or a native dependency needs it. | `/opt/vulkan-1.3.250/lib` |
| `VULKAN_LIBRARY_PATH` | Directory holding the loader. Read by the native loader and the CMake configure step. Never passed directly to `dlopen`. | `/opt/vulkan-1.3.250/lib/sparcv9` |
| `MC_VULKAN_LIBRARY_NAME` | Loader file name, or an absolute path. Read by the native loader. | `libvulkan.so.1` |
| `NATIVE_ACCELERATOR_VULKAN_LIBRARY` | Highest-precedence explicit loader path or name. | `/opt/vulkan-1.3.250/lib/sparcv9/libvulkan.so.1` |
| `VK_DRIVER_FILES` | Vulkan 1.3 loader ICD manifest list, consumed by the Vulkan loader. Existing values are never overwritten by Native Accelerator. | `/opt/mesa-lavapipe-21.3.8/share/vulkan/icd.d/lvp_icd.sun4v.json` |
| `VK_ICD_FILENAMES` | Older name for the same ICD list; honored by the loader. Set **either** this or `VK_DRIVER_FILES`, not both with different paths. | same as above |
| `VK_LAYER_PATH` | Directory searched for implicit Vulkan layers, if any are used. Not needed for the loader probe. | `/opt/vulkan-1.3.250/share/vulkan/explicit_layer.d` |
| `NATIVE_ACCELERATOR_MESA_PREFIX` | Mesa tree root to select when no ICD variable is set. | `/opt/mesa-lavapipe-21.3.8` |
| `NATIVE_ACCELERATOR_MESA_SEARCH_PATH` | Colon-separated roots scanned for Mesa trees. A root contributes itself and its immediate subdirectories. | `/opt:/opt/local` |
| `NATIVE_ACCELERATOR_MESA_PIN` | `1` forces `VK_DRIVER_FILES`/`VK_ICD_FILENAMES` from the selected tree. | `1` |
| `NATIVE_ACCELERATOR_MESA_NO_DEFAULTS` | Set to any value to ignore built-in Mesa prefixes and search roots. | `1` |
| `NATIVEACCELERATOR_MINECRAFT_HOME` | Minecraft install whose `libraries/` and `versions/` are read. Defaults to `$HOME/.minecraft`. | `/export/home/solaris/.minecraft` |
| `NATIVEACCELERATOR_MINECRAFT_NATIVES` | Exact LWJGL natives directory. Overrides the search. | `$HOME/.minecraft/versions/26.3/natives/lwjgl/3.4.3/sparc64` |

Minimal export block for the loader probe on SPARCv9:

```sh
export JAVA_HOME=/opt/jdk-25
export LD_LIBRARY_PATH_64=/opt/vulkan-1.3.250/lib/sparcv9
export VULKAN_LIBRARY_PATH=/opt/vulkan-1.3.250/lib/sparcv9
export MC_VULKAN_LIBRARY_NAME=libvulkan.so.1
export VK_DRIVER_FILES=/opt/mesa-lavapipe-21.3.8/share/vulkan/icd.d/lvp_icd.sun4v.json
export NATIVEACCELERATOR_MINECRAFT_HOME="$HOME/.minecraft"

./scripts/run-vulkan-loader-probe.sh
```

Use the `sun4v` (or matching) ICD file that is present in your Mesa tree. Run
`ls /opt/mesa-lavapipe-21.3.8/share/vulkan/icd.d/` to see the actual name.

### 3.2 Harness and build controls

| Variable | Purpose |
| --- | --- |
| `MVN` | Maven executable. Default `mvn`. |
| `MAVEN_REPO_LOCAL` / `MAVEN_REPO` | Maven local repository for the project build. Does not select LWJGL at runtime. |
| `MAVEN_OFFLINE=1` | Pass `-o` to Maven. |
| `SKIP_MINECRAFT_BOOTSTRAP=1` | Skip provisioning `target/minecraft/client.jar`. Only valid when it already exists. |
| `HARNESS_JAVA` | Java executable for harness runs. Defaults to `$JAVA`, then `$JAVA_HOME/bin/java`, then `java` on `PATH`. |
| `JAVA` | Alternative to `HARNESS_JAVA`, checked first. |
| `LWJGL_NATIVES_CLASSIFIER` | Maven natives classifier for the optional compiler smoke profile only. Not used by the runtime. |

### 3.3 Native Accelerator properties (passed with `-D`, not environment)

| Property | Purpose |
| --- | --- |
| `-Dnativeaccelerator.vulkan.loaderOnly=true` | Loader-only probe; creates no instance or device. Used by `run-vulkan-loader-probe.sh`. |
| `-Dorg.lwjgl.librarypath=<dir>` | Natives directory for LWJGL. Process-wide; must contain only compatible natives. |
| `-Dorg.lwjgl.util.Debug=true` | Prints which LWJGL native library was loaded and from where. Keep on while diagnosing. |
| `-Dorg.lwjgl.vulkan.libname=<file>` | Overrides the LWJGL Vulkan library name. Takes precedence over the environment. |

### 3.4 Variables that do not apply on Solaris

`MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS`, `NATIVE_ACCELERATOR_VULKAN_PROBE_ISOLATED`,
`NATIVEACCELERATOR_GPU_EVIDENCE`, and the `MoltenVK` settings are macOS-only. Leave them unset.

## 4. Verify the loader before running the probe

```sh
# Which Vulkan loader will the dynamic linker pick, and is it fully resolved?
LD_LIBRARY_PATH_64=$LD_LIBRARY_PATH_64 ldd "$VULKAN_LIBRARY_PATH/libvulkan.so.1" | grep -i 'not found' || echo "all dependencies resolved"

# Which ICD manifest is in effect?
echo "VK_DRIVER_FILES=$VK_DRIVER_FILES VK_ICD_FILENAMES=${VK_ICD_FILENAMES:-}"

# Which LWJGL natives and jars does the harness use?
./scripts/run-vulkan-loader-probe.sh 2>&1 | grep 'minecraft_classpath: LWJGL'
```

The `minecraft_classpath: LWJGL` lines must point under `NATIVEACCELERATOR_MINECRAFT_HOME/libraries`.
If any line points into `~/.m2`, stop and report it.

## 5. Run the tests

```sh
./scripts/run-vulkan-loader-probe.sh          # loader-only; must print PASS or BLOCKED with a reason
./scripts/run-native-vulkan-headless-probe.sh # instance/device probe; diagnostic on Solaris
./scripts/run-vulkan-smoke.sh                 # Vulkan smoke test
```

Expected results on a host without a usable driver are `BLOCKED` or `DIAGNOSTIC` lines, not
silent success.

## 6. Diagnosing a failure

If the loader probe reports `FAIL (... <- ...)`, the text after the arrow is the underlying cause. For
example, `UnsatisfiedLinkError: 'long org.lwjgl.system.linux.DynamicLinkLoader.ndlopen(long, int)'`
means LWJGL's Linux `dlopen` binding did not link on this JVM/OS combination. Collect:

```sh
java -XshowSettings:properties -version 2>&1
elfdump -Ld "$VULKAN_LIBRARY_PATH/libvulkan.so.1" | head -40
ldd "$VULKAN_LIBRARY_PATH/libvulkan.so.1"
uname -a; isainfo -kv
```

Paste the output with the exact `NativeVulkanDeviceBootstrapTest:` line.
