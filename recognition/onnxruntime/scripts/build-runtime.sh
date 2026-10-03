#!/usr/bin/env bash
# Build the pinned ONNX Runtime ARM64 CPU runtime and its Java (JNI) binding from source,
# with telemetry compiled out.
#
# Usage: build-runtime.sh [all|fetch|build]
#   fetch  Network phase (F-Droid `prebuild`): check out the pinned ONNX Runtime commit,
#          delete everything this build does not use, and download the pinned CMake
#          dependency archives (SHA-1 verified) into a mirror outside the source tree.
#   build  Offline phase (F-Droid `build`, after the source scan): build a host protoc from
#          source, build libonnxruntime.so and libonnxruntime4j_jni.so with the pinned NDK,
#          strip them, copy them and the matching Java API sources into the module and write
#          build/source-output/{PROVENANCE,SHA256SUMS}. The output is reproducible for a fixed
#          build path, toolchain and SOURCE_DATE_EPOCH; see build.expectedSha256.
#   all    fetch + build (default).
#
# FPINK_ORT_DEPS_DIR overrides the dependency mirror (default ~/.cache/fpink/onnxruntime-deps).
# FPINK_ORT_JOBS overrides the build parallelism (default: nproc).
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
source_dir="$root/build/source/onnxruntime"
work_dir="$root/build/source-work"
output_dir="$root/build/source-output"
deps_dir="${FPINK_ORT_DEPS_DIR:-$HOME/.cache/fpink/onnxruntime-deps}"
jobs="${FPINK_ORT_JOBS:-$(nproc)}"

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
if isinstance(value, dict):
    print("\n".join(f"{k} {v}" for k, v in sorted(value.items())))
elif isinstance(value, list):
    print("\n".join(str(item) for item in value))
else:
    print(value)
PY
}

# Prints "<url> <sha1>" for a dependency, as pinned by cmake/deps.txt of the checked-out commit.
dep_entry() {
  python3 - "$source_dir/cmake/deps.txt" "$1" <<'PY'
import sys
path, name = sys.argv[1:]
for line in open(path, encoding="utf-8"):
    fields = line.strip().split(";")
    if len(fields) == 3 and fields[0] == name:
        print(fields[1], fields[2])
        break
else:
    raise SystemExit(f"{name} is not pinned in cmake/deps.txt")
PY
}

require git python3 sha1sum sha256sum
repository="$(lock_value source.repository)"
commit="$(lock_value source.commit)"

mirror_path() {
  printf '%s/%s' "$deps_dir" "${1#https://}"
}

fetch() {
  require curl
  mkdir -p "$(dirname "$source_dir")"
  if [[ ! -d "$source_dir/.git" ]]; then
    git init -q "$source_dir"
    git -C "$source_dir" remote add origin "$repository"
  fi
  git -C "$source_dir" fetch --depth 1 origin "$commit"
  git -C "$source_dir" checkout -q --force --detach "$commit"
  git -C "$source_dir" clean -q -f -d -x
  if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
    echo "ONNX Runtime checkout does not match the pinned commit $commit." >&2
    exit 1
  fi

  # Keep only what this build reads, so the scanned tree has no test models, Gradle
  # wrapper jars or other prebuilt files, and no telemetry-only Java sources.
  python3 - "$source_dir" "$lock" <<'PY'
import json, pathlib, shutil, sys
tree = pathlib.Path(sys.argv[1])
preparation = json.load(open(sys.argv[2], encoding="utf-8"))["preparation"]
keep = set(preparation["keep"])

def prune(directory, prefix):
    for child in sorted(directory.iterdir()):
        relative = f"{prefix}{child.name}"
        if relative == ".git" or relative in keep:
            continue
        if child.is_dir() and not child.is_symlink() and any(k.startswith(relative + "/") for k in keep):
            prune(child, relative + "/")
        elif child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()

prune(tree, "")
for relative in preparation["remove"]:
    target = tree / relative
    if target.is_dir():
        shutil.rmtree(target)
    elif target.exists():
        target.unlink()
PY
  if [[ -n "$(git -C "$source_dir" status --porcelain | grep -v '^ D ' || true)" ]]; then
    echo "Preparing the ONNX Runtime tree changed more than deletions." >&2
    exit 1
  fi

  local name sha1 url pinned target
  while read -r name sha1; do
    read -r url pinned <<<"$(dep_entry "$name")"
    if [[ "$pinned" != "$sha1" ]]; then
      echo "$name: source-runtime.lock.json pins $sha1 but cmake/deps.txt pins $pinned." >&2
      exit 1
    fi
    target="$(mirror_path "$url")"
    if [[ -f "$target" ]] && echo "$sha1  $target" | sha1sum -c --quiet - 2>/dev/null; then
      continue
    fi
    mkdir -p "$(dirname "$target")"
    curl -fsSL --retry 3 -o "$target.part" "$url"
    echo "$sha1  $target.part" | sha1sum -c --quiet - || {
      echo "$name: downloaded archive does not match SHA-1 $sha1." >&2
      exit 1
    }
    mv "$target.part" "$target"
  done < <(lock_value dependencies.archives)
  echo "Dependency mirror ready in $deps_dir"
}

