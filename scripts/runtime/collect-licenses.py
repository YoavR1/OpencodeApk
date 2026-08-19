#!/usr/bin/env python3
"""Assemble the open-source attribution the APK must carry.

Every native library in `jniLibs/` is third-party code we redistribute, and two
of them are copyleft. Distributing them without notice - and, for git, without a
source offer - is a licence violation, not a paperwork oversight.

This script exists because that obligation is easy to satisfy once and then break
silently: someone adds a library to the packaging script, ships it, and nothing
anywhere notices that it has no entry. So the mapping below is exhaustive by
construction - a shipped library with no entry is an ERROR that fails the build,
never a warning.

It writes:

    apps/android/app/src/main/assets/licenses/NOTICE.txt   the text the app shows
    apps/android/app/src/main/assets/licenses/<pkg>.txt    full texts we have

Run it after prepare-android-runtime.py. `--check` verifies without writing,
which is what CI uses.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
JNILIBS = ROOT / "apps" / "android" / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
ASSETS = ROOT / "apps" / "android" / "app" / "src" / "main" / "assets" / "licenses"

# Staging trees left by prepare-android-runtime.py. Termux ships a copyright file
# for some packages and not others, so these are a source of real licence text
# where they exist - never the authority for WHICH licence applies.
STAGING = [
    ROOT / "spike" / "m6" / "staging" / "android-node",
    ROOT / "spike" / "m8" / "staging" / "git",
]
DOC = "data/data/com.termux/files/usr/share/doc"

# Texts committed to this repository, for components whose package ships none.
# See licenses/README.md for where each came from and why it is vendored.
VENDORED = ROOT / "licenses"


class Component:
    """One redistributable, and everything needed to comply with its licence."""

    def __init__(self, name, licence, url, doc=None, copyleft=False, note=None, vendored=None):
        self.name = name
        self.licence = licence
        self.url = url
        # Directory name under Termux's share/doc, when it ships one.
        self.doc = doc
        # True when the licence obliges us to offer source for THIS binary.
        self.copyleft = copyleft
        # Apache-2.0 §4(a) obliges us to ship the licence text with the binary,
        # without any source obligation. Tracked so a missing text is reported
        # rather than passing silently because it is not copyleft.
        self.text_required = copyleft or licence.startswith("Apache-2.0")
        self.note = note
        # Filename under licenses/, when the package ships no text of its own.
        self.vendored = vendored


# Every library the APK ships, mapped to what it actually is.
#
# Licences are recorded from each project's own upstream terms. Where Termux
# also ships a copyright file it is collected verbatim and preferred, because a
# distributor's copy is better evidence than a name in a table.
LIBRARIES = {
    "libnode.so": Component("Node.js", "MIT", "https://github.com/nodejs/node", doc="nodejs"),
    "libicuuc78.so": Component("ICU", "Unicode-3.0", "https://github.com/unicode-org/icu", doc="libicu"),
    "libicui18n78.so": Component("ICU", "Unicode-3.0", "https://github.com/unicode-org/icu", doc="libicu"),
    "libicudata78.so": Component("ICU", "Unicode-3.0", "https://github.com/unicode-org/icu", doc="libicu"),
    "libcares.so": Component("c-ares", "MIT", "https://github.com/c-ares/c-ares", doc="c-ares"),
    "libffi.so": Component("libffi", "MIT", "https://github.com/libffi/libffi", doc="libffi"),
    "libz1.so": Component("zlib", "Zlib", "https://github.com/madler/zlib", doc="zlib"),
    "libsqlite3.so": Component("SQLite", "blessing (public domain)", "https://sqlite.org"),
    "libc++_shared.so": Component(
        "LLVM libc++", "Apache-2.0 WITH LLVM-exception", "https://github.com/llvm/llvm-project"
    ),
    "libcrypto3.so": Component(
        "OpenSSL", "Apache-2.0", "https://github.com/openssl/openssl", vendored="Apache-2.0-OpenSSL.txt"
    ),
    "libssl3.so": Component(
        "OpenSSL", "Apache-2.0", "https://github.com/openssl/openssl", vendored="Apache-2.0-OpenSSL.txt"
    ),
    "libcurl.so": Component("curl", "curl (MIT-like)", "https://github.com/curl/curl", doc="libcurl"),
    "libpcre2-8.so": Component("PCRE2", "BSD-3-Clause", "https://github.com/PCRE2Project/pcre2", doc="pcre2"),
    "libgit.so": Component(
        "Git",
        "GPL-2.0-only",
        "https://github.com/git/git",
        copyleft=True,
        vendored="GPL-2.0.txt",
        note="Runs as a separate process; the app does not link it. See docs/LICENSES.md.",
    ),
    "libgit-remote-http.so": Component(
        "Git", "GPL-2.0-only", "https://github.com/git/git", copyleft=True,
        vendored="GPL-2.0.txt",
        note="Runs as a separate process; the app does not link it. See docs/LICENSES.md.",
    ),
    "libiconv.so": Component(
        "GNU libiconv",
        "LGPL-2.1-or-later",
        "https://savannah.gnu.org/projects/libiconv/",
        copyleft=True,
        vendored="LGPL-2.1.txt",
        note="Dynamically linked, so it can be replaced by the user - which is what LGPL section 6 requires.",
    ),
}

# Shipped in the assets bundle rather than as a library.
BUNDLED = [
    Component("OpenCode", "MIT", "https://github.com/anomalyco/opencode",
              note="The server and UI this app exists to run."),
    Component("expat", "MIT", "https://github.com/libexpat/libexpat", doc="libexpat",
              note="Statically linked into git; no separate library ships."),
]


def find_doc(name: str) -> tuple[pathlib.Path, str] | None:
    """The licence text Termux shipped for a package, if it shipped one."""
    for root in STAGING:
        base = root / DOC / name
        if not base.is_dir():
            continue
        for candidate in ("copyright", "LICENSE", "COPYING", "LICENSE.txt"):
            path = base / candidate
            if path.is_file():
                return path, path.read_text(encoding="utf-8", errors="replace")
    return None


def shipped() -> list[str]:
    if not JNILIBS.is_dir():
        return []
    return sorted(p.name for p in JNILIBS.iterdir() if p.suffix == ".so" or ".so." in p.name)


def build() -> tuple[str, dict[str, str], list[str], list[str]]:
    """Returns the NOTICE, the texts to write, hard problems, and warnings."""
    problems: list[str] = []
    warnings: list[str] = []
    present = shipped()

    unmapped = [lib for lib in present if lib not in LIBRARIES]
    for lib in unmapped:
        problems.append(
            f"{lib} is packaged but has no licence entry. Add it to LIBRARIES in "
            f"{pathlib.Path(__file__).name} - shipping it without attribution is a licence violation."
        )

    # Only report on what is actually being shipped, so a partial build does not
    # produce a NOTICE claiming components the APK does not contain.
    used = [(lib, LIBRARIES[lib]) for lib in present if lib in LIBRARIES]

    seen: dict[str, Component] = {}
    for _, component in used:
        seen.setdefault(component.name, component)
    for component in BUNDLED:
        seen.setdefault(component.name, component)

    texts: dict[str, str] = {}
    lines = [
        "OPEN SOURCE NOTICES",
        "",
        "OpenCode for Android bundles the components below and redistributes them",
        "under their own licences. This notice is generated from what the APK",
        "actually contains; see docs/LICENSES.md for the obligations analysis.",
        "",
    ]

    for name in sorted(seen):
        component = seen[name]
        lines.append(f"{name}")
        lines.append(f"  Licence : {component.licence}")
        lines.append(f"  Source  : {component.url}")
        if component.note:
            lines.append(f"  Note    : {component.note}")

        # The package's own copy is preferred: a distributor's text is better
        # evidence than one this repository chose. A vendored copy is the
        # fallback for packages that ship none.
        slug, text = None, None
        if component.doc:
            found = find_doc(component.doc)
            if found:
                slug, text = component.doc, found[1]
        if text is None and component.vendored:
            path = VENDORED / component.vendored
            if path.is_file():
                slug = pathlib.Path(component.vendored).stem
                text = path.read_text(encoding="utf-8", errors="replace")

        if text is not None:
            texts[slug] = text
            lines.append(f"  Full text: licenses/{slug}.txt")
        else:
            lines.append("  Full text: NOT BUNDLED - see Source")
            if component.copyleft:
                problems.append(
                    f"{name} is copyleft ({component.licence}) and its full licence text is "
                    "not bundled. The text must accompany the binary."
                )
            elif component.text_required:
                # Not a build blocker - no source obligation attaches - but it
                # does block public distribution, so it is surfaced every run
                # rather than discovered by a store review.
                warnings.append(
                    f"{name} is {component.licence}, which requires the licence text to accompany "
                    "the binary (Apache-2.0 section 4a). Not bundled; add it before publishing."
                )
        lines.append("")

    copyleft = sorted({c.name for c in seen.values() if c.copyleft})
    if copyleft:
        lines += [
            "WRITTEN OFFER",
            "",
            "The components listed above under GPL or LGPL terms - "
            + ", ".join(copyleft)
            + " -",
            "are redistributed unmodified. Complete corresponding source for the exact",
            "versions in this APK is available from the Source URLs above, and on request",
            "from the project's issue tracker for as long as this build is distributed.",
            "",
            "These run as separate processes or are dynamically linked; the application",
            "itself is MIT-licensed and is not a derivative work of them.",
            "",
        ]

    return "\n".join(lines), texts, problems, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify without writing")
    args = parser.parse_args()

    present = shipped()
    if not present:
        # A build without the runtime is legitimate (ADR-0023); it ships no
        # third-party binary, so it needs no notice for one.
        print("no native libraries packaged - nothing to attribute")
        return 0

    notice, texts, problems, warnings = build()

    for problem in problems:
        print(f"ERROR: {problem}", file=sys.stderr)
    for warning in warnings:
        print(f"WARN:  {warning}", file=sys.stderr)

    if args.check:
        if problems:
            return 1
        current = (ASSETS / "NOTICE.txt")
        if not current.is_file() or current.read_text(encoding="utf-8") != notice:
            print("ERROR: NOTICE.txt is missing or stale; run scripts/runtime/collect-licenses.py",
                  file=sys.stderr)
            return 1
        print(f"attribution present and current for {len(present)} libraries")
        return 0

    if problems:
        return 1

    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "NOTICE.txt").write_text(notice, encoding="utf-8", newline="\n")
    for slug, text in texts.items():
        (ASSETS / f"{slug}.txt").write_text(text, encoding="utf-8", newline="\n")

    print(f"wrote NOTICE.txt and {len(texts)} licence text(s) for {len(present)} libraries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
