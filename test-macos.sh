#!/bin/sh
# test-macos.sh -- build Native Accelerator, install it into the real Minecraft
# folder, and launch Minecraft (Fabric Loader 0.19.5).
#
# Every JVM/game argument below is taken verbatim from the TLauncher launch
# command captured in tl console.log for the fabric-loader-0.19.5-26.3 install,
# so the game starts exactly the way the launcher starts it.
#
# Usage:
#   ./test-macos.sh                 build, install, bundle natives, launch
#   SKIP_BUILD=1 ./test-macos.sh    reuse the existing target jar
#   NATIVE=1 ./test-macos.sh        force a native rebuild (mvn -Pnative)
#   NATIVE=0 ./test-macos.sh        never rebuild native libs (Java-only jar)
# Default is NATIVE=auto: native libraries are rebuilt only when they are missing,
# so a fresh checkout works with a plain ./test-macos.sh.
#   DRY_RUN=1 ./test-macos.sh       print the launch command, do not launch
#   NO_BUNDLE=1 ./test-macos.sh     install the jar without the built .dylibs
#   JAVA=/path/to/java ./test-macos.sh
#   MC_DIR=/path/to/minecraft ./test-macos.sh
#   EXTRA_JVM_ARGS="-Dfoo=bar -Dbaz=1" ./test-macos.sh

set -eu

# ---- configuration -------------------------------------------------------
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MC_DIR=${MC_DIR:-'/Users/macbook/Library/Application Support/minecraft'}
MODS_DIR="$MC_DIR/mods"
MOD_JAR="$PROJECT_DIR/target/native-accelerator-0.1.0-SNAPSHOT.jar"
MODS_JAR="$MODS_DIR/native-accelerator.jar"
NATIVE_BUILD_DIR="$PROJECT_DIR/target/native-build"
JAVA=${JAVA:-'/Users/macbook/Library/Application Support/minecraft/runtime/java-runtime-epsilon/osx/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java'}

# ---- 1. build the mod ----------------------------------------------------
if [ "${SKIP_BUILD:-0}" != "1" ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "test-macos.sh: mvn not found (use SKIP_BUILD=1 to reuse a jar)" >&2
        exit 1
    fi
    echo "[test-macos] building Native Accelerator..."
    # Default (NATIVE=auto) rebuilds the native libraries when they are missing so a
    # fresh checkout works with a plain ./test-macos.sh; NATIVE=1 forces a rebuild and
    # NATIVE=0 skips it to ship a Java-only jar.
    if [ "${NATIVE:-auto}" = "1" ] || { [ "${NATIVE:-auto}" = "auto" ] && [ ! -d "$NATIVE_BUILD_DIR" ]; }; then
        ( cd "$PROJECT_DIR" && mvn -q -DskipTests -Pnative package )
    else
        ( cd "$PROJECT_DIR" && mvn -q -DskipTests package )
    fi
fi

if [ ! -f "$MOD_JAR" ]; then
    echo "test-macos.sh: build artifact missing: $MOD_JAR" >&2
    exit 1
fi

if [ ! -d "$MC_DIR" ]; then
    echo "test-macos.sh: Minecraft folder not found: $MC_DIR" >&2
    exit 1
fi

# ---- 2. stage the mod jar and bundle the built native libraries ----------
# Platform.current().nativeId() decides the resource folder inside the jar:
#   Intel  x86_64 -> macos-amd64        (fixed-endian id, no suffix)
#   Apple  arm64  -> macos-arm64-le     (endian-qualified id)
ARCH=$(uname -m)
case "$ARCH" in
    x86_64|amd64)        PLATFORM_ID=macos-amd64 ;;
    arm64|aarch64)       PLATFORM_ID=macos-arm64-le ;;
    *)                   PLATFORM_ID=macos-$ARCH ;;
esac

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/nativeaccelerator-stage.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
( cd "$STAGE" && jar xf "$MOD_JAR" )

BUNDLED=0
if [ "${NO_BUNDLE:-0}" != "1" ] && [ -d "$NATIVE_BUILD_DIR" ]; then
    mkdir -p "$STAGE/META-INF/native/$PLATFORM_ID"
    for lib in "$NATIVE_BUILD_DIR"/*.dylib "$NATIVE_BUILD_DIR"/*/*.dylib; do
        [ -f "$lib" ] || continue
        cp -f "$lib" "$STAGE/META-INF/native/$PLATFORM_ID/"
        BUNDLED=$((BUNDLED + 1))
    done
    echo "[test-macos] bundled $BUNDLED native librar(y/ies) as META-INF/native/$PLATFORM_ID/"
else
    echo "[test-macos] no native libraries bundled; the mod will use its Java fallback"
