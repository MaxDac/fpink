#!/usr/bin/env bash
# Run scripts/fdroid_rb_build.py in the pinned F-Droid buildserver image.
#
# Usage: scripts/fdroid-rb-docker.sh OUTPUT_DIR [fdroid_rb_build.py options...]
# Environment: LITE_BUILD_THREADS (optional, to vary the build when checking
# reproducibility), FDROID_BUILDSERVER_IMAGE (override the pinned image).
#
# The image is the one fdroiddata's `fdroid build` CI job uses. Keep the digest
# in sync with docs/FDROID_VALIDATION.md when bumping it.
set -euo pipefail

readonly default_image="registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8"
image="${FDROID_BUILDSERVER_IMAGE:-$default_image}"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 OUTPUT_DIR [fdroid_rb_build.py options...]" >&2
  exit 2
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$1"
shift
mkdir -p "$output"
output="$(cd "$output" && pwd)"

if [[ "$(git -C "$root" rev-parse --is-shallow-repository)" == true ]]; then
  echo "The checkout is shallow; fetch full history (actions/checkout fetch-depth: 0)." >&2
  exit 1
fi

docker run --rm \
  --volume "$root:/src:ro" \
  --volume "$output:/out" \
  ${LITE_BUILD_THREADS:+--env "LITE_BUILD_THREADS=$LITE_BUILD_THREADS"} \
  "$image" \
  python3 /src/scripts/fdroid_rb_build.py --output /out "$@"
