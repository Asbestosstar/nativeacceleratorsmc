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

# The client POM does not declare Minecraft's transitive runtime libraries (Netty, UUID utilities, ...),
# so the harness adds the libraries the installed 26.3 game itself lists. A jar whose filename the Maven
# classpath already supplies is skipped so LWJGL and friends are never mixed across versions.
launcher_home=$(na_minecraft_home)
game_libraries="$(python3 - "$launcher_home" <<'PY'
import json, os, platform, sys
home = sys.argv[1]
libdir = os.path.join(home, "libraries")
manifests = [os.path.join(home, "versions/26.3/26.3.json"),
             os.path.join(home, "versions/fabric-loader-0.19.5-26.3/fabric-loader-0.19.5-26.3.json")]
arch = platform.machine()
jars = {}
for manifest in manifests:
    if not os.path.exists(manifest):
        sys.exit(f"missing game manifest {manifest}")
    for lib in json.load(open(manifest)).get("libraries", []):
        parts = lib.get("name", "").split(":")
        if len(parts) < 3:
            continue
        classifier = parts[3] if len(parts) > 3 else ""
        if "natives" in classifier or classifier.startswith(("linux", "windows")):
            continue
        if classifier.startswith("osx") and ((arch == "arm64") != ("aarch" in classifier)):
            continue
        path = lib.get("downloads", {}).get("artifact", {}).get("path")
        if not path:
            group, artifact, version = parts[0].replace(".", "/"), parts[1], parts[2]
            suffix = f"-{classifier}" if classifier else ""
            path = f"{group}/{artifact}/{version}/{artifact}-{version}{suffix}.jar"
        full = os.path.join(libdir, path)
        if not os.path.exists(full):
            sys.exit(f"missing game library {full}")
        jars[os.path.basename(full)] = full
print(os.pathsep.join(jars.values()))
PY
)" || { printf 'RENDERPEARL-TRIANGLE: BLOCKED (cannot read Minecraft runtime libraries from %s)\n' "$launcher_home"; exit 1; }

runtime_classpath="$(python3 - "$TEST_CLASSES" "$MAIN_CLASSES" "$CP_FILE" "$game_libraries" <<'PY'
import os, re, sys
test_classes, main_classes, cp_file, game = sys.argv[1:5]
def artifact(jar):
    return re.sub(r'-\d[^/]*\.jar$', '', os.path.basename(jar))
game_jars = [j for j in game.split(os.pathsep) if j]
game_artifacts = {artifact(j) for j in game_jars}
maven = [j for j in open(cp_file).read().strip().split(os.pathsep) if j]
entries = [test_classes, main_classes] + game_jars
entries += [j for j in maven if artifact(j) not in game_artifacts]
print(os.pathsep.join(entries))
PY
)"

jvm_args=(--enable-native-access=ALL-UNNAMED -Dorg.lwjgl.util.Debug=true "-Dorg.lwjgl.librarypath=$native_dir")
if [[ "$os" == macos ]]; then
  if [[ -z "${MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS+x}" ]]; then
    export MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0
  fi
  jvm_args=(-XstartOnFirstThread "${jvm_args[@]}" "-Dnativeaccelerator.vulkan.sdlLibrary=$moltenvk_library")
fi
exec "$HARNESS_JAVA" "${jvm_args[@]}" -cp "$runtime_classpath" "$HARNESS"
