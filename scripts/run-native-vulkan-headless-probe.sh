#!/usr/bin/env bash
# Enumerate Vulkan devices without initializing SDL video or claiming WSI support.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_DIR="$ROOT" LABEL=native-vulkan-headless-probe
export PROJECT_DIR LABEL
# shellcheck source=run-harness-common.sh
source "$ROOT/scripts/run-harness-common.sh"
# MoltenVK reads this before its first Vulkan operation. Respect an explicit caller setting.
if [[ "$(na_os)" == macos && -z "${MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS:-}" ]]; then
  export MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0
fi
prepare_harness_classpath

native_dir=$(na_minecraft_natives_dir)
if [[ ! -d "$native_dir" ]]; then
  printf 'VULKAN-HEADLESS: BLOCKED (no Minecraft LWJGL natives directory at %s; set NATIVEACCELERATOR_MINECRAFT_NATIVES)\n' "$native_dir"
  exit 0
fi
if ! na_find_native "$native_dir" lwjgl >/dev/null; then
  printf 'VULKAN-HEADLESS: BLOCKED (missing LWJGL native library in %s)\n' "$native_dir"
  exit 0
fi
if [[ "$(na_os)" == macos ]] && ! na_find_native "$native_dir" MoltenVK >/dev/null; then
  printf 'VULKAN-HEADLESS: BLOCKED (missing MoltenVK library in %s)\n' "$native_dir"
  exit 0
fi

args=()
if [[ "${NATIVEACCELERATOR_REQUIRE_GPU:-0}" == "1" || "${1:-}" == "--require" ]]; then
  args+=(--require)
fi
if ((${#args[@]})); then
  exec "$HARNESS_JAVA" -Dorg.lwjgl.util.Debug=true "-Dorg.lwjgl.librarypath=$native_dir" \
    -cp "$(harness_classpath)" \
    com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanHeadlessDeviceProbe "${args[@]}"
else
  exec "$HARNESS_JAVA" -Dorg.lwjgl.util.Debug=true "-Dorg.lwjgl.librarypath=$native_dir" \
    -cp "$(harness_classpath)" \
    com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanHeadlessDeviceProbe
fi
