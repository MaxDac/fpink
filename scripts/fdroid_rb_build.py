#!/usr/bin/env python3
"""Build the unsigned release APK the way F-Droid builds it, for reproducible builds.

F-Droid publishes our signed APK only if its own build of the tagged commit is
byte-identical apart from the signature (recipe fields Binaries and
AllowedAPKSigningKeys). This script replays the last build block of
metadata/com.fpink.capture.yml like fdroidserver's `fdroid build --on-server`
(common.prepare_source and build.build_local), inside the F-Droid buildserver
image, so our release APK and F-Droid's come from the same toolchain, commands,
environment and absolute paths.

Run it as root inside registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
from an fpink checkout; the checked-out HEAD is built. The output directory
receives unsigned.apk, the Paddle source-runtime SHA256SUMS and build-env.txt.
See docs/FDROID_VALIDATION.md for comparing two builds.

Intentional differences from fdroidserver, none of which reach the APK: no
source scanner, no removal of signingConfig lines, no source tarball, and
sudo stays installed.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys

APPLICATION_ID = "com.fpink.capture"
ROOT = Path(__file__).resolve().parent.parent
HOME = Path("/home/vagrant")
SDK = Path("/opt/android-sdk")
DEFAULT_BUILD_DIR = HOME / "build" / APPLICATION_ID
JAVA = Path("/usr/lib/jvm/java-21-openjdk-amd64")
# fdroidserver build.py deletes these before running `build` and Gradle.
CLEAN_DIRECTORIES = (
    "build/android-profile", "build/generated", "build/intermediates", "build/outputs",
    "build/reports", "build/tmp", "buildSrc/build", ".gradle",
)
GRADLE_FILES = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}


class RbBuildError(Exception):
    pass


def log(message: str):
    print(f"==> {message}", flush=True)


def run(arguments, **kwargs):
    printable = " ".join(shlex.quote(str(argument)) for argument in arguments)
    log(f"$ {printable}" + (f"   (in {kwargs['cwd']})" if "cwd" in kwargs else ""))
    subprocess.run([str(argument) for argument in arguments], check=True, **kwargs)


def load_recipe(path: Path):
    try:
        import yaml
        recipe = yaml.safe_load(path.read_text(encoding="utf-8"))
    except ImportError:
        from ruamel.yaml import YAML
        recipe = YAML(typ="safe").load(path.read_text(encoding="utf-8"))
    builds = recipe.get("Builds") or []
    if not builds:
        raise RbBuildError(f"{path} has no build block")
    build = builds[-1]

    def as_list(value):
        if value is None:
            return []
        return [str(item) for item in value] if isinstance(value, list) else [str(value)]

    for unsupported in ("init", "patch", "srclibs", "submodules", "preassemble", "postbuild",
                        "output", "forceversion", "forcevercode", "buildjni", "scandelete"):
        if build.get(unsupported):
            raise RbBuildError(f"The replay does not implement the recipe field {unsupported!r}")
    return {
        "subdir": str(build.get("subdir") or ""),
        "sudo": as_list(build.get("sudo")),
        "gradle": as_list(build.get("gradle")),
        "rm": as_list(build.get("rm")),
        "prebuild": as_list(build.get("prebuild")),
        "build": as_list(build.get("build")),
        "gradleprops": as_list(build.get("gradleprops")),
        "ndk": str(build["ndk"]),
    }


def bsenv():
    output = subprocess.run(
        ["bash", "-c", "set +u; source /etc/profile.d/bsenv.sh; env -0"],
        check=True, capture_output=True,
    ).stdout.decode()
    return dict(item.split("=", 1) for item in output.split("\0") if "=" in item)


def version_properties(root: Path):
    values = {}
    for line in (root / "version.properties").read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values["versionName"], values["versionCode"]


def substitute(command: str, ndk: Path, commit: str, version_name: str, version_code: str):
    # common.replace_config_vars / replace_build_vars
    for name, value in (("$$SDK$$", SDK), ("$$NDK$$", ndk), ("$$MVN3$$", "mvn"),
                        ("$$COMMIT$$", commit), ("$$VERCODE$$", version_code),
                        ("$$VERSION$$", version_name)):
        command = command.replace(name, str(value))
    if re.search(r"\$\$[A-Za-z0-9_]+\$\$", command):
        raise RbBuildError(f"Unsupported substitution in {command!r}")
    return command


def setup_system(recipe, env):
    # fdroiddata .gitlab-ci.yml `fdroid build` job.
    run(["apt-get", "update"])
    run(["apt-get", "-y", "dist-upgrade"])
    run(["apt-get", "install", "-y", "sudo", "openjdk-21-jdk-headless"])
    run(["update-alternatives", "--set", "java", JAVA / "bin/java"])
    run(["git", "-C", HOME / "gradlew-fdroid", "pull"])
    # common.auto_install_ndk: fdroidserver's sdkmanager, into $ANDROID_HOME/ndk/<revision>.
    run(["sdkmanager", f"ndk;{recipe['ndk']}"], env=env)
    # build.py runs the recipe's sudo: commands as root before anything else.
    if recipe["sudo"]:
        run(["bash", "-e", "-u", "-o", "pipefail", "-x", "-c", "; ".join(recipe["sudo"])],
            env={**env, "DEBIAN_FRONTEND": "noninteractive"})


def checkout(source: Path, build_dir: Path, commit: str):
    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.parent.mkdir(parents=True, exist_ok=True)
    run(["git", "clone", "--quiet", "--no-hardlinks", source, build_dir])
    run(["git", "-C", build_dir, "checkout", "--quiet", "-f", commit])
    run(["git", "-C", build_dir, "clean", "-dffx"])
    run(["chown", "-R", "vagrant:vagrant", build_dir])


def write_local_properties(build_dir: Path, subdir: str, ndk: Path):
    # common.prepare_source: the repo root and every subdir component.
    paths = [build_dir / "local.properties"]
    current = build_dir
    for part in [p for p in subdir.split("/") if p]:
        current = current / part
        paths.append(current / "local.properties")
    for path in paths:
        props = path.read_text(encoding="iso-8859-1") + "\n" if path.is_file() else ""
        props += f"sdk.dir={SDK}\nsdk-location={SDK}\nndk.dir={ndk}\nndk-location={ndk}\n"
        path.write_text(props, encoding="iso-8859-1")
        shutil.chown(path, "vagrant", "vagrant")


def clean_like_fdroidserver(build_dir: Path):
    for root, _dirs, files in os.walk(build_dir):
        if GRADLE_FILES & set(files):
            for directory in CLEAN_DIRECTORIES:
                shutil.rmtree(Path(root) / directory, ignore_errors=True)
            for name in ("gradlew", "gradlew.bat"):
                if name in files:
                    (Path(root) / name).unlink()


def build_environment(base, ndk: Path, epoch: str):
    # fdroid build runs `sudo --preserve-env --user vagrant env HOME=/home/vagrant fdroid`
    # with `CI` unset, then common.set_FDroidPopen_env adds the SDK/NDK variables.
    path = os.pathsep.join([str(ndk), base.get("PATH", "/usr/local/bin:/usr/bin:/bin")])
    env = {
        "PATH": path, "HOME": str(HOME), "USER": "vagrant", "LOGNAME": "vagrant",
        "LANG": base.get("LANG", "C.UTF-8"), "LC_ALL": base.get("LC_ALL", "C.UTF-8"),
        "DEBIAN_FRONTEND": "noninteractive", "home_vagrant": str(HOME),
        "fdroidserver": base.get("fdroidserver", str(HOME / "fdroidserver")),
        "GRADLE_USER_HOME": str(HOME / ".gradle"), "SOURCE_DATE_EPOCH": epoch,
        "JAVA21_HOME": str(JAVA),
    }
    for name in ("ANDROID_HOME", "ANDROID_SDK", "ANDROID_SDK_ROOT"):
        env[name] = str(SDK)
    for name in ("ANDROID_NDK", "NDK", "ANDROID_NDK_HOME"):
        env[name] = str(ndk)
    # Only for varying the build in reproducibility checks; F-Droid never sets it.
    if os.environ.get("LITE_BUILD_THREADS"):
        env["LITE_BUILD_THREADS"] = os.environ["LITE_BUILD_THREADS"]
    return env


def as_vagrant(arguments, cwd: Path, env):
    run(arguments, cwd=cwd, env=env, user="vagrant", group="vagrant", extra_groups=[])


def describe_environment(output: Path, env, build_dir: Path, ndk: Path):
    commands = {
        "image": ["bash", "-c", "cat /etc/buildserverid 2>/dev/null || true"],
        "java": ["java", "-version"],
        "gradle": ["bash", "-c", f"cd {shlex.quote(str(build_dir))} && git -C {HOME}/gradlew-fdroid rev-parse HEAD"],
        "cmake": ["cmake", "--version"],
        "ndk": ["cat", ndk / "source.properties"],
        "packages": ["dpkg-query", "-W", "openjdk-21-jdk-headless", "cmake", "binutils", "gcc", "g++", "make", "python3", "git"],
    }
    with open(output / "build-env.txt", "w", encoding="utf-8") as report:
        for name, command in commands.items():
            result = subprocess.run([str(c) for c in command], env=env, capture_output=True, text=True)
            report.write(f"## {name}\n{result.stdout}{result.stderr}\n")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-dir", type=Path, default=DEFAULT_BUILD_DIR,
                        help=f"Build path (default {DEFAULT_BUILD_DIR}, F-Droid's; paths can end up in binaries)")
    parser.add_argument("--output", type=Path, default=Path("rb-output"), help="Output directory")
    parser.add_argument("--recipe", type=Path, default=ROOT / "metadata" / f"{APPLICATION_ID}.yml")
    parser.add_argument("--skip-system-setup", action="store_true",
                        help="Skip apt/JDK/NDK setup (a second build in the same container)")
    parser.add_argument("--check-task", action="append", default=[], dest="check_tasks",
                        help="Gradle task to run after the APK is copied out (tests, lint, verification)")
    arguments = parser.parse_args(argv)
    try:
        if os.geteuid() != 0:
            raise RbBuildError("Run as root: the recipe's sudo: commands need it, like on the buildserver")
        if not Path("/etc/profile.d/bsenv.sh").is_file() or not (HOME / "gradlew-fdroid").is_dir():
            raise RbBuildError("Run inside registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie")
        # The mounted checkout belongs to the host user, and a local clone's upload-pack
        # ignores `git -c` and GIT_CONFIG_*; the container is disposable.
        run(["git", "config", "--system", "--replace-all", "safe.directory", "*"])
        recipe = load_recipe(arguments.recipe)
        commit = subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"],
                                check=True, capture_output=True, text=True).stdout.strip()
        base = bsenv()
        ndk = SDK / "ndk" / recipe["ndk"]
        system_env = {**base, "ANDROID_HOME": str(SDK)}
        system_env.pop("CI", None)
        if not arguments.skip_system_setup:
            setup_system(recipe, system_env)
        if not (ndk / "source.properties").is_file():
            raise RbBuildError(f"NDK {recipe['ndk']} is not installed at {ndk}")

        build_dir = arguments.build_dir.resolve()
        checkout(ROOT, build_dir, commit)
        root_dir = build_dir / recipe["subdir"] if recipe["subdir"] else build_dir
        version_name, version_code = version_properties(build_dir)
        epoch = subprocess.run(["git", "-C", build_dir, "log", "-n1", "--pretty=%ct"],
                               check=True, capture_output=True, text=True).stdout.strip()
        env = build_environment(base, ndk, epoch)

        write_local_properties(build_dir, recipe["subdir"], ndk)
        for path in recipe["rm"]:
            target = build_dir / path
            log(f"Removing {path}")
            if target.is_dir() and not target.is_symlink():
                shutil.rmtree(target)
            elif target.exists() or target.is_symlink():
                target.unlink()
        bash = ["bash", "-e", "-u", "-o", "pipefail", "-x", "-c"]
        if recipe["prebuild"]:
            command = substitute("; ".join(recipe["prebuild"]), ndk, commit, version_name, version_code)
            as_vagrant([*bash, command], root_dir, env)
        clean_like_fdroidserver(build_dir)
        if recipe["build"]:
            command = substitute("; ".join(recipe["build"]), ndk, commit, version_name, version_code)
            as_vagrant([*bash, command], root_dir, env)
        flavors = "" if recipe["gradle"] in ([], ["yes"]) else "".join(f[:1].upper() + f[1:] for f in recipe["gradle"])
        as_vagrant(["gradle", *("-P" + prop for prop in recipe["gradleprops"]), f"assemble{flavors}Release"],
                   root_dir, env)

        apks = sorted((root_dir / "build/outputs/apk").glob(f"*/release/*-release-unsigned.apk"))
        apks = [apk for apk in apks if apk.parent.parent.name.lower() == flavors.lower()] or apks
        if len(apks) != 1:
            raise RbBuildError(f"Expected exactly one unsigned release APK, found {apks}")
        output = arguments.output.resolve()
        output.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apks[0], output / "unsigned.apk")
        runtime_sums = build_dir / "recognition/paddle/build/source-output/SHA256SUMS"
        if runtime_sums.is_file():
            shutil.copyfile(runtime_sums, output / "paddle-runtime-SHA256SUMS")
        describe_environment(output, env, build_dir, ndk)
        digest = hashlib.sha256((output / "unsigned.apk").read_bytes()).hexdigest()
        (output / "SHA256SUMS").write_text(f"{digest}  unsigned.apk\n", encoding="utf-8")
        log(f"{APPLICATION_ID} {version_name} ({version_code}) from {commit}: unsigned.apk sha256 {digest}")
        if arguments.check_tasks:
            as_vagrant(["gradle", *("-P" + prop for prop in recipe["gradleprops"]), *arguments.check_tasks],
                       root_dir, env)
            if hashlib.sha256(apks[0].read_bytes()).hexdigest() != digest:
                raise RbBuildError("The check tasks rebuilt the APK differently")
        run(["chmod", "-R", "a+rwX", output])
    except (RbBuildError, subprocess.CalledProcessError, OSError, KeyError) as error:
        print(f"Reproducible build failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
