#!/bin/sh
# Provision the non-repository Minecraft client artifact before Maven resolves the project.
# Maven collects project dependencies before executing lifecycle phases, so this must be a
# separate invocation from `mvn package` on a clean local repository.
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MVN=${MVN:-mvn}
MAVEN_REPO_LOCAL=${MAVEN_REPO_LOCAL:-${MAVEN_REPO:-}}
MINECRAFT_VERSION=${MINECRAFT_VERSION:-26.3}
MINECRAFT_CLIENT_URL=${MINECRAFT_CLIENT_URL:-https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar}
MINECRAFT_CLIENT_SHA1=${MINECRAFT_CLIENT_SHA1:-e877b6a07acd633fb3bb475002175cec036e7b87}
MINECRAFT_CLIENT_JAR=${MINECRAFT_CLIENT_JAR:-$PROJECT_DIR/target/minecraft/client.jar}

if ! command -v curl >/dev/null 2>&1; then
    printf '%s\n' 'bootstrap-minecraft-maven.sh: curl is required' >&2
    exit 1
fi
if ! command -v shasum >/dev/null 2>&1; then
    printf '%s\n' 'bootstrap-minecraft-maven.sh: shasum is required' >&2
    exit 1
fi
if ! command -v "$MVN" >/dev/null 2>&1 && [ ! -x "$MVN" ]; then
    printf 'bootstrap-minecraft-maven.sh: Maven not found: %s\n' "$MVN" >&2
    exit 1
fi

MINECRAFT_DIR=$(dirname "$MINECRAFT_CLIENT_JAR")
mkdir -p "$MINECRAFT_DIR"

verify_client() {
    [ -s "$MINECRAFT_CLIENT_JAR" ] || return 1
    actual=$(shasum -a 1 "$MINECRAFT_CLIENT_JAR" | awk '{print $1}')
    [ "$actual" = "$MINECRAFT_CLIENT_SHA1" ]
}

if verify_client; then
    printf '[minecraft-bootstrap] verified %s\n' "$MINECRAFT_CLIENT_JAR"
else
    temporary="$MINECRAFT_CLIENT_JAR.tmp.$$"
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    rm -f "$temporary"
    printf '[minecraft-bootstrap] downloading Minecraft %s\n' "$MINECRAFT_VERSION"
    curl --fail --location --retry 3 --silent --show-error "$MINECRAFT_CLIENT_URL" -o "$temporary"
    actual=$(shasum -a 1 "$temporary" | awk '{print $1}')
    if [ "$actual" != "$MINECRAFT_CLIENT_SHA1" ]; then
        printf 'bootstrap-minecraft-maven.sh: SHA-1 mismatch for Minecraft client (expected %s, got %s)\n' \
            "$MINECRAFT_CLIENT_SHA1" "$actual" >&2
        exit 1
    fi
    mv -f "$temporary" "$MINECRAFT_CLIENT_JAR"
    trap - EXIT HUP INT TERM
fi

MAVEN_ARGS=""
if [ -n "$MAVEN_REPO_LOCAL" ]; then
    MAVEN_ARGS="-Dmaven.repo.local=$MAVEN_REPO_LOCAL"
fi

# Use direct plugin goals rather than a project lifecycle phase: Maven would otherwise
# collect this project's dependencies before the initialize-phase install can run.
# shellcheck disable=SC2086
"$MVN" $MAVEN_ARGS -q \
    org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="$MINECRAFT_CLIENT_JAR" \
    -DgroupId=net.minecraft \
    -DartifactId=minecraft \
    -Dversion="$MINECRAFT_VERSION" \
    -Dclassifier=client \
    -Dpackaging=jar \
    -DgeneratePom=true

printf '[minecraft-bootstrap] installed net.minecraft:minecraft:%s:client\n' "$MINECRAFT_VERSION"
