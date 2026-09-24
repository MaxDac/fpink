#!/usr/bin/env bash
# Build the pinned Paddle Lite ARM64 CPU runtime from source.
#
# Usage: build-runtime.sh [all|fetch|build]
#   fetch  Network phase: check out the pinned Paddle Lite commit and prepare
#          the tree (F-Droid `prebuild`).
#   build  Offline phase: compile the tiny-publish runtime, copy the runtime and
#          headers into the module and write build/source-output/PROVENANCE
#          (F-Droid `build`, after the source scan).
#   all    fetch + build (default).
set -euo pipefail

phase="${1:-all}"
case "$phase" in
  all | fetch | build) ;;
  *)
    echo "Usage: $0 [all|fetch|build]" >&2
    exit 2
    ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock="$root/source-runtime.lock.json"
source_dir="$root/build/source/paddle-lite"
output_dir="$root/build/source-output"
runtime="$root/native/arm64-v8a/libpaddle_light_api_shared.so"
headers="$root/src/main/cpp/third_party/paddle_lite"

require() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null || {
      echo "Required host tool is unavailable: $tool" >&2
      exit 1
    }
  done
}

lock_value() {
  python3 - "$lock" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for key in sys.argv[2].split("."):
    value = value[key]
print(" ".join(value) if isinstance(value, list) else value)
PY
}

require git python3 sha256sum
repository="$(lock_value source.repository)"
commit="$(lock_value source.commit)"

fetch() {
  mkdir -p "$(dirname "$source_dir")"
  if [[ ! -d "$source_dir/.git" ]]; then
    git init -q "$source_dir"
    git -C "$source_dir" remote add origin "$repository"
  fi
  git -C "$source_dir" fetch --depth 1 origin "$commit"
  git -C "$source_dir" checkout -q --force --detach "$commit"
  git -C "$source_dir" clean -q -f -d -x
  if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
    echo "Paddle Lite checkout does not match the pinned commit $commit." >&2
    exit 1
  fi

  # Unused by the tiny-publish, Java-disabled build; contains a prebuilt
  # gradle-wrapper.jar that must not be present in the scanned source tree.
  local path
  for path in $(lock_value preparation.remove); do
    rm -rf "${source_dir:?}/$path"
  done

  # flatbuffers.cmake is only used with -DLITE_UPDATE_FBS_HEAD=ON (the tiny
  # publish uses the in-tree pre-built headers), but pin it anyway so no
  # unverified CDN archive can ever be fetched from this tree.
  local flatbuffers_cmake="$source_dir/cmake/external/flatbuffers.cmake"
  local fb_repo fb_commit
  fb_repo="$(lock_value preparation.flatbuffers.repository)"
  fb_commit="$(lock_value preparation.flatbuffers.commit)"
  python3 - "$flatbuffers_cmake" "$fb_repo" "$fb_commit" <<'PY'
import sys
path, repo, commit = sys.argv[1:]
text = open(path, encoding="utf-8").read()
old_url = "    URL             https://paddlelite-data.bj.bcebos.com/third_party_libs/flatbuffers-1.12.0.zip\n"
old_tag = '    GIT_TAG         "v1.12.0"\n'
if old_url not in text or old_tag not in text:
    raise SystemExit("Unexpected upstream flatbuffers.cmake; refusing to continue.")
text = text.replace(old_url, f'    GIT_REPOSITORY  "{repo}"\n')
text = text.replace(old_tag, f'    GIT_TAG         "{commit}"\n')
open(path, "w", encoding="utf-8").write(text)
PY
  if grep -rIl --include='*.cmake' --include='CMakeLists.txt' 'flatbuffers-1.12.0.zip' "$source_dir" >/dev/null; then
    echo "Unpinned flatbuffers download remains in the Paddle Lite tree." >&2
    exit 1
  fi

  # lite/tools/cmake_tools/ast.py shadows the standard-library `ast` module,
  # which breaks `import logging` on newer Python 3 releases. Rename it.
  python3 - "$source_dir/lite/tools/cmake_tools" <<'PY'
import pathlib, re, sys
tools = pathlib.Path(sys.argv[1])
(tools / "ast.py").rename(tools / "lite_ast.py")
for script in tools.glob("*.py"):
    text = script.read_text(encoding="utf-8")
    patched = re.sub(r"^from ast import ", "from lite_ast import ", text, flags=re.M)
    if patched != text:
        script.write_text(patched, encoding="utf-8")
PY
  if grep -rIl --include='*.py' '^from ast import\|^import ast$' "$source_dir/lite/tools/cmake_tools" >/dev/null; then
    echo "Paddle Lite cmake_tools still import the shadowing ast module." >&2
    exit 1
  fi
  git -C "$source_dir" diff --stat
}

