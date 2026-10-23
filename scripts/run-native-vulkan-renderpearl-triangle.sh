#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_DIR="$ROOT" LABEL=native-vulkan-renderpearl-triangle
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
  printf 'RENDERPEARL-TRIANGLE: BLOCKED (Minecraft LWJGL SDL/Vulkan natives are unavailable in %s)\n' "$native_dir"
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  printf 'RENDERPEARL-TRIANGLE: BLOCKED (python3 is required to read the Minecraft runtime classpath)\n'
  exit 1
fi
HARNESS=com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanRenderPearlTriangleHarness

# The harness needs the installed game's exact runtime libraries (LWJGL, Netty, ...). Refuse to run on the
# Maven copies instead of falling back, because mismatched LWJGL builds break native binding.
launcher_home=$(na_minecraft_home)
runtime_classpath=$(python3 "$PROJECT_DIR/scripts/lib/minecraft_classpath.py" "$launcher_home" "$CP_FILE") \
  || { printf 'RENDERPEARL-TRIANGLE: BLOCKED (cannot read Minecraft runtime libraries from %s)\n' "$launcher_home"; exit 1; }
runtime_classpath="$TEST_CLASSES$(printf ':%s' "$MAIN_CLASSES")${runtime_classpath:+:$runtime_classpath}"

jvm_args=(--enable-native-access=ALL-UNNAMED -Dorg.lwjgl.util.Debug=true "-Dorg.lwjgl.librarypath=$native_dir")
if [[ "$os" == macos ]]; then
  if [[ -z "${MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS+x}" ]]; then
    export MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0
  fi
  jvm_args=(-XstartOnFirstThread "${jvm_args[@]}" "-Dnativeaccelerator.vulkan.sdlLibrary=$moltenvk_library")
fi
exec "$HARNESS_JAVA" "${jvm_args[@]}" -cp "$runtime_classpath" "$HARNESS"