# Verifies a mirrored archive and prints its path.
archive() {
  local url sha1 target
  read -r url sha1 <<<"$(dep_entry "$1")"
  target="$(mirror_path "$url")"
  echo "$sha1  $target" | sha1sum -c --quiet - >&2 || {
    echo "Missing or corrupt $1 archive in $deps_dir; run '$0 fetch' first." >&2
    exit 1
  }
  printf '%s' "$target"
}

build_host_protoc() {
  local host="$work_dir/host-protoc"
  rm -rf "$host"
  mkdir -p "$host/protobuf" "$host/abseil"
  (cd "$host/protobuf" && cmake -E tar xf "$(archive protobuf)")
  (cd "$host/abseil" && cmake -E tar xf "$(archive abseil_cpp)")
  local protobuf_src abseil_src patch_file
  protobuf_src="$(find "$host/protobuf" -mindepth 1 -maxdepth 1 -type d -print -quit)"
  abseil_src="$(find "$host/abseil" -mindepth 1 -maxdepth 1 -type d -print -quit)"
  # The same patches, in the same order, that ONNX Runtime applies to its own protobuf.
  for patch_file in protobuf_android_log protobuf_msvc_unreachable_code \
    protobuf_msvc_map_unreachable_code protobuf_compiler_incomplete_type; do
    patch --binary --ignore-whitespace -p1 -d "$protobuf_src" \
      < "$source_dir/cmake/patches/protobuf/$patch_file.patch" >&2
  done
  # Protobuf fetches Abseil with FetchContent; serve it the verified archive and allow no
  # other download.
  cmake -G Ninja -S "$protobuf_src" -B "$host/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17 \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_INSTALL=OFF \
    -Dprotobuf_FORCE_FETCH_DEPENDENCIES=ON \
    -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
    -DFETCHCONTENT_SOURCE_DIR_ABSL="$abseil_src" \
    -DABSL_PROPAGATE_CXX_STD=ON >&2
  cmake --build "$host/build" --target protoc --parallel "$jobs" >&2
  printf '%s' "$host/build/protoc"
}

