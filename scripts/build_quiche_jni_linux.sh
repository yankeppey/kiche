#!/usr/bin/env bash
set -euo pipefail

# Build the Linux JNI wrapper `libquiche_jni.so`, linked dynamically against
# a pre-built `libquiche.so`. cargo is not invoked here.
#
# Required env vars:
#   LIBQUICHE_JVM_NATIVE_ROOT — dir containing <arch>/libquiche.so
#   QUICHE_INCLUDE_DIR        — dir containing quiche.h
#
# Usage: scripts/build_quiche_jni_linux.sh [x86_64|arm64]
# Output: build/buildJniLinux/<arch>/libquiche_jni.so

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_OUT="$PROJECT_ROOT/build/buildJniLinux"
SRC_DIR="$PROJECT_ROOT/kiche/jni"

ARCH="${1:-x86_64}"
case "$ARCH" in
  x86_64) ;;
  arm64|aarch64) ARCH="arm64" ;;
  *) echo "Unsupported arch: $ARCH (expected x86_64 or arm64)" >&2; exit 1 ;;
esac

: "${LIBQUICHE_JVM_NATIVE_ROOT:?need LIBQUICHE_JVM_NATIVE_ROOT — pass via Gradle or workflow}"
: "${QUICHE_INCLUDE_DIR:?need QUICHE_INCLUDE_DIR — pass via Gradle or workflow}"

LIBQUICHE_SO="$LIBQUICHE_JVM_NATIVE_ROOT/$ARCH/libquiche.so"
if [ ! -f "$LIBQUICHE_SO" ]; then
  echo "ERROR: $LIBQUICHE_SO not found" >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "Warning: JAVA_HOME not set — CMake's FindJNI may fail to locate jni.h" >&2
fi

BUILD_DIR="$JNI_OUT/$ARCH"
mkdir -p "$BUILD_DIR"
cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DJAVA_HOME="${JAVA_HOME:-}" \
  -DQUICHE_INCLUDE_DIR="$QUICHE_INCLUDE_DIR" \
  -DQUICHE_LIB_PATH="$LIBQUICHE_SO"
cmake --build "$BUILD_DIR" --config Release

# Strip libquiche_jni.so. With dynamic linking the wrapper itself is small,
# but the GNU linker can still leave .debug_* sections from CMake's
# RelWithDebInfo / Release defaults.
SO="$BUILD_DIR/libquiche_jni.so"
size() { stat -c%s "$1" 2>/dev/null || stat -f%z "$1"; }
echo "Stripping debug info ($(size "$SO") bytes before)..."
strip --strip-debug "$SO"

echo "✅  Built $SO ($(size "$SO") bytes after strip)"
