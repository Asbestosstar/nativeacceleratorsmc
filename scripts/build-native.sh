#!/bin/sh
set -eu

TARGET_DIR=${1:-target}
OUTPUT_DIR=${2:-target/classes}
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR="$TARGET_DIR/native-build"
BUILD_TYPE=${NA_BUILD_TYPE:-RelWithDebInfo}

normalize_os() {
    case "$(uname -s)" in
        Linux) echo linux ;;
        SunOS) echo solaris ;;
        FreeBSD) echo freebsd ;;
        NetBSD) echo netbsd ;;
        OpenBSD) echo openbsd ;;
        Darwin) echo macos ;;
        Haiku) echo haiku ;;
        MINGW*|MSYS*|CYGWIN*) echo windows ;;
        *) uname -s | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/-$//' ;;
    esac
}

find_objcopy() {
    for TOOL in objcopy gobjcopy llvm-objcopy; do
        if command -v "$TOOL" >/dev/null 2>&1; then
            command -v "$TOOL"
            return 0
        fi
    done
    return 1
}

OS=$(normalize_os)

# RelWithDebInfo keeps optimization enabled while also emitting source/line and
# local-symbol information. NA_REFERENCE_SYMBOLS disables hidden-by-default
# visibility for this reference-friendly distribution build. Nothing here
# invokes strip(1), and the original binary placed in the JAR stays unstripped.
cmake -S "$PROJECT_DIR/native" -B "$BUILD_DIR" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DNA_REFERENCE_SYMBOLS=ON
cmake --build "$BUILD_DIR" --config "$BUILD_TYPE" --parallel

# CMake owns architecture/endian normalization. This avoids shell and Java disagreeing
# about resource IDs such as sparcv9-be or ppc32-le.
RESOURCE_ARCH_FILE="$BUILD_DIR/nativeaccelerator-resource-arch.txt"
if [ ! -f "$RESOURCE_ARCH_FILE" ]; then
    echo "CMake did not emit $RESOURCE_ARCH_FILE" >&2
    exit 1
fi
RESOURCE_ARCH=$(tr -d '\r\n' < "$RESOURCE_ARCH_FILE")
PLATFORM="$OS-$RESOURCE_ARCH"
DEST="$OUTPUT_DIR/META-INF/native/$PLATFORM"
DEBUG_DEST="$OUTPUT_DIR/META-INF/native-debug/$PLATFORM"
mkdir -p "$DEST" "$DEBUG_DEST"

case "$OS" in
    macos) LIB="libnativeaccelerator.dylib" ;;
    windows) LIB="nativeaccelerator.dll" ;;
    *) LIB="libnativeaccelerator.so" ;;
esac

FOUND=$(find "$BUILD_DIR" -type f \( -name "$LIB" -o -name "libnativeaccelerator.so.*" \) | head -n 1)
if [ -z "$FOUND" ]; then
    echo "Native library was not produced: $LIB" >&2
    exit 1
fi

cp "$FOUND" "$DEST/$LIB"
echo "Bundled unstripped $DEST/$LIB"

case "$OS" in
    macos) RENDERER_LIB="libnativeaccelerator_renderer_vulkan.dylib" ;;
    windows) RENDERER_LIB="nativeaccelerator_renderer_vulkan.dll" ;;
    *) RENDERER_LIB="libnativeaccelerator_renderer_vulkan.so" ;;
esac

RENDERER_FOUND=$(find "$BUILD_DIR" -type f \( -name "$RENDERER_LIB" -o -name "libnativeaccelerator_renderer_vulkan.so.*" \) | head -n 1)
if [ -n "$RENDERER_FOUND" ]; then
    cp "$RENDERER_FOUND" "$DEST/$RENDERER_LIB"
    echo "Bundled unstripped $DEST/$RENDERER_LIB"
else
    echo "Renderer companion library was not produced; core library remains usable" >&2
fi

