# shellcheck shell=sh
# Shared POSIX helpers for Native Accelerator scripts. Source this file; do not execute it.

na_os() {
    case "$(uname -s 2>/dev/null || echo unknown)" in
        Linux) echo linux ;;
        Darwin) echo macos ;;
        SunOS) echo solaris ;;
        FreeBSD) echo freebsd ;;
        NetBSD) echo netbsd ;;
        OpenBSD) echo openbsd ;;
        DragonFly) echo dragonfly ;;
        Haiku) echo haiku ;;
        HP-UX) echo hpux ;;
        AIX) echo aix ;;
        MINGW*|MSYS*|CYGWIN*) echo windows ;;
        *) echo unknown ;;
    esac
}

# Usage: na_digest sha1|sha256 FILE. Prints the lowercase hex digest.
na_digest() {
    na_digest_algorithm=$1
    na_digest_file=$2
    na_digest_bits=${na_digest_algorithm#sha}
    if command -v "${na_digest_algorithm}sum" >/dev/null 2>&1; then
        "${na_digest_algorithm}sum" "$na_digest_file" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a "$na_digest_bits" "$na_digest_file" | awk '{ print $1 }'
    elif command -v digest >/dev/null 2>&1; then
        digest -a "$na_digest_algorithm" "$na_digest_file"
    elif command -v "$na_digest_algorithm" >/dev/null 2>&1; then
        "$na_digest_algorithm" -q "$na_digest_file"
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst "-$na_digest_algorithm" "$na_digest_file" | awk '{ print $NF }'
    else
        return 1
    fi
}

na_minecraft_home() {
    if [ -n "${NATIVEACCELERATOR_MINECRAFT_HOME:-}" ]; then
        printf '%s\n' "$NATIVEACCELERATOR_MINECRAFT_HOME"
        return
    fi
    case "$(na_os)" in
        macos) printf '%s\n' "$HOME/Library/Application Support/minecraft" ;;
        windows) printf '%s\n' "${APPDATA:-$HOME/AppData/Roaming}/.minecraft" ;;
        *) printf '%s\n' "$HOME/.minecraft" ;;
    esac
}

na_minecraft_natives_dir() {
    if [ -n "${NATIVEACCELERATOR_MINECRAFT_NATIVES:-}" ]; then
        printf '%s\n' "$NATIVEACCELERATOR_MINECRAFT_NATIVES"
        return
    fi
    printf '%s\n' "$(na_minecraft_home)/versions/fabric-loader-0.19.5-26.3/natives/lwjgl/3.4.3-snapshot/$(na_arch_dir)"
}

# Prints the LWJGL natives architecture directory for this host.
na_arch_dir() {
    case "$(uname -m 2>/dev/null || echo unknown)" in
        x86_64|amd64|i86pc)
            if [ "$(uname -m)" = i86pc ] && command -v isainfo >/dev/null 2>&1 && [ "$(isainfo -n)" = i386 ]; then
                echo x86
            else
                echo x64
            fi ;;
        arm64|aarch64) echo arm64 ;;
        i386|i486|i586|i686) echo x86 ;;
        *) uname -m 2>/dev/null || echo unknown ;;
    esac
}

# Usage: na_find_native DIR STEM. Prints the first matching native library, or fails.
# STEM is the bare name, e.g. lwjgl, SDL3, MoltenVK, or vulkan (vulkan-1 on Windows).
na_find_native() {
    na_find_dir=$1
    na_find_stem=$2
    if [ "$na_find_stem" = vulkan ] && [ "$(na_os)" = windows ]; then
        na_find_stem=vulkan-1
    fi
    case "$(na_os)" in
        macos) set -- "$na_find_dir/lib$na_find_stem.dylib" ;;
        windows) set -- "$na_find_dir/$na_find_stem.dll" ;;
        hpux) set -- "$na_find_dir/lib$na_find_stem.sl" "$na_find_dir/lib$na_find_stem.so" ;;
        *) set -- "$na_find_dir/lib$na_find_stem.so" "$na_find_dir/lib$na_find_stem.so".[0-9]* ;;
    esac
    for na_find_candidate do
        if [ -f "$na_find_candidate" ]; then
            printf '%s\n' "$na_find_candidate"
            return 0
        fi
    done
    return 1
}

# Vulkan loader for the Minecraft runtime: MoltenVK on macOS, otherwise the system loader is used
# unless the natives directory ships one.
na_vulkan_library() {
    if [ "$(na_os)" = macos ]; then
        na_find_native "$1" MoltenVK
    else
        na_find_native "$1" vulkan
    fi
}
