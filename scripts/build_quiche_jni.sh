#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

BASE_BUILD_DIR="$PROJECT_ROOT/build/buildJniMacos"
QUICHE_BUILD_DIR="$PROJECT_ROOT/build/quiche"
SRC_DIR="$PROJECT_ROOT/kiche/jni"

ARCHITECTURES=("arm64" "x86_64")

for ARCH in "${ARCHITECTURES[@]}"; do
    echo "Building macOS JNI for $ARCH..."

    BUILD_DIR="$BASE_BUILD_DIR/$ARCH"
    mkdir -p "$BUILD_DIR"

    # Set JAVA_HOME if not set
    if [ -z "${JAVA_HOME:-}" ]; then
        if [ -x "/usr/libexec/java_home" ]; then
            export JAVA_HOME="$(/usr/libexec/java_home)"
        else
            echo "Warning: JAVA_HOME not set and java_home not available"
        fi
    fi

    cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
        -DJAVA_HOME="${JAVA_HOME}" \
        -DQUICHE_INCLUDE_DIR="$QUICHE_BUILD_DIR/macosx/$ARCH/include" \
        -DQUICHE_LIB_PATH="$QUICHE_BUILD_DIR/macosx/$ARCH/lib/libquiche.a"

    cmake --build "$BUILD_DIR" --config Release

    echo "✅ Built macOS JNI for $ARCH"
done

echo "✅ All macOS JNI architectures built successfully"
