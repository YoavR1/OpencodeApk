#!/usr/bin/env python3
"""Fetch an Android/Bionic arm64 Node runtime and unpack it into a staging tree.

M6 needs to answer whether a JavaScript runtime capable of hosting the OpenCode
server can run on a stock, unrooted Android phone. Node is built for Android
aarch64 by the Termux project, which is the only maintained source of such builds
this spike found. Nothing here ships in the app: this is a measuring instrument
for the spike, and the packaging question (jniLibs, W^X) is answered separately by
RuntimeFeasibilityTest.

Downloads come from Termux's official repository over HTTPS. Everything lands in
one staging directory that can be deleted.

Usage:  python spike/m6/fetch-node-android.py [staging-dir]
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

# node plus everything its ELF headers will ask the loader for.
PACKAGES = ["nodejs", "libc++", "openssl", "c-ares", "libicu", "libsqlite", "zlib", "libffi"]


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def index_entries() -> dict[str, dict[str, str]]:
    """Parses the Packages index into {name: {field: value}}."""
    text = fetch(INDEX).decode("utf-8", "replace")
    entries: dict[str, dict[str, str]] = {}
    for block in text.split("\n\n"):
        fields: dict[str, str] = {}
        for line in block.splitlines():
            if ": " in line and not line.startswith(" "):
                key, _, value = line.partition(": ")
                fields[key] = value
        name = fields.get("Package")
        if name in PACKAGES:
            entries[name] = fields
    return entries


def unpack_deb(blob: bytes, into: Path) -> None:
    """Extracts a .deb's data member.

    A .deb is an `ar` archive; the payload is data.tar.<compression>. The format
    is simple enough to read directly, which avoids depending on `ar` being
    installed (it is not, on Windows).
    """
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
            mode = "r:"
        elif name.endswith(".gz"):
            mode = "r:gz"
        elif name.endswith(".zst"):
            try:
                import zstandard
            except ImportError:
                raise SystemExit("payload is zstd-compressed; pip install zstandard") from None
            payload = zstandard.ZstdDecompressor().decompress(payload, max_output_size=1 << 30)
            mode = "r:"
        else:
            raise SystemExit(f"unrecognised payload member: {name}")

        with tarfile.open(fileobj=io.BytesIO(payload), mode=mode) as archive:
            archive.extractall(into, filter="tar")
        return


def main() -> None:
    staging = Path(sys.argv[1] if len(sys.argv) > 1 else "spike/m6/staging/android-node").resolve()
    staging.mkdir(parents=True, exist_ok=True)

    entries = index_entries()
    missing = [name for name in PACKAGES if name not in entries]
    if missing:
        raise SystemExit(f"not in the index: {missing}")

    total = 0
    for name in PACKAGES:
        fields = entries[name]
        url = f"{REPO}/{fields['Filename']}"
        blob = fetch(url)
        total += len(blob)
        unpack_deb(blob, staging)
        print(f"  {name:10} {fields['Version']:18} {len(blob) / 1e6:5.1f} MB")

    print(f"\ndownloaded {total / 1e6:.1f} MB into {staging}")

    prefix = staging / "data" / "data" / "com.termux" / "files" / "usr"
    node = prefix / "bin" / "node"
    if node.exists():
        print(f"node binary: {node} ({node.stat().st_size / 1e6:.1f} MB)")
    else:
        print("WARNING: no bin/node found; the layout may have changed")


if __name__ == "__main__":
    main()
