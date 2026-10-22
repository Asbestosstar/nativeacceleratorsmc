#!/usr/bin/env bash
# Build harness classes and run the Vulkan loader check without creating a Vulkan instance.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_DIR=$ROOT
LABEL=vulkan-loader-probe
# shellcheck source=run-harness-common.sh
source "$ROOT/scripts/run-harness-common.sh"

prepare_harness_classpath

native_dir=$(na_minecraft_natives_dir)
if [[ ! -d "$native_dir" ]]; then
    printf 'VULKAN-LOADER: BLOCKED (no Minecraft LWJGL natives directory at %s; set NATIVEACCELERATOR_MINECRAFT_NATIVES)\n' "$native_dir" >&2
    exit 2
fi
if ! na_find_native "$native_dir" lwjgl >/dev/null; then
    printf 'VULKAN-LOADER: BLOCKED (no LWJGL native library in %s)\n' "$native_dir" >&2
    exit 2
fi
if [[ "$(na_os)" == macos ]] && ! na_find_native "$native_dir" MoltenVK >/dev/null; then
    printf 'VULKAN-LOADER: BLOCKED (no MoltenVK library in %s)\n' "$native_dir" >&2
    exit 2
fi

"$HARNESS_JAVA" \
    -Dorg.lwjgl.util.Debug=true \
    "-Dorg.lwjgl.librarypath=$native_dir" \
    -Dnativeaccelerator.vulkan.loaderOnly=true \
    -cp "$(harness_classpath)" \
    com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanDeviceBootstrapTest
