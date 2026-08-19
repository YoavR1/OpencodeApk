#!/usr/bin/env python3
"""Assemble the on-device OpenCode runtime into the Android project.

Produces two things the APK needs, neither of which is committed:

  apps/android/app/src/main/jniLibs/arm64-v8a/   binaries and their libraries
  apps/android/app/src/main/assets/runtime/      the server bundle and launcher

Why jniLibs rather than assets for the binaries: an app may not execute a file it
can write (W^X, measured in M6), so anything executable ships as `lib*.so` and
runs from `nativeLibraryDir`. Android extracts only names matching `lib*.so`, so
versioned libraries are renamed and every reference to them patched - elfpatch.py.

Git is bundled the same way. It looks up helpers by exact name, which `lib*.so`
cannot provide, so the app symlinks them at first launch - executing through a
symlink into nativeLibraryDir is permitted (verified on a device, M8). The names
to link are written to tools.json rather than duplicated in Kotlin.

Usage:
    python scripts/runtime/prepare-android-runtime.py [--skip-fetch]

Prerequisites:
    bun run --cwd packages/opencode script/build-node.ts
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from elfpatch import Elf, android_name, patch_file  # noqa: E402
from termux import fetch_packages  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
NODE_STAGING = ROOT / "spike" / "m6" / "staging" / "android-node"
GIT_STAGING = ROOT / "spike" / "m8" / "staging" / "git"
NODE_PREFIX = NODE_STAGING / "data" / "data" / "com.termux" / "files" / "usr"
GIT_PREFIX = GIT_STAGING / "data" / "data" / "com.termux" / "files" / "usr"
JNILIBS = ROOT / "apps" / "android" / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
ASSETS = ROOT / "apps" / "android" / "app" / "src" / "main" / "assets" / "runtime"
DIST = ROOT / "packages" / "opencode" / "dist" / "node"
LAUNCHER = ROOT / "apps" / "android" / "runtime" / "launch.mjs"
SHIM = ROOT / "spike" / "m6" / "shim" / "@lydell" / "node-pty"

# Provided by Bionic; never bundled.
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libstdc++.so"}

GIT_PACKAGES = ["git", "libcurl", "libexpat", "libiconv", "pcre2", "zlib", "openssl"]

# Executables to ship, as {name in jniLibs: path under the Termux prefix}.
#
# Only two of git's 181 helpers are worth shipping. 146 of them are hardlinks to
# the same `git` binary, which already contains every builtin - status, diff,
# branch, commit, add, log - and the rest are perl or shell scripts for workflows
# a phone will not run. git-remote-http is the one real addition, and it is also
# git-remote-https, which is a hardlink to it.
EXECUTABLES = {
    "libnode.so": (NODE_PREFIX, "bin/node"),
    "libgit.so": (GIT_PREFIX, "bin/git"),
    "libgit-remote-http.so": (GIT_PREFIX, "libexec/git-core/git-remote-http"),
}

# Symlinks the app creates so tools are found under the names they expect.
LINKS = {
    "node": "libnode.so",
    "git": "libgit.so",
    "git-remote-http": "libgit-remote-http.so",
    "git-remote-https": "libgit-remote-http.so",
}


def fetch_all() -> None:
    print("== fetching the Node runtime ==")
    subprocess.run(
        [sys.executable, str(ROOT / "spike" / "m6" / "fetch-node-android.py"), str(NODE_STAGING)],
        check=True,
    )
    print()
    print("== fetching git ==")
    fetch_packages(GIT_PACKAGES, GIT_STAGING)


def closure(binary: Path, libdirs: list[Path]) -> dict[str, Path]:
    """Every library the binary transitively needs, by original name."""
    found: dict[str, Path] = {}
    queue = [binary]
    while queue:
        current = queue.pop()
        for _, name in Elf(current).needed:
            if name in SYSTEM or name in found:
                continue
            candidate = next((d / name for d in libdirs if (d / name).exists()), None)
            if candidate is None:
                print(f"    {name}: not present, assuming the platform provides it")
                continue
            resolved = candidate.resolve()
            found[name] = resolved
            queue.append(resolved)
    return found


def stage_native() -> None:
    print()
    print("== binaries and libraries -> jniLibs ==")
    libdirs = [NODE_PREFIX / "lib", GIT_PREFIX / "lib"]

    libraries: dict[str, Path] = {}
    for name, (prefix, relative) in EXECUTABLES.items():
        source = prefix / relative
        if not source.exists():
            raise SystemExit(f"missing {source}; run without --skip-fetch")
        libraries.update(closure(source, libdirs))

    renames = {name: android_name(name) for name in libraries}
    renames.update({Path(rel).name: dest for dest, (_, rel) in EXECUTABLES.items()})

    if JNILIBS.exists():
        shutil.rmtree(JNILIBS)
    JNILIBS.mkdir(parents=True)

    total = 0
    for original, source in sorted(libraries.items()):
        destination = JNILIBS / renames[original]
        shutil.copy2(source, destination)
        total += destination.stat().st_size
        changes = patch_file(destination, renames)
        note = f"  ({len(changes)} patched)" if changes else ""
        arrow = "" if original == renames[original] else f" -> {renames[original]}"
        print(f"  {original}{arrow}  {destination.stat().st_size / 1e6:.1f} MB{note}")

    for name, (prefix, relative) in EXECUTABLES.items():
        destination = JNILIBS / name
        shutil.copy2(prefix / relative, destination)
        total += destination.stat().st_size
        changes = patch_file(destination, renames)
        print(f"  {relative} -> {name}  {destination.stat().st_size / 1e6:.1f} MB  ({len(changes)} patched)")

    print(f"  total native: {total / 1e6:.1f} MB")


def stage_assets() -> None:
    print()
    print("== server bundle -> assets ==")
    bundle = DIST / "node.js"
    if not bundle.exists():
        raise SystemExit(f"no bundle at {bundle}; run: bun run --cwd packages/opencode script/build-node.ts")

    if ASSETS.exists():
        shutil.rmtree(ASSETS)
    (ASSETS / "node_modules" / "@lydell").mkdir(parents=True)

    for source in [bundle, *sorted(DIST.glob("*.wasm"))]:
        destination = ASSETS / source.name
        shutil.copy2(source, destination)
        print(f"  {source.name}  {destination.stat().st_size / 1e6:.1f} MB")

    shutil.copy2(LAUNCHER, ASSETS / "launch.mjs")

    # The bundle's two externals. jsonc-parser is real and pure JavaScript; the
    # PTY shim exists because the bundle imports node-pty statically and there is
    # no Android build of it, so the server cannot even load without something at
    # that specifier.
    jsonc = next((ROOT / "node_modules" / ".bun").glob("jsonc-parser@*/node_modules/jsonc-parser"), None)
    if jsonc is None:
        raise SystemExit("jsonc-parser not found; run bun install first")
    shutil.copytree(jsonc, ASSETS / "node_modules" / "jsonc-parser")
    shutil.copytree(SHIM, ASSETS / "node_modules" / "@lydell" / "node-pty")

    # The link table lives with the artifacts rather than in Kotlin, so adding a
    # tool is one edit here instead of two in different languages.
    (ASSETS / "tools.json").write_text(json.dumps({"links": LINKS}, indent=2), encoding="utf-8")

    size = sum(f.stat().st_size for f in ASSETS.rglob("*") if f.is_file())
    print(f"  total assets: {size / 1e6:.1f} MB")


def main() -> None:
    if "--skip-fetch" not in sys.argv:
        fetch_all()
    stage_native()
    stage_assets()
    print()
    print("ready. `./gradlew assembleDebug` will now package the runtime.")


if __name__ == "__main__":
    main()
