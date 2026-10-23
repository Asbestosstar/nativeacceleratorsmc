#!/usr/bin/env python3
# Usage: minecraft_classpath.py MINECRAFT_HOME MAVEN_CLASSPATH_FILE
# Prints the Minecraft 26.3 runtime jars followed by the Maven classpath entries whose artifact
# the game does not already supply. The game's own LWJGL jars must win: the launcher's natives
# were built for these exact jars, and a Maven copy with a different build breaks native binding.
import json
import os
import platform
import re
import sys


def artifact(jar):
    return re.sub(r"-\d[^/]*\.jar$", "", os.path.basename(jar))


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
                group, name, version = parts[0].replace(".", "/"), parts[1], parts[2]
                suffix = f"-{classifier}" if classifier else ""
                path = f"{group}/{name}/{version}/{name}-{version}{suffix}.jar"
            full = os.path.join(libdir, path)
            if not os.path.exists(full):
                sys.exit(f"missing game library {full}")
            jars[os.path.basename(full)] = full
    return list(jars.values())


def main():
    home, cp_file = sys.argv[1], sys.argv[2]
    game = game_jars(home)
    game_artifacts = {artifact(j) for j in game}
    maven = [j for j in open(cp_file).read().strip().split(os.pathsep) if j]
    entries = game + [j for j in maven if artifact(j) not in game_artifacts]
    print(os.pathsep.join(entries))


if __name__ == "__main__":
    main()
