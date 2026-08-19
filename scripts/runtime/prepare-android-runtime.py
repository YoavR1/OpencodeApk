#!/usr/bin/env python3
"""Assemble the on-device OpenCode runtime into the Android project.

Produces two things the APK needs, neither of which is committed:

  apps/android/app/src/main/jniLibs/arm64-v8a/   the Node binary and its libraries
  apps/android/app/src/main/assets/runtime/      the server bundle and launcher

Why jniLibs rather than assets for the binary: an app may not execute a file it
can write (W^X, measured in M6), so the runtime has to ship as `lib*.so` and run
from `nativeLibraryDir`. Android extracts only names matching `lib*.so`, so
versioned libraries are renamed and every reference to them patched - see
elfpatch.py.

The server bundle is data, not code to execute, so it lives in assets and is
copied to filesDir at first launch.

Usage:
    python scripts/runtime/prepare-android-runtime.py [--skip-fetch]

Prerequisites:
    bun run --cwd packages/opencode script/build-node.ts
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from elfpatch import Elf, android_name, patch_file  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
STAGING = ROOT / "spike" / "m6" / "staging" / "android-node"
PREFIX = STAGING / "data" / "data" / "com.termux" / "files" / "usr"
JNILIBS = ROOT / "apps" / "android" / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
ASSETS = ROOT / "apps" / "android" / "app" / "src" / "main" / "assets" / "runtime"
DIST = ROOT / "packages" / "opencode" / "dist" / "node"
LAUNCHER = ROOT / "apps" / "android" / "runtime" / "launch.mjs"
SHIM = ROOT / "spike" / "m6" / "shim" / "@lydell" / "node-pty"

# Provided by Bionic; never bundled.
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libstdc++.so"}


def fetch_runtime() -> None:
    print("== fetching the runtime ==")
    subprocess.run(
        [sys.executable, str(ROOT / "spike" / "m6" / "fetch-node-android.py"), str(STAGING)],
        check=True,
    )


def closure(binary: Path, libdir: Path) -> dict[str, Path]:
    """Every library the binary transitively needs, by original name."""
    found: dict[str, Path] = {}
    queue = [binary]
    while queue:
        current = queue.pop()
        for _, name in Elf(current).needed:
            if name in SYSTEM or name in found:
                continue
            candidate = libdir / name
            if not candidate.exists():
                print(f"  {name}: not present, assuming the platform provides it")
                continue
            resolved = candidate.resolve()
            found[name] = resolved
            queue.append(resolved)
    return found


def stage_native() -> dict[str, str]:
    print("\n== native runtime -> jniLibs ==")
    node = PREFIX / "bin" / "node"
    if not node.exists():
        raise SystemExit(f"no runtime at {node}; run without --skip-fetch")

    libs = closure(node, PREFIX / "lib")
    renames = {name: android_name(name) for name in libs}
    renames["node"] = "libnode.so"

    if JNILIBS.exists():
        shutil.rmtree(JNILIBS)
    JNILIBS.mkdir(parents=True)

    total = 0
    for original, source in sorted(libs.items()):
        destination = JNILIBS / renames[original]
        shutil.copy2(source, destination)
        total += destination.stat().st_size
        changes = patch_file(destination, renames)
        note = f"  ({len(changes)} reference{'s' if len(changes) != 1 else ''} patched)" if changes else ""
        arrow = "" if original == renames[original] else f" -> {renames[original]}"
        print(f"  {original}{arrow}  {destination.stat().st_size / 1e6:.1f} MB{note}")

    destination = JNILIBS / "libnode.so"
    shutil.copy2(node, destination)
    total += destination.stat().st_size
    changes = patch_file(destination, renames)
    print(f"  node -> libnode.so  {destination.stat().st_size / 1e6:.1f} MB  ({len(changes)} references patched)")

    print(f"  total native: {total / 1e6:.1f} MB")
    return renames


def stage_assets() -> None:
    print("\n== server bundle -> assets ==")
    bundle = DIST / "node.js"
    if not bundle.exists():
        raise SystemExit(f"no bundle at {bundle}; run: bun run --cwd packages/opencode script/build-node.ts")

    if ASSETS.exists():
        shutil.rmtree(ASSETS)
    (ASSETS / "node_modules" / "@lydell").mkdir(parents=True)

    total = 0
    for source in [bundle, *sorted(DIST.glob("*.wasm"))]:
        destination = ASSETS / source.name
        shutil.copy2(source, destination)
        total += destination.stat().st_size
        print(f"  {source.name}  {destination.stat().st_size / 1e6:.1f} MB")

    shutil.copy2(LAUNCHER, ASSETS / "launch.mjs")

    # The bundle's two externals. jsonc-parser is real and pure JavaScript; the
    # PTY shim exists because the bundle imports node-pty statically and there is
    # no Android build of it, so the server cannot even load without something at
    # that specifier. Terminals are unavailable until M8 (ADR-0022).
    jsonc = next((ROOT / "node_modules" / ".bun").glob("jsonc-parser@*/node_modules/jsonc-parser"), None)
    if jsonc is None:
        raise SystemExit("jsonc-parser not found; run bun install first")
    shutil.copytree(jsonc, ASSETS / "node_modules" / "jsonc-parser")
    shutil.copytree(SHIM, ASSETS / "node_modules" / "@lydell" / "node-pty")

    size = sum(f.stat().st_size for f in ASSETS.rglob("*") if f.is_file())
    print(f"  total assets: {size / 1e6:.1f} MB")


def main() -> None:
    if "--skip-fetch" not in sys.argv:
        fetch_runtime()
    stage_native()
    stage_assets()
    print("\nready. `./gradlew assembleDebug` will now package the runtime.")


if __name__ == "__main__":
    main()
