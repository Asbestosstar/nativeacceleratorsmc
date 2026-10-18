#!/usr/bin/env bash
# Shared setup for plain-main Java harnesses.
# Source this file from a runner after setting PROJECT_DIR and a human-readable LABEL.
set -euo pipefail

: "${PROJECT_DIR:?run-harness-common.sh requires PROJECT_DIR}"
: "${LABEL:?run-harness-common.sh requires LABEL}"

MVN=${MVN:-mvn}
BUILD_DIR=${BUILD_DIR:-$PROJECT_DIR/target}
TEST_CLASSES=${TEST_CLASSES:-$BUILD_DIR/test-classes}
MAIN_CLASSES=${MAIN_CLASSES:-$BUILD_DIR/classes}
CP_FILE=${CP_FILE:-$BUILD_DIR/${LABEL}-test-classpath.txt}

MAVEN_ARGS=(-q -f "$PROJECT_DIR/pom.xml")
if [[ -n "${MAVEN_REPO_LOCAL:-${MAVEN_REPO:-}}" ]]; then
    MAVEN_ARGS+=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL:-${MAVEN_REPO}}")
fi
if [[ "${MAVEN_OFFLINE:-0}" == "1" ]]; then
    MAVEN_ARGS+=(-o)
fi
# Set LWJGL_NATIVES_CLASSIFIER to a Maven classifier such as natives-macos when the
# optional lwjgl-natives profile is enabled for a real compiler smoke run.
if [[ -n "${LWJGL_NATIVES_CLASSIFIER:-}" ]]; then
    MAVEN_ARGS+=("-Dlwjgl.natives=$LWJGL_NATIVES_CLASSIFIER")
fi

find_harness_java() {
    if [[ -n "${JAVA:-}" && -x "$JAVA" ]]; then
        printf '%s\n' "$JAVA"
        return
    fi
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        printf '%s\n' "$JAVA_HOME/bin/java"
        return
    fi

    # Maven uses JAVA_HOME when it is set and otherwise resolves java through PATH. Use the
    # same fallback rather than parsing human-readable Maven version output, whose format varies
    # between Maven releases and does not reliably expose the runtime home.
    command -v java || true
}

HARNESS_JAVA=${HARNESS_JAVA:-$(find_harness_java)}
if [[ -z "$HARNESS_JAVA" || ! -x "$HARNESS_JAVA" ]]; then
    printf '%s: no Java runtime found (set JAVA or JAVA_HOME)\n' "$LABEL" >&2
    exit 1
fi

prepare_harness_classpath() {
    mkdir -p "$BUILD_DIR"
    if [[ "${SKIP_MINECRAFT_BOOTSTRAP:-0}" != "1" ]]; then
        printf '[%s] provisioning the Minecraft compile dependency...\n' "$LABEL"
        MAVEN_REPO_LOCAL=${MAVEN_REPO_LOCAL:-${MAVEN_REPO:-}} \
            MVN="$MVN" "$PROJECT_DIR/scripts/bootstrap-minecraft-maven.sh"
    fi
    printf '[%s] compiling main and test sources...\n' "$LABEL"
    "$MVN" "${MAVEN_ARGS[@]}" test-compile

    printf '[%s] resolving test/provided dependency classpath...\n' "$LABEL"
    # includeScope=test includes compile, provided, runtime, and test dependencies. This is
    # important for plain-main tests such as TriangleMaterialTest, which use provided Gson.
    "$MVN" "${MAVEN_ARGS[@]}" dependency:build-classpath \
        -Dmdep.outputFile="$CP_FILE" \
        -Dmdep.includeScope=test

    if [[ ! -f "$CP_FILE" ]]; then
        printf '%s: Maven did not create %s\n' "$LABEL" "$CP_FILE" >&2
        exit 1
    fi
}

harness_classpath() {
    local resolved separator
    resolved=$(<"$CP_FILE")
    separator=':'
    if [[ "${OS:-}" == "Windows_NT" || "$(uname -s 2>/dev/null || true)" == MINGW* || "$(uname -s 2>/dev/null || true)" == MSYS* ]]; then
        separator=';'
    fi
    printf '%s%s%s' "$TEST_CLASSES" "$separator" "$MAIN_CLASSES"
    if [[ -n "$resolved" ]]; then
        printf '%s%s' "$separator" "$resolved"
    fi
}

run_harness_main() {
    local main_class=$1
    shift
    printf '[%s] running %s\n' "$LABEL" "$main_class"
    "$HARNESS_JAVA" -cp "$(harness_classpath)" "$main_class" "$@"
}
