#!/usr/bin/env bash
set -euo pipefail

# Build static libquiche.a for macOS and iOS architectures via cargo
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUICHE_DIR="$(cd "$SCRIPT_DIR/../third_party/quiche" && pwd)"
OUT_ROOT="$SCRIPT_DIR/../build/quiche"

# Resolve rustup toolchain — needed for cross-compilation targets.
# Homebrew rustc doesn't ship cross-compilation std libs, so we must use
# rustup-managed cargo AND rustc together.
RUSTUP_TOOLCHAIN_DIR="${RUSTUP_HOME:-$HOME/.rustup}/toolchains/stable-aarch64-apple-darwin"
if [ -x "$RUSTUP_TOOLCHAIN_DIR/bin/cargo" ]; then
  export CARGO="$RUSTUP_TOOLCHAIN_DIR/bin/cargo"
  export RUSTC="$RUSTUP_TOOLCHAIN_DIR/bin/rustc"
else
  export CARGO="${CARGO:-cargo}"
fi

# Cargo target → output path mapping
# Format: "rust_target output_sdk output_arch"
TARGETS=(
  "aarch64-apple-darwin macosx arm64"
  "x86_64-apple-darwin macosx x86_64"
  "aarch64-apple-ios iphoneos arm64"
  "aarch64-apple-ios-sim iphone arm64"
)

for entry in "${TARGETS[@]}"; do
  read -r RUST_TARGET SDK ARCH <<<"$entry"

  PREFIX="$OUT_ROOT/$SDK/$ARCH"
  mkdir -p "$PREFIX/lib" "$PREFIX/include"

  echo "▶️  Building quiche for $RUST_TARGET ($SDK/$ARCH)..."

  # Workaround: quiche's build.rs doesn't distinguish aarch64-apple-ios from
  # aarch64-apple-ios-sim — it always uses the "iphoneos" sysroot for aarch64.
  # We temporarily patch build.rs to use "iphonesimulator" for sim targets.
  if [[ "$RUST_TARGET" == "aarch64-apple-ios-sim" ]]; then
    sed -i.bak \
      's/("CMAKE_OSX_SYSROOT", "iphoneos")/("CMAKE_OSX_SYSROOT", "iphonesimulator")/' \
      "$QUICHE_DIR/quiche/src/build.rs"
    # Force rebuild of the build script
    touch "$QUICHE_DIR/quiche/src/build.rs"
  fi

  # For iOS targets, use `cargo rustc --crate-type staticlib` to avoid cdylib
  # linking which fails due to missing iOS SDK symbols (___chkstk_darwin).
  # We only need the static library anyway.
  if [[ "$RUST_TARGET" == *"-ios"* ]]; then
    "$CARGO" rustc \
      --manifest-path "$QUICHE_DIR/quiche/Cargo.toml" \
      --target "$RUST_TARGET" \
      --features ffi \
      --release \
      --crate-type staticlib
  else
    "$CARGO" build \
      --manifest-path "$QUICHE_DIR/quiche/Cargo.toml" \
      --target "$RUST_TARGET" \
      --features ffi \
      --release
  fi

  # Restore build.rs if we patched it
  if [[ -f "$QUICHE_DIR/quiche/src/build.rs.bak" ]]; then
    mv "$QUICHE_DIR/quiche/src/build.rs.bak" "$QUICHE_DIR/quiche/src/build.rs"
  fi

  # Copy the static library
  cp "$QUICHE_DIR/target/$RUST_TARGET/release/libquiche.a" "$PREFIX/lib/"

  # Copy headers
  cp "$QUICHE_DIR/quiche/include/quiche.h" "$PREFIX/include/"

  echo "✅  libquiche.a → $PREFIX/lib/libquiche.a"
done
