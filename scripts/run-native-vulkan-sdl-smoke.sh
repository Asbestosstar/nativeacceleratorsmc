#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_DIR="$ROOT" LABEL=native-vulkan-sdl-smoke
export PROJECT_DIR LABEL
# shellcheck source=run-harness-common.sh
source "$ROOT/scripts/run-harness-common.sh"
prepare_harness_classpath

os=$(na_os)
native_dir=$(na_minecraft_natives_dir)
lwjgl_library=$(na_find_native "$native_dir" lwjgl) || lwjgl_library=
sdl_library=$(na_find_native "$native_dir" SDL3) || sdl_library=
moltenvk_library=$(na_find_native "$native_dir" MoltenVK) || moltenvk_library=
if [[ -z "$lwjgl_library" || -z "$sdl_library" || ( "$os" == macos && -z "$moltenvk_library" ) ]]; then
  printf 'SDL-VULKAN: BLOCKED (Minecraft LWJGL SDL/Vulkan natives are unavailable in %s)\n' "$native_dir"
  exit 1
fi

jvm_args=(--enable-native-access=ALL-UNNAMED -Dorg.lwjgl.util.Debug=true "-Dorg.lwjgl.librarypath=$native_dir")
if [[ "$os" == macos ]]; then
  # Cocoa video initialization must run on the process's first thread.
  if [[ -z "${MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS+x}" ]]; then
    export MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0
  fi
  jvm_args=(-XstartOnFirstThread "${jvm_args[@]}" "-Dnativeaccelerator.vulkan.sdlLibrary=$moltenvk_library")
fi
exec "$HARNESS_JAVA" "${jvm_args[@]}" -cp "$(harness_classpath)" \
  com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanSdlSmoke
