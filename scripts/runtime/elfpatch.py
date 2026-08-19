#!/usr/bin/env python3
"""Rename shared libraries so Android will ship them, and fix the references.

Android extracts only files matching `lib*.so` from an APK's `lib/<abi>/`
directory. The Node runtime depends on libraries named `libicuuc.so.78`,
`libcrypto.so.3` and so on, which do not match and would simply be missing at
runtime. They have to be renamed - and once renamed, every `DT_NEEDED` and
`DT_SONAME` entry that referred to the old name has to be rewritten too, or the
loader asks for a file that is no longer there.

Every rename this needs is *shorter* than the original (`libicuuc.so.78` →
`libicuuc78.so`), so the strings can be patched in place inside `.dynstr` without
rebuilding the section. That is only safe when nothing else points into the bytes
being overwritten, which this checks and refuses to guess about.

Used by prepare-android-runtime.py; standalone for inspection:
    python scripts/runtime/elfpatch.py <file> [--apply new=old ...]
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

DT_NULL, DT_NEEDED, DT_SONAME, DT_STRTAB, DT_STRSZ = 0, 1, 14, 5, 10


class Elf:
    """Just enough ELF64 little-endian to read and patch the dynamic section."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.data = bytearray(path.read_bytes())
        if self.data[:4] != b"\x7fELF" or self.data[4] != 2:
            raise ValueError(f"{path}: not a 64-bit ELF")
        self._load_program_headers()
        self._load_dynamic()

    def _load_program_headers(self) -> None:
        e_phoff, = struct.unpack_from("<Q", self.data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from("<HH", self.data, 0x36)
        self.segments: list[tuple[int, int, int]] = []
        self.dyn_off = self.dyn_size = None
        for i in range(e_phnum):
            base = e_phoff + i * e_phentsize
            p_type, = struct.unpack_from("<I", self.data, base)
            p_offset, p_vaddr = struct.unpack_from("<QQ", self.data, base + 0x08)
            p_filesz, = struct.unpack_from("<Q", self.data, base + 0x20)
            if p_type == 1:  # PT_LOAD
                self.segments.append((p_vaddr, p_offset, p_filesz))
            elif p_type == 2:  # PT_DYNAMIC
                self.dyn_off, self.dyn_size = p_offset, p_filesz

    def _vaddr_to_offset(self, vaddr: int) -> int | None:
        for seg_vaddr, seg_off, seg_size in self.segments:
            if seg_vaddr <= vaddr < seg_vaddr + seg_size:
                return seg_off + (vaddr - seg_vaddr)
        return None

    def _load_dynamic(self) -> None:
        self.tags: list[tuple[int, int, int]] = []  # (tag, value, offset-of-entry)
        self.strtab = self.strsz = None
        if self.dyn_off is None:
            return
        for pos in range(self.dyn_off, self.dyn_off + self.dyn_size, 16):
            tag, value = struct.unpack_from("<qQ", self.data, pos)
            if tag == DT_NULL:
                break
            self.tags.append((tag, value, pos))
            if tag == DT_STRTAB:
                self.strtab = self._vaddr_to_offset(value)
            elif tag == DT_STRSZ:
                self.strsz = value

    def string_at(self, index: int) -> str:
        start = self.strtab + index
        end = self.data.index(b"\0", start)
        return self.data[start:end].decode("utf-8", "replace")

    @property
    def needed(self) -> list[tuple[int, str]]:
        return [(v, self.string_at(v)) for t, v, _ in self.tags if t == DT_NEEDED]

    @property
    def soname(self) -> tuple[int, str] | None:
        for t, v, _ in self.tags:
            if t == DT_SONAME:
                return v, self.string_at(v)
        return None

    def _string_indexes(self) -> set[int]:
        """Every .dynstr offset the dynamic section points at."""
        return {v for t, v, _ in self.tags if t in (DT_NEEDED, DT_SONAME)}

    def rename_string(self, index: int, new: str) -> None:
        """Overwrites a .dynstr entry in place, refusing anything unsafe."""
        old = self.string_at(index)
        if len(new) > len(old):
            raise ValueError(f"{self.path.name}: '{new}' is longer than '{old}'; in-place patching cannot grow a string")

        # Another entry may point into the middle of this string - .dynstr
        # commonly shares suffixes. Overwriting would corrupt it.
        start, end = index, index + len(old)
        for other in self._string_indexes():
            if other != index and start < other <= end:
                raise ValueError(
                    f"{self.path.name}: '{old}' at {index} overlaps another string at {other} "
                    f"('{self.string_at(other)}'); refusing to patch in place"
                )

        base = self.strtab + index
        self.data[base : base + len(old) + 1] = new.encode() + b"\0" * (len(old) - len(new) + 1)

    def save(self, path: Path | None = None) -> None:
        (path or self.path).write_bytes(bytes(self.data))


def android_name(name: str) -> str:
    """`libicuuc.so.78` → `libicuuc78.so`; names already ending in .so are unchanged.

    Android extracts only `*.so`, so a versioned suffix has to move before the
    extension. The version is kept rather than dropped, because two majors of the
    same library can legitimately be present.
    """
    if name.endswith(".so"):
        return name
    if ".so." not in name:
        return name
    stem, _, version = name.partition(".so.")
    return f"{stem}{version.replace('.', '')}.so"


def patch_file(path: Path, renames: dict[str, str]) -> list[str]:
    """Applies DT_NEEDED/DT_SONAME renames to one file. Returns what changed."""
    elf = Elf(path)
    changes: list[str] = []

    for index, name in elf.needed:
        new = renames.get(name)
        if new and new != name:
            elf.rename_string(index, new)
            changes.append(f"NEEDED {name} -> {new}")

    current = elf.soname
    if current:
        index, name = current
        new = renames.get(name)
        if new and new != name:
            elf.rename_string(index, new)
            changes.append(f"SONAME {name} -> {new}")

    if changes:
        elf.save()
    return changes


def main() -> None:
    target = Path(sys.argv[1])
    elf = Elf(target)
    print(f"{target.name}")
    soname = elf.soname
    print(f"  SONAME: {soname[1] if soname else '(none)'}")
    for _, name in elf.needed:
        marker = "" if name == android_name(name) else f"  -> {android_name(name)}"
        print(f"  NEEDED: {name}{marker}")


if __name__ == "__main__":
    main()
