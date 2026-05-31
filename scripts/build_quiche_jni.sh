#!/bin/bash
set -euo pipefail

# Builds the macOS JNI wrapper libquiche_jni.dylib for arm64 + x86_64,
# linking dynamically against libquiche.dylib + quiche.h supplied by Gradle.
#
# Required env vars (set by the :kiche:buildJniMacos Gradle task):
#   LIBQUICHE_JVM_NATIVE_ROOT — dir containing <arch>/libquiche.dylib
#   QUICHE_INCLUDE_DIR        — dir containing quiche.h

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."

BASE_BUILD_DIR="$PROJECT_ROOT/build/buildJniMacos"
SRC_DIR="$PROJECT_ROOT/kiche/jni"

: "${LIBQUICHE_JVM_NATIVE_ROOT:?need LIBQUICHE_JVM_NATIVE_ROOT — pass via Gradle}"
: "${QUICHE_INCLUDE_DIR:?need QUICHE_INCLUDE_DIR — pass via Gradle}"

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

    LIBQUICHE_DYLIB="$LIBQUICHE_JVM_NATIVE_ROOT/$ARCH/libquiche.dylib"
    if [ ! -f "$LIBQUICHE_DYLIB" ]; then
        echo "ERROR: $LIBQUICHE_DYLIB not found (extracted from :libquiche-jvm?)" >&2
        exit 1
    fi

    cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
        -DCMAKE_OSX_ARCHITECTURES="$ARCH" \
        -DJAVA_HOME="${JAVA_HOME}" \
        -DQUICHE_INCLUDE_DIR="$QUICHE_INCLUDE_DIR" \
        -DQUICHE_LIB_PATH="$LIBQUICHE_DYLIB"

    cmake --build "$BUILD_DIR" --config Release

    echo "✅ Built macOS JNI for $ARCH"
done

echo "✅ All macOS JNI architectures built successfully"
