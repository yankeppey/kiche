#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?Please export ANDROID_NDK_HOME to point at your NDK}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUICHE_DIR="$(cd "$SCRIPT_DIR/../third_party/quiche" && pwd)"
OUT_ROOT="$SCRIPT_DIR/../build/quiche/android"
API=26

# Android ABI → Rust target mapping
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
RUST_TARGETS=("aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android")

for i in "${!ABIS[@]}"; do
  ABI="${ABIS[$i]}"
  RUST_TARGET="${RUST_TARGETS[$i]}"
  PREFIX="$OUT_ROOT/$ABI"
  mkdir -p "$PREFIX/lib" "$PREFIX/include"

  echo "▶️  Building quiche for Android $ABI ($RUST_TARGET)..."

  cargo ndk \
    --target "$RUST_TARGET" \
    --platform "$API" \
    -- build \
    --manifest-path "$QUICHE_DIR/quiche/Cargo.toml" \
    --features ffi \
    --release

  cp "$QUICHE_DIR/target/$RUST_TARGET/release/libquiche.a" "$PREFIX/lib/"
  cp "$QUICHE_DIR/quiche/include/quiche.h" "$PREFIX/include/"

  echo "✅  libquiche.a → $PREFIX/lib/libquiche.a"
done
