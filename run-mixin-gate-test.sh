#!/bin/sh
# run-mixin-gate-test.sh -- run the Native Accelerator mixin selection gate harness.
#
# SystemMixinGateTest is a plain-main harness (no JUnit dependency), so it is not
# picked up by mvn test. This script compiles it with the project toolchain and
# classpath and runs it.
#
# Usage:
#   ./run-mixin-gate-test.sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

MVN=${MVN:-mvn}
CP_FILE="$PROJECT_DIR/target/mixin-gate-test-classpath.txt"
TEST_CLASSES="$PROJECT_DIR/target/test-classes"
MAIN_CLASS=com.asbestosstar.nativeaccelerator.mixinconfig.SystemMixinGateTest

# The project targets a recent Java release, so the harness must run on the same JDK Maven compiles
# with rather than whatever java happens to be first on PATH. Explicit JAVA wins; otherwise ask Maven.
if [ -z "${JAVA:-}" ]; then
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA="$JAVA_HOME/bin/java"
    else
        JAVA=$($MVN -o -version 2>/dev/null | sed -n 's/^.*runtime: //p')
        JAVA=${JAVA%/}/bin/java
    fi
fi
if [ ! -x "$JAVA" ]; then
    JAVA=$(command -v java || true)
fi
if [ -z "$JAVA" ]; then
    echo "run-mixin-gate-test.sh: no java runtime found (set JAVA=...)" >&2
    exit 1
fi

if [ "${SKIP_MINECRAFT_BOOTSTRAP:-0}" != "1" ]; then
    echo "[mixin-gate] provisioning the Minecraft compile dependency..."
    MAVEN_REPO_LOCAL=${MAVEN_REPO_LOCAL:-${MAVEN_REPO:-}} \
        MVN="$MVN" "$PROJECT_DIR/scripts/bootstrap-minecraft-maven.sh"
fi

MAVEN_ARGS=""
if [ -n "${MAVEN_REPO_LOCAL:-${MAVEN_REPO:-}}" ]; then
    MAVEN_ARGS="-Dmaven.repo.local=${MAVEN_REPO_LOCAL:-${MAVEN_REPO}}"
fi

echo "[mixin-gate] compiling main and test sources..."
# shellcheck disable=SC2086
$MVN $MAVEN_ARGS -o -q test-compile

echo "[mixin-gate] resolving runtime classpath..."
$MVN -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=compile

CP="$TEST_CLASSES:$PROJECT_DIR/target/classes:$(cat "$CP_FILE")"

echo "[mixin-gate] running $MAIN_CLASS"
exec "$JAVA" -cp "$CP" "$MAIN_CLASS"
