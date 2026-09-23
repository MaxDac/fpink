#!/usr/bin/env bash
set -euo pipefail

# This script intentionally runs only on Linux because F-Droid's builders are
# Linux hosts. It never substitutes the current checked-in binary artifacts.

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$root/reproducibility.lock.json"
verify="$root/scripts/verify-reproducibility.py"
work_directory="${1:-$root/build/reproducibility}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Source reproduction must run on Linux." >&2
  exit 1
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME must identify the pinned Android NDK." >&2
  exit 1
fi
for command in git curl python3 sha256sum; do
  command -v "$command" >/dev/null || {
    echo "Required command is unavailable: $command" >&2
    exit 1
  }
done

python3 "$verify" --manifest "$manifest" --download-inputs "$work_directory/models"
mkdir -p "$work_directory"

python3 - "$manifest" "$work_directory" <<'PY'
import json
import sys
from pathlib import Path

manifest_path, work_directory = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
work_directory.mkdir(parents=True, exist_ok=True)
runtime = manifest["runtime"]
(work_directory / "runtime-commit").write_text(runtime["source"]["commit"] + "\n", encoding="utf-8")
(work_directory / "runtime-command").write_text(
    "\0".join(runtime["command"]) + "\0", encoding="utf-8")
for model in manifest["models"]:
    (work_directory / f"{model['name']}-command").write_text(
        "\0".join(model["conversion"]["command"]) + "\0", encoding="utf-8")
PY

runtime_commit="$(cat "$work_directory/runtime-commit")"
runtime_source="$work_directory/Paddle-Lite"
if [[ ! -d "$runtime_source/.git" ]]; then
  git clone --no-checkout https://github.com/PaddlePaddle/Paddle-Lite.git "$runtime_source"
fi
git -C "$runtime_source" fetch --depth=1 origin "$runtime_commit"
git -C "$runtime_source" checkout --detach "$runtime_commit"

python3 - "$manifest" "$runtime_source" <<'PY'
import hashlib
import json
import sys
from pathlib import Path
from urllib.request import urlopen

manifest_path, runtime_source = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
archive = manifest["runtime"]["thirdPartyArchive"]
destination = runtime_source / archive["file"]
with urlopen(archive["url"]) as response, destination.open("wb") as stream:
    while chunk := response.read(65536):
        stream.write(chunk)
digest = hashlib.file_digest(destination.open("rb"), "sha256").hexdigest()
if destination.stat().st_size != archive["bytes"] or digest != archive["sha256"]:
    raise SystemExit("Paddle-Lite third-party archive does not match the pinned provenance.")
PY

mapfile -d '' runtime_command < "$work_directory/runtime-command"
(
  cd "$runtime_source"
  NDK_ROOT="$ANDROID_NDK_HOME" LITE_BUILD_THREADS="${LITE_BUILD_THREADS:-4}" \
    "${runtime_command[@]}"
)

runtime_file="$(find "$runtime_source" -path '*/cxx/lib/libpaddle_light_api_shared.so' -type f -print -quit)"
if [[ -z "$runtime_file" ]]; then
  echo "Paddle-Lite source build did not produce libpaddle_light_api_shared.so." >&2
  exit 1
fi
cp "$runtime_file" "$work_directory/libpaddle_light_api_shared.so"

(
  cd "$runtime_source"
  LITE_BUILD_THREADS="${LITE_BUILD_THREADS:-4}" ./lite/tools/build.sh opt
)
optimizer="$(find "$runtime_source/build.opt" -type f -name opt -print -quit)"
if [[ -z "$optimizer" ]]; then
  echo "Paddle-Lite source build did not produce the opt executable." >&2
  exit 1
fi

for model in PP-OCRv5_mobile_det PP-OCRv5_mobile_rec; do
  mapfile -d '' model_command < "$work_directory/$model-command"
  expanded=()
  for argument in "${model_command[@]}"; do
    expanded+=("${argument/\{model\}/$work_directory/models/$model}")
  done
  for index in "${!expanded[@]}"; do
    expanded[$index]="${expanded[$index]/\{output\}/$work_directory}"
  done
  expanded[0]="$optimizer"
  "${expanded[@]}"
done

python3 "$verify" --manifest "$manifest" --first "$work_directory" --second "$work_directory"
echo "One source build completed at $work_directory. Repeat in an empty directory, then compare both outputs:"
echo "  python3 '$verify' --manifest '$manifest' --first '$work_directory' --second <second-build>"
