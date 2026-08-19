#!/usr/bin/env python3
"""Report what an ELF binary asks the dynamic loader for, and what that weighs.

Answers the M6 question "what pieces must be bundled" from the binary itself
rather than from package metadata, and sizes the result the way an APK would see
it: symlinks resolved, each real file counted once.

Usage: python spike/m6/elf-needs.py <binary> [libdir]
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

DT_NULL, DT_NEEDED, DT_STRTAB, DT_SONAME, DT_RUNPATH, DT_STRSZ = 0, 1, 5, 14, 29, 10


def dynamic_entries(path: Path) -> tuple[list[str], str | None, str | None]:
    """Returns (DT_NEEDED names, SONAME, RUNPATH) for a 64-bit little-endian ELF."""
    data = path.read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit(f"{path}: not a 64-bit ELF")

    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

    dyn_off = dyn_size = None
    segments: list[tuple[int, int, int]] = []  # (vaddr, offset, filesz)
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, base)
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, base + 0x08)
        p_filesz, = struct.unpack_from("<Q", data, base + 0x20)
        if p_type == 2:  # PT_DYNAMIC
            dyn_off, dyn_size = p_offset, p_filesz
        elif p_type == 1:  # PT_LOAD
            segments.append((p_vaddr, p_offset, p_filesz))

    if dyn_off is None:
        return [], None, None

    def to_offset(vaddr: int) -> int | None:
        for seg_vaddr, seg_off, seg_size in segments:
            if seg_vaddr <= vaddr < seg_vaddr + seg_size:
                return seg_off + (vaddr - seg_vaddr)
        return None

    tags: list[tuple[int, int]] = []
    for pos in range(dyn_off, dyn_off + dyn_size, 16):
        tag, val = struct.unpack_from("<qQ", data, pos)
        if tag == DT_NULL:
            break
        tags.append((tag, val))

    strtab_vaddr = next((v for t, v in tags if t == DT_STRTAB), None)
    if strtab_vaddr is None:
        return [], None, None
    strtab = to_offset(strtab_vaddr)

    def string_at(index: int) -> str:
        start = strtab + index
        end = data.index(b"\0", start)
        return data[start:end].decode("utf-8", "replace")

    needed = [string_at(v) for t, v in tags if t == DT_NEEDED]
    soname = next((string_at(v) for t, v in tags if t == DT_SONAME), None)
    runpath = next((string_at(v) for t, v in tags if t == DT_RUNPATH), None)
    return needed, soname, runpath


def main() -> None:
    binary = Path(sys.argv[1])
    libdir = Path(sys.argv[2]) if len(sys.argv) > 2 else binary.parent.parent / "lib"

    # Bionic provides these; they are never bundled.
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so"}

    seen: dict[str, Path | None] = {}
    queue = [binary]
    roots: list[str] = []

    while queue:
        current = queue.pop()
        try:
            needed, _, runpath = dynamic_entries(current)
        except SystemExit:
            continue
        if current == binary:
            roots = list(needed)
            print(f"{binary.name}: {binary.stat().st_size / 1e6:.1f} MB")
            print(f"  RUNPATH: {runpath}")
            print(f"  needs:   {', '.join(needed)}")
        for name in needed:
            if name in seen or name in system:
                continue
            candidate = libdir / name
            resolved = candidate.resolve() if candidate.exists() else None
            seen[name] = resolved
            if resolved:
                queue.append(resolved)

    print("\ntransitive libraries to bundle:")
    unique: dict[Path, int] = {}
    missing: list[str] = []
    for name, resolved in sorted(seen.items()):
        if resolved is None:
            missing.append(name)
            print(f"  {name:28} MISSING (expected from the platform)")
            continue
        size = resolved.stat().st_size
        unique[resolved] = size
        print(f"  {name:28} {size / 1e6:6.1f} MB")

    total = binary.stat().st_size + sum(unique.values())
    print(f"\nunique files: {len(unique)}")
    print(f"bundle size (binary + libraries, uncompressed): {total / 1e6:.1f} MB")
    if missing:
        print(f"not found in {libdir} (assumed provided by Android): {', '.join(missing)}")


if __name__ == "__main__":
    main()
