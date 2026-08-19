#!/usr/bin/env python3
"""Fetch Android/Bionic arm64 packages and unpack them into a staging tree.

Termux is the only maintained source of Android-native builds of Node, Git and
their dependencies that this project has found. Nothing from here is committed;
the staging tree is a build input, and a shipping product should compile these
itself rather than redistribute someone else's artifacts (ADR-0022).

Used by prepare-android-runtime.py. Runnable directly for inspection:
    python scripts/runtime/termux.py <staging-dir> nodejs git ...
"""

from __future__ import annotations

import io
import lzma
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main"
INDEX = f"{REPO}/dists/stable/main/binary-aarch64/Packages"

_index_cache: dict[str, dict[str, str]] | None = None


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=180) as response:
        return response.read()


def index() -> dict[str, dict[str, str]]:
    """The whole package index, parsed once per process."""
    global _index_cache
    if _index_cache is not None:
        return _index_cache

    text = fetch(INDEX).decode("utf-8", "replace")
    entries: dict[str, dict[str, str]] = {}
    for block in text.split("\n\n"):
        fields: dict[str, str] = {}
        for line in block.splitlines():
            if ": " in line and not line.startswith(" "):
                key, _, value = line.partition(": ")
                fields[key] = value
        if "Package" in fields:
            entries[fields["Package"]] = fields
    _index_cache = entries
    return entries


def unpack_deb(blob: bytes, into: Path) -> None:
    """Extracts a .deb's data member without needing `ar` installed."""
    stream = io.BytesIO(blob)
    if stream.read(8) != b"!<arch>\n":
        raise SystemExit("not an ar archive")

    while True:
        header = stream.read(60)
        if len(header) < 60:
            return
        # GNU ar pads names to 16 bytes and terminates them with a slash.
        name = header[0:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        payload = stream.read(size)
        if size % 2:
            stream.read(1)

        if not name.startswith("data.tar"):
            continue

        if name.endswith(".xz"):
            payload = lzma.decompress(payload)
        elif name.endswith(".zst"):
            try:
                import zstandard
            except ImportError:
                raise SystemExit("payload is zstd-compressed; pip install zstandard") from None
            payload = zstandard.ZstdDecompressor().decompress(payload, max_output_size=1 << 30)
        elif not name.endswith(".gz"):
            raise SystemExit(f"unrecognised payload member: {name}")

        mode = "r:gz" if name.endswith(".gz") else "r:"
        with tarfile.open(fileobj=io.BytesIO(payload), mode=mode) as archive:
            archive.extractall(into, filter="tar")
        return


def fetch_packages(names: list[str], staging: Path, quiet: bool = False) -> int:
    """Downloads and unpacks each named package. Returns the bytes downloaded."""
    staging.mkdir(parents=True, exist_ok=True)
    entries = index()

    missing = [name for name in names if name not in entries]
    if missing:
        raise SystemExit(f"not in the Termux index: {missing}")

    total = 0
    for name in names:
        fields = entries[name]
        blob = fetch(f"{REPO}/{fields['Filename']}")
        total += len(blob)
        unpack_deb(blob, staging)
        if not quiet:
            print(f"  {name:12} {fields['Version']:18} {len(blob) / 1e6:5.1f} MB")
    return total


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    downloaded = fetch_packages(sys.argv[2:], Path(sys.argv[1]))
    print(f"\ndownloaded {downloaded / 1e6:.1f} MB")