build() {
  require bash cmake make sha256sum
  local ndk="${NDK_ROOT:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}}"
  if [[ -z "$ndk" || ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
    echo "Set NDK_ROOT (or ANDROID_NDK_ROOT/ANDROID_NDK_HOME) to the pinned Android NDK." >&2
    exit 1
  fi
  local ndk_revision expected_ndk
  ndk_revision="$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk/source.properties" | tr -d '\r')"
  expected_ndk="$(lock_value build.ndkRevision)"
  if [[ "$ndk_revision" != "$expected_ndk" ]]; then
    echo "NDK $ndk_revision does not match pinned NDK $expected_ndk." >&2
    exit 1
  fi
  if [[ ! -d "$source_dir/.git" || "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
    echo "Pinned Paddle Lite source is missing; run '$0 fetch' first." >&2
    exit 1
  fi

  local arch toolchain publish_root build_dir
  arch="$(lock_value build.arch)"
  toolchain="$(lock_value build.toolchain)"
  build_dir="$source_dir/build.lite.android.$arch.$toolchain"
  publish_root="$build_dir/$(lock_value build.publishDirectory)"
  local -a arguments
  read -r -a arguments <<<"$(lock_value build.arguments)"

  # build_android.sh runs `egrep -o "android-ndk-r[0-9]{2}"` on NDK_ROOT under
  # `set -e`, so an SDK-style path such as ndk/28.2.13676358 aborts it silently.
  local ndk_link
  ndk_link="$root/build/ndk/android-ndk-$(lock_value build.ndkRelease)"
  mkdir -p "$(dirname "$ndk_link")"
  ln -sfn "$ndk" "$ndk_link"

  rm -rf "$output_dir"
  mkdir -p "$output_dir" "$headers" "$(dirname "$runtime")"
  (
    cd "$source_dir"
    export NDK_ROOT="$ndk_link"
    export LITE_BUILD_THREADS="${LITE_BUILD_THREADS:-$(nproc)}"
    # Paddle Lite declares cmake_minimum_required(VERSION 3.0); only CMake 4
    # needs this (CMake 3.31 on the F-Droid buildserver ignores it).
    export CMAKE_POLICY_VERSION_MINIMUM="${CMAKE_POLICY_VERSION_MINIMUM:-3.5}"
    ./lite/tools/build_android.sh "${arguments[@]}"
  )

  if find "$build_dir" -path '*-stamp/*-download' -print -quit | grep -q .; then
    echo "Paddle Lite build used an ExternalProject download; this is not allowed." >&2
    exit 1
  fi

  local built_runtime="$publish_root/cxx/lib/libpaddle_light_api_shared.so"
  if [[ ! -f "$built_runtime" ]]; then
    echo "Paddle Lite source build did not produce $built_runtime." >&2
    exit 1
  fi
  local -a required_headers
  read -r -a required_headers <<<"$(lock_value build.headers)"
  local header
  for header in "${required_headers[@]}"; do
    if [[ ! -f "$publish_root/cxx/include/$header" ]]; then
      echo "Paddle Lite source build did not produce cxx/include/$header." >&2
      exit 1
    fi
  done

  cp "$built_runtime" "$runtime"
  for header in "${required_headers[@]}"; do
    cp "$publish_root/cxx/include/$header" "$headers/$header"
  done

  local readelf_bin
  readelf_bin="$(command -v llvm-readelf || command -v readelf || true)"
  if [[ -z "$readelf_bin" ]]; then
    readelf_bin="$(find "$ndk/toolchains/llvm/prebuilt" -name llvm-readelf -type f -print -quit)"
  fi
  if [[ -z "$readelf_bin" ]]; then
    echo "llvm-readelf or readelf is required to validate the source-built ELF." >&2
    exit 1
  fi
  "$readelf_bin" -h "$runtime" | grep -Eq 'AArch64|ARM aarch64' || {
    echo "Source-built Paddle runtime is not an AArch64 ELF." >&2
    exit 1
  }
  local align
  while read -r align; do
    if (( align < 16384 )); then
      echo "Source-built Paddle runtime is not 16 KB page aligned." >&2
      exit 1
    fi
  done < <("$readelf_bin" -lW "$runtime" | awk '$1 == "LOAD" { print $NF }')

  (
    cd "$root"
    sha256sum \
      native/arm64-v8a/libpaddle_light_api_shared.so \
      "${required_headers[@]/#/src/main/cpp/third_party/paddle_lite/}"
  ) | tee "$output_dir/SHA256SUMS"
  {
    printf 'source=%s\n' "$repository"
    printf 'commit=%s\n' "$commit"
    printf 'ndk=%s\n' "$ndk_revision"
    printf 'arguments=%s\n' "${arguments[*]}"
  } > "$output_dir/PROVENANCE"
  cat "$output_dir/PROVENANCE"
}

case "$phase" in
  fetch) fetch ;;
  build) build ;;
  all)
    fetch
    build
    ;;
esac
