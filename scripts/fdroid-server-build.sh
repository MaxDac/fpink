#!/usr/bin/env bash
# Build the checked-out commit with the real `fdroid build --on-server`, like
# fdroiddata's `fdroid build` CI job, to confirm that scripts/fdroid_rb_build.py
# replays F-Droid's build faithfully.
#
# Usage (on a Docker host): scripts/fdroid-server-build.sh OUTPUT_DIR
# The commit must be fetchable from the recipe's Repo (pushed to GitHub).
set -euo pipefail

readonly image="$(sed -n 's/^readonly default_image="\(.*\)"$/\1/p' "$(dirname "${BASH_SOURCE[0]}")/fdroid-rb-docker.sh")"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$1"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
commit="$(git -C "$root" rev-parse HEAD)"

docker run --rm --volume "$root:/src:ro" --volume "$output:/out" --env "COMMIT=$commit" \
  "${FDROID_BUILDSERVER_IMAGE:-$image}" bash -euo pipefail -c '
set +u; source /etc/profile.d/bsenv.sh; set -u
export ANDROID_HOME=/opt/android-sdk
apt-get update && apt-get -y dist-upgrade
sdkmanager "platform-tools" "build-tools;31.0.0"
rm -rf "$fdroidserver" && mkdir "$fdroidserver"
git ls-remote https://gitlab.com/fdroid/fdroidserver.git master
curl --silent --fail https://gitlab.com/fdroid/fdroidserver/-/archive/master/fdroidserver-master.tar.gz \
  | tar -xz --directory="$fdroidserver" --strip-components=1
git -C "$home_vagrant/gradlew-fdroid" pull

# Like fdroiddata CI: run from $home_vagrant, so the app is built in
# /home/vagrant/build/com.fpink.capture as on the production buildserver.
data="$home_vagrant"
mkdir -p "$data/metadata" "$data/build" "$data/tmp" "$data/unsigned" "$data/logs"
version_name="$(sed -n "s/^versionName=//p" /src/version.properties)"
version_code="$(sed -n "s/^versionCode=//p" /src/version.properties)"
# The last build block of the recipe, pinned to this commit and version. Binaries and
# the auto-update fields are dropped: there is no signed release to compare against.
python3 - "$data/metadata/com.fpink.capture.yml" <<PY
import sys, yaml
recipe = yaml.safe_load(open("/src/metadata/com.fpink.capture.yml"))
build = recipe["Builds"][-1]
build.update(versionName="$version_name", versionCode=int("$version_code"), commit="$COMMIT")
recipe["Builds"] = [build]
for key in ("Binaries", "AllowedAPKSigningKeys", "AutoUpdateMode", "UpdateCheckMode", "UpdateCheckData"):
    recipe.pop(key, None)
recipe.update(CurrentVersion="$version_name", CurrentVersionCode=int("$version_code"))
yaml.safe_dump(recipe, open(sys.argv[1], "w"), sort_keys=False)
PY
cat "$data/metadata/com.fpink.capture.yml"

apt-get install -y sudo openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
for d in "$home_vagrant/.android" "$home_vagrant/.gradle"; do mkdir -p "$d"; done
chown -R vagrant "$data" "$home_vagrant"
export GRADLE_USER_HOME="$home_vagrant/.gradle"
cd "$data"
fdroid() {
  sudo --preserve-env --user vagrant env PATH="$fdroidserver:$PATH" \
    PYTHONPATH="$fdroidserver:$fdroidserver/examples" PYTHONUNBUFFERED=true HOME="$home_vagrant" fdroid "$@"
}
fdroid fetchsrclibs "com.fpink.capture:$version_code" --verbose
(unset CI; fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "com.fpink.capture:$version_code")
cp "tmp/com.fpink.capture_${version_code}.apk" /out/unsigned.apk
(cd /out && sha256sum unsigned.apk > SHA256SUMS)
chmod -R a+rwX /out
'
