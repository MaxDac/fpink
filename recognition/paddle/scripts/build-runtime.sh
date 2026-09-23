#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$root/source-runtime.lock.json"
source_dir="$root/build/source/paddle-lite"
output_dir="$root/build/source-output"
runtime="$root/native/arm64-v8a/libpaddle_light_api_shared.so"
headers="$root/src/main/cpp/third_party/paddle_lite"

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v bash >/dev/null || { echo "bash is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$ndk" || ! -d "$ndk" ]]; then
  echo "Set ANDROID_NDK_ROOT or ANDROID_NDK_HOME to the pinned Android NDK." >&2
  exit 1
fi

commit="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["source"]["commit"])' "$lock")"
repository="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["source"]["repository"])' "$lock")"

mkdir -p "$(dirname "$source_dir")" "$output_dir" "$headers"
if [[ ! -d "$source_dir/.git" ]]; then
  git init "$source_dir" >/dev/null
  git -C "$source_dir" remote add origin "$repository"
fi
git -C "$source_dir" fetch --depth 1 origin "$commit"
git -C "$source_dir" checkout --detach "$commit" >/dev/null

rm -rf "$output_dir"
mkdir -p "$output_dir"
find "$source_dir" -type f -name libpaddle_light_api_shared.so -delete
(
  cd "$source_dir"
  ./lite/tools/build_android.sh \
    --arch=armv8 \
    --with_cv=ON \
    --with_extra=ON \
    --toolchain=clang \
    --android_ndk_root="$ndk"
)

built_runtime="$(find "$source_dir" -type f -name libpaddle_light_api_shared.so -print -quit)"
if [[ -z "$built_runtime" ]]; then
  echo "Paddle Lite source build did not produce libpaddle_light_api_shared.so." >&2
  exit 1
fi

declare -a required_headers=(paddle_api.h paddle_place.h)
for header in "${required_headers[@]}"; do
  if [[ ! -f "$source_dir/cxx/include/$header" ]]; then
    echo "Paddle Lite source build did not produce cxx/include/$header." >&2
    exit 1
  fi
done

cp "$built_runtime" "$runtime"
for header in "${required_headers[@]}"; do
  cp "$source_dir/cxx/include/$header" "$headers/$header"
done

readelf_bin="$(command -v llvm-readelf || command -v readelf || true)"
if [[ -z "$readelf_bin" ]]; then
  echo "llvm-readelf or readelf is required to validate the source-built ELF." >&2
  exit 1
fi
"$readelf_bin" -h "$runtime" | grep -Eq 'AArch64|ARM aarch64' || {
  echo "Source-built Paddle runtime is not an AArch64 ELF." >&2
  exit 1
}
sha256sum "$runtime" "$headers"/*.h | tee "$output_dir/SHA256SUMS"
printf 'source=%s\ncommit=%s\n' "$repository" "$commit" > "$output_dir/PROVENANCE"
