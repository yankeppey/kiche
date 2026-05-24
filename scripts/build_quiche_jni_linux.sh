#!/usr/bin/env bash
set -euo pipefail

# Build the desktop-JVM JNI library for Linux: a static libquiche.a (Rust/cargo)
# plus the libquiche_jni.so wrapper (CMake). Runs natively on a Linux x86_64 host
# (no cross-compilation), which keeps quiche's vendored BoringSSL build simple.
#
# Usage: scripts/build_quiche_jni_linux.sh [x86_64|arm64]
# Output: build/buildJniLinux/<arch>/libquiche_jni.so

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
QUICHE_DIR="$PROJECT_ROOT/third_party/quiche"
QUICHE_OUT="$PROJECT_ROOT/build/quiche/linux"
JNI_OUT="$PROJECT_ROOT/build/buildJniLinux"
SRC_DIR="$PROJECT_ROOT/kiche/jni"

ARCH="${1:-x86_64}"
case "$ARCH" in
  x86_64)        RUST_TARGET="x86_64-unknown-linux-gnu" ;;
  arm64|aarch64) RUST_TARGET="aarch64-unknown-linux-gnu"; ARCH="arm64" ;;
  *) echo "Unsupported arch: $ARCH (expected x86_64 or arm64)" >&2; exit 1 ;;
esac

CARGO="${CARGO:-cargo}"

echo "▶️  Building quiche static lib for $RUST_TARGET ..."
"$CARGO" build \
  --manifest-path "$QUICHE_DIR/quiche/Cargo.toml" \
  --target "$RUST_TARGET" \
  --features ffi \
  --release

PREFIX="$QUICHE_OUT/$ARCH"
mkdir -p "$PREFIX/lib" "$PREFIX/include"
cp "$QUICHE_DIR/target/$RUST_TARGET/release/libquiche.a" "$PREFIX/lib/"
cp "$QUICHE_DIR/quiche/include/quiche.h" "$PREFIX/include/"

if [ -z "${JAVA_HOME:-}" ]; then
  echo "Warning: JAVA_HOME not set — CMake's FindJNI may fail to locate jni.h" >&2
fi

BUILD_DIR="$JNI_OUT/$ARCH"
mkdir -p "$BUILD_DIR"
cmake -B "$BUILD_DIR" -S "$SRC_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DJAVA_HOME="${JAVA_HOME:-}" \
  -DQUICHE_INCLUDE_DIR="$PREFIX/include" \
  -DQUICHE_LIB_PATH="$PREFIX/lib/libquiche.a"
cmake --build "$BUILD_DIR" --config Release

# Strip DWARF debug info. quiche's release build (Rust + vendored BoringSSL via
# CMake) embeds ~24 MB of DWARF into libquiche.a, and the GNU linker copies it
# into the .so. --strip-debug removes only the .debug_* sections; the dynamic
# symbol table (the exported JNI entry points) and all code stay intact. macOS
# (DWARF -> .dSYM) and Windows (-> .pdb) don't carry it, so only Linux needs this.
SO="$BUILD_DIR/libquiche_jni.so"
size() { stat -c%s "$1" 2>/dev/null || stat -f%z "$1"; }
echo "Stripping debug info ($(size "$SO") bytes before)..."
strip --strip-debug "$SO"

echo "✅  Built $SO ($(size "$SO") bytes after strip)"