fi

mkdir -p "$MODS_DIR"
rm -f "$MODS_DIR"/native-accelerator*.jar
( cd "$STAGE" && zip -q -r -X -FS "$MODS_JAR" . )
echo "[test-macos] installed $(basename "$MOD_JAR") -> $MODS_JAR"

# ---- 3. launch (arguments verbatim from tl console.log) ------------------
CP='/Users/macbook/Library/Application Support/minecraft/libraries/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar:/Users/macbook/Library/Application Support/minecraft/libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar:/Users/macbook/Library/Application Support/minecraft/libraries/at/yawk/lz4/lz4-java/1.10.1/lz4-java-1.10.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/ca/weblite/java-objc-bridge/1.1/java-objc-bridge-1.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/azure/azure-json/1.4.0/azure-json-1.4.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/github/oshi/oshi-core/6.9.0/oshi-core-6.9.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/ibm/icu/icu4j/78.3/icu4j-78.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/microsoft/azure/msal4j/1.24.1/msal4j-1.24.1.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/authlib/10.0.77/authlib-10.0.77.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/blocklist/1.0.10/blocklist-1.0.10.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/jtracy/1.14.38/jtracy-1.14.38-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/jtracy/1.14.38/jtracy-1.14.38-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/logging/1.7.12/logging-1.7.12.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/tlauncher/patchy/2.2.101/patchy-2.2.101.jar:/Users/macbook/Library/Application Support/minecraft/libraries/com/mojang/text2speech/1.19.12/text2speech-1.19.12.jar:/Users/macbook/Library/Application Support/minecraft/libraries/commons-codec/commons-codec/1.22.0/commons-codec-1.22.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-buffer/4.2.16.Final/netty-buffer-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-codec-base/4.2.16.Final/netty-codec-base-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-codec-compression/4.2.16.Final/netty-codec-compression-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-codec-http/4.2.16.Final/netty-codec-http-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-handler/4.2.16.Final/netty-handler-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-resolver/4.2.16.Final/netty-resolver-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-transport-classes-epoll/4.2.16.Final/netty-transport-classes-epoll-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-transport-classes-kqueue/4.2.16.Final/netty-transport-classes-kqueue-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-transport-native-kqueue/4.2.16.Final/netty-transport-native-kqueue-4.2.16.Final-osx-aarch_64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-transport-native-kqueue/4.2.16.Final/netty-transport-native-kqueue-4.2.16.Final-osx-x86_64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-transport-native-unix-common/4.2.16.Final/netty-transport-native-unix-common-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar:/Users/macbook/Library/Application Support/minecraft/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar:/Users/macbook/Library/Application Support/minecraft/libraries/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/apache/logging/log4j/log4j-api/2.26.0/log4j-api-2.26.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/apache/logging/log4j/log4j-core/2.26.0/log4j-core-2.26.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/apache/logging/log4j/log4j-slf4j2-impl/2.26.0/log4j-slf4j2-impl-2.26.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/jcraft/jorbis/0.0.17/jorbis-0.0.17.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/joml/joml/1.10.9/joml-1.10.9.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-freetype/3.4.3/lwjgl-freetype-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-freetype/3.4.3/lwjgl-freetype-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-freetype/3.4.3/lwjgl-freetype-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-jemalloc/3.4.3/lwjgl-jemalloc-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-jemalloc/3.4.3/lwjgl-jemalloc-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-jemalloc/3.4.3/lwjgl-jemalloc-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-openal/3.4.3/lwjgl-openal-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-openal/3.4.3/lwjgl-openal-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-openal/3.4.3/lwjgl-openal-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-opengl/3.4.3/lwjgl-opengl-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-opengl/3.4.3/lwjgl-opengl-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-opengl/3.4.3/lwjgl-opengl-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-shaderc/3.4.3/lwjgl-shaderc-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-shaderc/3.4.3/lwjgl-shaderc-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-shaderc/3.4.3/lwjgl-shaderc-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-spvc/3.4.3/lwjgl-spvc-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-spvc/3.4.3/lwjgl-spvc-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-spvc/3.4.3/lwjgl-spvc-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-stb/3.4.3/lwjgl-stb-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-stb/3.4.3/lwjgl-stb-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-stb/3.4.3/lwjgl-stb-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-vma/3.4.3/lwjgl-vma-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-vma/3.4.3/lwjgl-vma-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-vma/3.4.3/lwjgl-vma-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-vulkan/3.4.3/lwjgl-vulkan-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-vulkan/3.4.3/lwjgl-vulkan-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl-vulkan/3.4.3/lwjgl-vulkan-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-macos.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-macos-arm64.jar:/Users/macbook/Library/Application Support/minecraft/libraries/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar:/Users/macbook/Library/Application Support/minecraft/versions/fabric-loader-0.19.5-26.3/fabric-loader-0.19.5-26.3.jar'

