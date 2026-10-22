#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR=${1:-"$PROJECT_DIR/target/native-test"}
cmake -S "$PROJECT_DIR/native" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
cmake --build "$BUILD_DIR" --config Release --parallel
(cd "$BUILD_DIR" && ctest -C Release --output-on-failure)
