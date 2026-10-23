#!/usr/bin/env python3
# Usage: minecraft_classpath.py MINECRAFT_HOME MAVEN_CLASSPATH_FILE
# Prints the Minecraft 26.3 LWJGL Java jars followed by every Maven classpath entry that is not LWJGL.
# LWJGL always comes from the game install: the launcher's natives were built for the game's exact LWJGL
# build, so Maven LWJGL jars are never used. Other libraries (netty, ...) are left to Maven and the system.
# Exits 3 when the game install cannot provide LWJGL.
import json
import os
import re
import sys


def artifact(jar):
    return re.sub(r"-\d[^/]*\.jar$", "", os.path.basename(jar))


def is_lwjgl(jar):
    return artifact(jar).startswith("lwjgl")


def lwjgl_jars(home):
    libdir = os.path.join(home, "libraries")
    manifests = [
        os.path.join(home, "versions/26.3/26.3.json"),
        os.path.join(home, "versions/fabric-loader-0.19.5-26.3/fabric-loader-0.19.5-26.3.json"),
    ]
    jars = {}
    for manifest in manifests:
        if not os.path.exists(manifest):
            raise RuntimeError(f"missing game manifest {manifest}")
        for lib in json.load(open(manifest)).get("libraries", []):
            parts = lib.get("name", "").split(":")
            # LWJGL Java jars carry no classifier; natives entries (any classifier) are loaded from librarypath.
            if len(parts) != 3 or not parts[1].startswith("lwjgl"):
                continue
            path = lib.get("downloads", {}).get("artifact", {}).get("path")
            if not path:
                group, name, version = parts[0].replace(".", "/"), parts[1], parts[2]
                path = f"{group}/{name}/{version}/{name}-{version}.jar"
            full = os.path.join(libdir, path)
            if not os.path.exists(full):
                raise RuntimeError(f"missing game library {full}")
            jars[os.path.basename(full)] = full
    return list(jars.values())


def main():
    home, cp_file = sys.argv[1], sys.argv[2]
    try:
        game = lwjgl_jars(home)
    except RuntimeError as error:
        print(f"minecraft_classpath: {error}", file=sys.stderr)
        sys.exit(3)
    if not game:
        print(f"minecraft_classpath: no Minecraft LWJGL jars found under {home}/libraries", file=sys.stderr)
        sys.exit(3)
    for jar in game:
        print(f"minecraft_classpath: LWJGL {jar}", file=sys.stderr)
    maven = [j for j in open(cp_file).read().strip().split(os.pathsep) if j]
    kept = [j for j in maven if not is_lwjgl(j)]
    print(os.pathsep.join(game + kept))


if __name__ == "__main__":
    main()