if [ ! -x "$JAVA" ]; then
    JAVA=$(command -v java || true)
    if [ -z "$JAVA" ]; then
        echo "test-macos.sh: no java runtime found (set JAVA=...)" >&2
        exit 1
    fi
    echo "[test-macos] bundled runtime missing; falling back to $JAVA"
fi

set --
if [ -n "${EXTRA_JVM_ARGS:-}" ]; then
    set -- "$@" $EXTRA_JVM_ARGS
fi
set -- "$@" '-Xdock:icon="/Users/macbook/Library/Application Support/minecraft/assets/objects/f0/f00657542252858a721e715a2e888a9226404e35"'
set -- "$@" '-Xdock:name=Minecraft'
set -- "$@" '-Dfml.ignoreInvalidMinecraftCertificates=true'
set -- "$@" '-Dfml.ignorePatchDiscrepancies=true'
set -- "$@" '-Djava.net.preferIPv4Stack=true'
set -- "$@" '-XstartOnFirstThread'
set -- "$@" '-Xss1M'
set -- "$@" '-XX:StackShadowPages=32'
set -- "$@" '--enable-native-access=ALL-UNNAMED'
set -- "$@" '--add-exports'
set -- "$@" 'java.base/jdk.internal.misc=ALL-UNNAMED'
set -- "$@" '-Djava.library.path=/Users/macbook/Library/Application Support/minecraft/versions/fabric-loader-0.19.5-26.3/natives/java'
set -- "$@" '-Djna.tmpdir=/Users/macbook/Library/Application Support/minecraft/versions/fabric-loader-0.19.5-26.3/natives/jna'
set -- "$@" '-Dorg.lwjgl.system.SharedLibraryExtractPath=/Users/macbook/Library/Application Support/minecraft/versions/fabric-loader-0.19.5-26.3/natives/lwjgl'
set -- "$@" '-Dio.netty.native.workdir=/Users/macbook/Library/Application Support/minecraft/versions/fabric-loader-0.19.5-26.3/natives/netty'
set -- "$@" '-Dminecraft.launcher.brand=minecraft-launcher'
set -- "$@" '-Dminecraft.launcher.version=2.3.173'
set -- "$@" '-cp'
set -- "$@" "$CP"
set -- "$@" '-DFabricMcEmu= net.minecraft.client.main.Main'
set -- "$@" '-Xms2G'
set -- "$@" '-XX:+UseCompactObjectHeaders'
set -- "$@" '-XX:+AlwaysPreTouch'
set -- "$@" '-XX:+UseStringDeduplication'
set -- "$@" '-XX:+UseZGC'
set -- "$@" '-Dminecraft.applet.TargetDirectory=/Users/macbook/Library/Application Support/minecraft'
set -- "$@" '-DlibraryDirectory=/Users/macbook/Library/Application Support/minecraft/libraries'
set -- "$@" '-Dcountry=US'
set -- "$@" '-Dlog4j.configurationFile=/Users/macbook/Library/Application Support/minecraft/assets/log_configs/client-1.21.2.xml'
set -- "$@" '-Xmx10922M'
set -- "$@" 'net.fabricmc.loader.impl.launch.knot.KnotClient'
set -- "$@" '--username'
set -- "$@" 'dev'
set -- "$@" '--version'
set -- "$@" 'fabric-loader-0.19.5-26.3'
set -- "$@" '--gameDir'
set -- "$@" '/Users/macbook/Library/Application Support/minecraft'
set -- "$@" '--assetsDir'
set -- "$@" '/Users/macbook/Library/Application Support/minecraft/assets'
set -- "$@" '--assetIndex'
set -- "$@" '34'
set -- "$@" '--uuid'
set -- "$@" '4064c54eff93430c89aa4430b9cac138'
set -- "$@" '--accessToken'
set -- "$@" 'null'
set -- "$@" '--clientId'
set -- "$@" 'null'
set -- "$@" '--xuid'
set -- "$@" 'null'
set -- "$@" '--versionType'
set -- "$@" 'release'
set -- "$@" '--width'
set -- "$@" '925'
set -- "$@" '--height'
set -- "$@" '530'

if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "[test-macos] game dir: $MC_DIR"
    echo "[test-macos] java:     $JAVA"
    echo "[test-macos] command:"
    echo "  $JAVA"
    for arg in "$@"; do echo "  $arg"; done
    exit 0
fi

echo "[test-macos] launching Minecraft from $MC_DIR"
cd "$MC_DIR"
exec "$JAVA" "$@"