build() {
  require cmake ninja patch c++ javac
  local ndk="${NDK_ROOT:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}}"
  if [[ -z "$ndk" || ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
    echo "Set NDK_ROOT (or ANDROID_NDK_ROOT/ANDROID_NDK_HOME) to the pinned Android NDK." >&2
    exit 1
  fi
  ndk="$(cd "$ndk" && pwd -P)"
  local ndk_revision expected_ndk
  ndk_revision="$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk/source.properties" | tr -d '\r')"
  expected_ndk="$(lock_value build.ndkRevision)"
  if [[ "$ndk_revision" != "$expected_ndk" ]]; then
    echo "NDK $ndk_revision does not match pinned NDK $expected_ndk." >&2
    exit 1
  fi
  if [[ ! -d "$source_dir/.git" || "$(git -C "$source_dir" rev-parse HEAD)" != "$commit" ]]; then
    echo "Pinned ONNX Runtime source is missing; run '$0 fetch' first." >&2
    exit 1
  fi
  if [[ -n "$(git -C "$source_dir" status --porcelain | grep -v '^ D ' || true)" ]]; then
    echo "The ONNX Runtime tree has changes other than the fetch-phase deletions." >&2
    exit 1
  fi

  local abi api
  abi="$(lock_value build.abi)"
  api="$(lock_value build.androidApi)"
  local -a defines
  mapfile -t defines < <(lock_value build.cmakeDefines)

  rm -rf "$work_dir" "$output_dir"
  mkdir -p "$work_dir" "$output_dir"

  # Reproducible builds (F-Droid rebuilds this runtime and compares the APK against the
  # upstream-signed release): timestamps from the pinned ONNX Runtime commit (not the
  # caller's SOURCE_DATE_EPOCH, so the pinned hashes hold for every fpink commit), no
  # build paths in __FILE__/debug info, and no lld build ID.
  SOURCE_DATE_EPOCH="$(git -C "$source_dir" log -1 --format=%ct)"
  export SOURCE_DATE_EPOCH
  local ort_build="$work_dir/ort"
  local prefix_map="-ffile-prefix-map=$source_dir=/onnxruntime -ffile-prefix-map=$ort_build=/onnxruntime-build -ffile-prefix-map=$ndk=/ndk"
  local link_flags="-Wl,--build-id=none"

  local protoc
  protoc="$(build_host_protoc)"
  [[ -x "$protoc" ]] || {
    echo "Host protoc build did not produce $protoc." >&2
    exit 1
  }

  local -a cmake_args=(
    -G Ninja
    -S "$source_dir/cmake"
    -B "$ort_build"
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake"
    "-DONNX_CUSTOM_PROTOC_EXECUTABLE=$protoc"
    "-Donnxruntime_CMAKE_DEPS_MIRROR_DIR=$deps_dir"
    "-DPython_EXECUTABLE=$(command -v python3)"
    "-DCMAKE_C_FLAGS=$prefix_map"
    "-DCMAKE_CXX_FLAGS=$prefix_map"
    "-DCMAKE_SHARED_LINKER_FLAGS=$link_flags"
  )
  local define
  for define in "${defines[@]}"; do
    cmake_args+=("-D$define")
  done
  cmake "${cmake_args[@]}" 2>&1 | tee "$work_dir/configure.log"

  # Every dependency must come from the verified mirror; nothing may be downloaded here.
  local fetched_remote
  fetched_remote="$(grep -E '^-- Fetch [^ ]+ from ' "$work_dir/configure.log" | grep -v -F " from $deps_dir/" || true)"
  if [[ -n "$fetched_remote" ]]; then
    echo "ONNX Runtime configure fetched dependencies outside the verified mirror:" >&2
    echo "$fetched_remote" >&2
    exit 1
  fi

  cmake --build "$ort_build" --target onnxruntime --parallel "$jobs"
  local core="$ort_build/libonnxruntime.so"
  [[ -f "$core" ]] || {
    echo "ONNX Runtime build did not produce $core." >&2
    exit 1
  }

  # JNI binding: what ONNX Runtime's onnxruntime_java.cmake builds, without its nested
  # Gradle build (which would download Gradle and plugins).
  local java_src="$source_dir/java/src/main"
  local -a java_files
  mapfile -t java_files < <(find "$java_src/java" "$java_src/jvm" -name '*.java' | LC_ALL=C sort)
  javac -h "$work_dir/jni-headers" -d "$work_dir/jni-classes" -encoding UTF-8 "${java_files[@]}"
  local clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
  local target="--target=aarch64-linux-android$api"
  local -a objects=()
  local source object
  mkdir -p "$work_dir/jni-objects"
  while read -r source; do
    object="$work_dir/jni-objects/$(basename "${source%.c}").o"
    "$clang" "$target" -std=c11 -O3 -DNDEBUG -fPIC $prefix_map \
      -ffile-prefix-map="$work_dir=/onnxruntime-build" \
      -I"$source_dir/include" \
      -I"$source_dir/include/onnxruntime/core/session" \
      -I"$source_dir/orttraining/orttraining/training_api/include" \
      -I"$work_dir/jni-headers" \
      -I"$ort_build" \
      -c "$source" -o "$object"
    objects+=("$object")
  done < <(find "$java_src/native" -name '*.c' | LC_ALL=C sort)
  local jni="$work_dir/libonnxruntime4j_jni.so"
  "$clang" "$target" -shared -o "$jni" "${objects[@]}" \
    -Wl,-soname,libonnxruntime4j_jni.so $link_flags -Wl,--no-undefined \
    -L"$ort_build" -lonnxruntime

  local native_dir="$root/native/$abi"
  mkdir -p "$native_dir"
  cp "$core" "$native_dir/libonnxruntime.so"
  cp "$jni" "$native_dir/libonnxruntime4j_jni.so"

  # Strip with the pinned NDK so the shipped bytes do not depend on debug info. The app
  # packages these files with keepDebugSymbols, so AGP never strips them a second time.
  local bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
  local -a outputs
  mapfile -t outputs < <(lock_value build.outputs)
  local output align needed forbidden
  for output in "${outputs[@]}"; do
    "$bin/llvm-strip" --strip-unneeded "$root/$output"
    "$bin/llvm-readelf" -h "$root/$output" | grep -Eq 'AArch64' || {
      echo "$output is not an AArch64 ELF." >&2
      exit 1
    }
    while read -r align; do
      if (( align < 16384 )); then
        echo "$output is not 16 KB page aligned." >&2
        exit 1
      fi
    done < <("$bin/llvm-readelf" -lW "$root/$output" | awk '$1 == "LOAD" { print $NF }')
    while read -r needed; do
      case "$needed" in
        libc.so | libm.so | libdl.so | liblog.so | libonnxruntime.so) ;;
        *)
          echo "$output links unexpected library $needed." >&2
          exit 1
          ;;
      esac
    done < <("$bin/llvm-readelf" -dW "$root/$output" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    while read -r forbidden; do
      if grep -aqF "$forbidden" "$root/$output"; then
        echo "$output contains telemetry string '$forbidden'." >&2
        exit 1
      fi
    done < <(lock_value build.forbiddenStrings)
  done

  # The Java API that matches these libraries, minus the telemetry-only sources.
  local java_to
  java_to="$root/$(lock_value build.javaSources.to)"
  rm -rf "$java_to"
  mkdir -p "$java_to"
  local from
  while read -r from; do
    cp -R "$source_dir/$from/." "$java_to/"
  done < <(lock_value build.javaSources.from)

  # Licence texts packaged into the APK next to the runtime.
  local notices_to notice
  notices_to="$(lock_value build.notices.to)"
  rm -rf "${root:?}/$notices_to"
  mkdir -p "$root/$notices_to"
  local -a notices=()
  while read -r notice; do
    cp "$source_dir/$notice" "$root/$notices_to/$notice"
    notices+=("$notices_to/$notice")
  done < <(lock_value build.notices.from)

  (
    cd "$root"
    sha256sum "${outputs[@]}" "${notices[@]}"
    printf '%s  %s\n' "$(java_sources_sha256 "$java_to")" "$(lock_value build.javaSources.to)"
  ) | tee "$output_dir/SHA256SUMS"
  # Gradle's -PortRuntimeBuiltFromSource enforces build.expectedSha256; report early.
  python3 - "$lock" "$output_dir/SHA256SUMS" <<'PY'
import json, sys
expected = json.load(open(sys.argv[1], encoding="utf-8"))["build"].get("expectedSha256")
actual = {}
for line in open(sys.argv[2], encoding="utf-8"):
    if line.strip():
        digest, path = line.rstrip("\n").split("  ", 1)
        actual[path] = digest
if expected is None:
    print("source-runtime.lock.json does not pin build.expectedSha256 yet; this build produced:")
    print(json.dumps(actual, indent=2, sort_keys=True))
elif expected != actual:
    print("WARNING: the source-built ONNX Runtime differs from build.expectedSha256 in "
          "source-runtime.lock.json; the build is not reproducible on this host.", file=sys.stderr)
    for path in sorted(set(expected) | set(actual)):
        print(f"  {path}: expected {expected.get(path)}, built {actual.get(path)}", file=sys.stderr)
PY
  {
    printf 'source=%s\n' "$repository"
    printf 'commit=%s\n' "$commit"
    printf 'ndk=%s\n' "$ndk_revision"
    printf 'cmakeDefines=%s\n' "${defines[*]}"
  } > "$output_dir/PROVENANCE"
  cat "$output_dir/PROVENANCE"
}

# SHA-256 of "<sha256>  <relative path>\n" for every file, sorted by path (Gradle computes the same).
java_sources_sha256() {
  (
    cd "$1"
    find . -type f | sed 's|^\./||' | LC_ALL=C sort | while read -r file; do
      printf '%s  %s\n' "$(sha256sum "$file" | cut -d' ' -f1)" "$file"
    done
  ) | sha256sum | cut -d' ' -f1
}

case "$phase" in
  fetch) fetch ;;
  build) build ;;
  all)
    fetch
    build
    ;;
esac
