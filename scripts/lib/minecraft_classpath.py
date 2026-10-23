#!/usr/bin/env python3
# Usage: minecraft_classpath.py MINECRAFT_HOME MAVEN_CLASSPATH_FILE
# Prints the Minecraft 26.3 runtime jars followed by the Maven classpath entries whose artifact
# the game does not already supply. LWJGL is always taken from the game install and Maven LWJGL jars
# are never used, because the launcher's natives were built for the game's exact LWJGL build.
# Exits 3 when the game install cannot provide LWJGL.
import json
import os
import platform
import re
import sys


def artifact(jar):
    return re.sub(r"-\d[^/]*\.jar$", "", os.path.basename(jar))


def is_lwjgl(jar):
    return artifact(jar).startswith("lwjgl")


def game_jars(home):
    libdir = os.path.join(home, "libraries")
    manifests = [
        os.path.join(home, "versions/26.3/26.3.json"),
        os.path.join(home, "versions/fabric-loader-0.19.5-26.3/fabric-loader-0.19.5-26.3.json"),
    ]
    arch = platform.machine()
    jars = {}
    for manifest in manifests:
        if not os.path.exists(manifest):
            raise RuntimeError(f"missing game manifest {manifest}")
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
                group, name, version = parts[0].replace(".", "/"), parts[1], parts[2]
                suffix = f"-{classifier}" if classifier else ""
                path = f"{group}/{name}/{version}/{name}-{version}{suffix}.jar"
            full = os.path.join(libdir, path)
            if not os.path.exists(full):
                raise RuntimeError(f"missing game library {full}")
            jars[os.path.basename(full)] = full
    return list(jars.values())


def main():
    home, cp_file = sys.argv[1], sys.argv[2]
    try:
        game = game_jars(home)
    except RuntimeError as error:
        print(f"minecraft_classpath: {error}", file=sys.stderr)
        sys.exit(3)
    lwjgl = [j for j in game if is_lwjgl(j)]
    if not lwjgl:
        print(f"minecraft_classpath: no Minecraft LWJGL jars found under {home}/libraries", file=sys.stderr)
        sys.exit(3)
    for jar in lwjgl:
        print(f"minecraft_classpath: LWJGL {jar}", file=sys.stderr)
    game_artifacts = {artifact(j) for j in game}
    maven = [j for j in open(cp_file).read().strip().split(os.pathsep) if j]
    kept = [j for j in maven if artifact(j) not in game_artifacts and not is_lwjgl(j)]
    print(os.pathsep.join(game + kept))


if __name__ == "__main__":
    main()
