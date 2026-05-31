#!/usr/bin/env bash
set -euo pipefail

# Build libquiche.{a,so} for Linux via cargo. Native build on a Linux host —
# quiche's vendored BoringSSL cross-compiles poorly, so no Docker/mingw here.
#
# Usage: scripts/build_libquiche_linux.sh [x86_64|arm64]
# Output layout (flat — matches what build_quiche_jni_linux.sh / Gradle's
# LIBQUICHE_JVM_NATIVE_ROOT expect):
#   build/quiche/linux/<arch>/libquiche.a
#   build/quiche/linux/<arch>/libquiche.so
#   build/quiche/linux/<arch>/include/quiche.h

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
QUICHE_DIR="$PROJECT_ROOT/third_party/quiche"
OUT_ROOT="$PROJECT_ROOT/build/quiche/linux"

ARCH="${1:-x86_64}"
case "$ARCH" in
  x86_64)        RUST_TARGET="x86_64-unknown-linux-gnu" ;;
  arm64|aarch64) RUST_TARGET="aarch64-unknown-linux-gnu"; ARCH="arm64" ;;
  *) echo "Unsupported arch: $ARCH (expected x86_64 or arm64)" >&2; exit 1 ;;
esac

CARGO="${CARGO:-cargo}"

echo "▶️  Building libquiche for $RUST_TARGET ..."
"$CARGO" build \
  --manifest-path "$QUICHE_DIR/quiche/Cargo.toml" \
  --target "$RUST_TARGET" \
  --features ffi \
  --release

PREFIX="$OUT_ROOT/$ARCH"
mkdir -p "$PREFIX/include"
cp "$QUICHE_DIR/target/$RUST_TARGET/release/libquiche.a"  "$PREFIX/"
cp "$QUICHE_DIR/target/$RUST_TARGET/release/libquiche.so" "$PREFIX/"
cp "$QUICHE_DIR/quiche/include/quiche.h"                  "$PREFIX/include/"

echo "✅  libquiche.{a,so} → $PREFIX/"
