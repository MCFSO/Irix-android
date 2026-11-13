#!/usr/bin/env bash
# Build libxmc_time.so for Android arm64 from the IriX (desktop) Rust workspace.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk bash tool/build_xmc_time_android.sh [/path/to/IriX]
#
# In Android CI the IriX source is checked out at ./irix, so:
#   bash tool/build_xmc_time_android.sh "$GITHUB_WORKSPACE/irix"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_REPO="${1:-$ROOT/../IriX}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
API="${ANDROID_API_LEVEL:-23}"
TARGET="aarch64-linux-android"
ABI="arm64-v8a"

if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "ANDROID_NDK_HOME is not set or does not exist" >&2
  exit 1
fi
if [[ ! -f "$RUST_REPO/rust/Cargo.toml" ]]; then
  echo "IriX Rust workspace not found at $RUST_REPO/rust/Cargo.toml" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) HOST="linux-x86_64" ;;
  Darwin) HOST="darwin-x86_64" ;;
  *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac

BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
CLANG="$BIN/aarch64-linux-android${API}-clang"
AR="$BIN/llvm-ar"
if [[ ! -x "$CLANG" ]]; then
  echo "NDK clang not found: $CLANG" >&2
  exit 1
fi

export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$AR"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"

cargo build --release --target "$TARGET" -p xmc_time \
  --manifest-path "$RUST_REPO/rust/Cargo.toml"

SRC="$RUST_REPO/rust/target/$TARGET/release/libxmc_time.so"
DST="$ROOT/app/src/main/jniLibs/$ABI/libxmc_time.so"
[[ -f "$SRC" ]] || { echo "Rust library not produced: $SRC" >&2; exit 1; }
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
echo "Copied $(stat -c%s "$DST" 2>/dev/null || stat -f%z "$DST") bytes to $DST"