package_debug_symbols() {
    BINARY=$1
    OUTPUT_NAME=$2

    # Human-readable symbol inventory is useful even on systems where split
    # debug tooling is unavailable. Failure is non-fatal because nm options vary.
    if command -v nm >/dev/null 2>&1; then
        nm -a -n "$BINARY" > "$DEBUG_DEST/$OUTPUT_NAME.nm.txt" 2>/dev/null || \
        nm -a "$BINARY" > "$DEBUG_DEST/$OUTPUT_NAME.nm.txt" 2>/dev/null || \
        rm -f "$DEBUG_DEST/$OUTPUT_NAME.nm.txt"
    fi

    case "$OS" in
        macos)
            if command -v dsymutil >/dev/null 2>&1; then
                dsymutil "$BINARY" -o "$DEBUG_DEST/$OUTPUT_NAME.dSYM"
                echo "Bundled dSYM for $OUTPUT_NAME"
            else
                echo "dsymutil unavailable; debug information remains in unstripped $OUTPUT_NAME" >&2
            fi
            ;;
        *)
            OBJCOPY=$(find_objcopy || true)
            if [ -n "$OBJCOPY" ]; then
                if "$OBJCOPY" --only-keep-debug "$BINARY" "$DEBUG_DEST/$OUTPUT_NAME.debug" 2>/dev/null; then
                    echo "Bundled split debug symbols $DEBUG_DEST/$OUTPUT_NAME.debug"
                else
                    rm -f "$DEBUG_DEST/$OUTPUT_NAME.debug"
                    echo "objcopy cannot extract debug data for $OUTPUT_NAME on this target; full symbols remain in the unstripped binary" >&2
                fi
            else
                echo "objcopy unavailable; debug information remains in unstripped $OUTPUT_NAME" >&2
            fi
            ;;
    esac
}

package_debug_symbols "$FOUND" "$LIB"
if [ -n "$RENDERER_FOUND" ]; then
    package_debug_symbols "$RENDERER_FOUND" "$RENDERER_LIB"
fi

# MSVC emits PDBs separately. Copy all project PDBs that CMake produced. This is
# intentionally additional to the binary itself rather than a substitute for it.
if [ "$OS" = "windows" ]; then
    find "$BUILD_DIR" -type f \( -name 'nativeaccelerator*.pdb' -o -name 'nativeaccelerator_renderer_vulkan*.pdb' \) -print | while IFS= read -r PDB; do
        cp "$PDB" "$DEBUG_DEST/$(basename "$PDB")"
        echo "Bundled PDB $DEBUG_DEST/$(basename "$PDB")"
    done
fi

# Bundle the native implementation sources alongside the compiled libraries for
# reference and porting. Preserve the native/ tree layout, but include only C
# source, headers, and assembly. Uppercase .S is included because many
# toolchains use it for assembly that must pass through the C preprocessor.
SOURCE_DEST="$OUTPUT_DIR/META-INF/native-source"
rm -rf "$SOURCE_DEST"
mkdir -p "$SOURCE_DEST"

(
    cd "$PROJECT_DIR/native"
    find . -type f \
        \( -name '*.c' -o -name '*.h' -o -name '*.s' -o -name '*.S' \) \
        -print | while IFS= read -r SOURCE; do
        RELATIVE=${SOURCE#./}
        mkdir -p "$SOURCE_DEST/$(dirname "$RELATIVE")"
        cp "$SOURCE" "$SOURCE_DEST/$RELATIVE"
    done
)

SOURCE_COUNT=$(find "$SOURCE_DEST" -type f | wc -l | tr -d ' ')
DEBUG_COUNT=$(find "$DEBUG_DEST" -type f 2>/dev/null | wc -l | tr -d ' ')
echo "Bundled $SOURCE_COUNT native reference source files under $SOURCE_DEST"
echo "Bundled $DEBUG_COUNT native debug/symbol files under $DEBUG_DEST"
