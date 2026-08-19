# Licences and attribution

The APK redistributes sixteen native libraries and a JavaScript bundle that this
project did not write. Two of them are copyleft. This is the obligations
analysis; the notice the app actually ships is generated, not written by hand.

---

## 1. How this is kept honest

`scripts/runtime/collect-licenses.py` walks `jniLibs/arm64-v8a`, maps every
library to a component, and writes:

```
apps/android/app/src/main/assets/licenses/NOTICE.txt   generated from what ships
apps/android/app/src/main/assets/licenses/<pkg>.txt    full licence texts
```

**A shipped library with no entry is an error, not a warning.** It fails
`assembleRelease` and `bundleRelease`. That matters because the realistic failure
mode is not "we forgot to write a notice" — it is "someone added a library to the
packaging script two years later and nothing noticed". Verified by adding a
`libmystery.so` and watching the release build stop:

```
ERROR: libmystery.so is packaged but has no licence entry.
```

The notice is generated from what the APK **actually contains**, so a build
without the runtime does not claim components it does not ship.

## 2. What ships

| Library | Component | Licence | Copyleft |
|---|---|---|---|
| `libnode.so` | Node.js v26.4.0 | MIT | |
| `libicuuc78.so`, `libicui18n78.so`, `libicudata78.so` | ICU | Unicode-3.0 | |
| `libcares.so` | c-ares | MIT | |
| `libffi.so` | libffi | MIT | |
| `libz1.so` | zlib | Zlib | |
| `libsqlite3.so` | SQLite | blessing (public domain) | |
| `libc++_shared.so` | LLVM libc++ | Apache-2.0 WITH LLVM-exception | |
| `libcrypto3.so`, `libssl3.so` | OpenSSL | Apache-2.0 | |
| `libcurl.so` | curl | curl (MIT-like) | |
| `libpcre2-8.so` | PCRE2 | BSD-3-Clause | |
| `libgit.so`, `libgit-remote-http.so` | Git 2.55.0 | **GPL-2.0-only** | ✓ |
| `libiconv.so` | GNU libiconv | **LGPL-2.1-or-later** | ✓ |

Plus, in the assets bundle: **OpenCode** (MIT) — the server and UI this app
exists to run — and **expat** (MIT), statically linked into git.

Versions were read from the binaries as shipped, on the device:
`libnode.so --version` → `v26.4.0`, `libgit.so --version` → `git version 2.55.0`.

## 3. Git is GPLv2 — what that obliges

This is the one that could stop a release, so it is worth stating precisely.

**The application is not a derivative work of git.** Git runs as a **separate
process**, launched with `ProcessBuilder` and communicated with over stdio and
the filesystem. Nothing links it. Under the GPL this is aggregation: the app
remains MIT-licensed, and shipping it alongside git does not relicense it.

**Redistributing the git binaries still carries obligations**, and aggregation
does not remove them:

1. The GPLv2 text must accompany the binaries. It does — `licenses/GPL-2.0.txt`
   is packaged into the APK and named in `NOTICE.txt`.
2. Complete corresponding source for the exact version must be available. Git
   2.55.0 is unmodified upstream; the notice carries the source URL and a written
   offer.
3. The binaries must not be relicensed or have further restrictions imposed.
   They are not.

Git is redistributed **unmodified**. The only change this project makes is the
filename — Android extracts only files matching `lib*.so` from an APK, so
`git` ships as `libgit.so` (ADR-0023). Renaming a file is not modifying the work.

## 4. libiconv is LGPL — what that obliges

`libiconv.so` is **dynamically linked** into git and Node. LGPL-2.1 §6 requires
that the user be able to relink the work against a modified version of the
library, and dynamic linking is the case the licence explicitly permits for
exactly that reason.

The library's own `README` records the split its distribution uses: the
`libiconv` and `libcharset` *libraries* are LGPL, while the `iconv` *program* and
its documentation are GPL. **We ship only the library**, so LGPL-2.1 is what
applies. `licenses/LGPL-2.1.txt` is its own `COPYING.LIB`, packaged with it.

## 5. Where the licence texts come from

Termux's packages carry a `copyright` file for some components and not others.
Where one exists it is collected verbatim from the staging tree and preferred — a
distributor's own copy is better evidence than a name in a table. Seven arrive
that way: Node.js, ICU, c-ares, libffi, zlib, curl, PCRE2, expat.

The rest are vendored in `licenses/`, with provenance recorded in
`licenses/README.md`. They are committed rather than fetched because a release
must not depend on a network round-trip to a third-party host, and because the
exact text that accompanied a build is part of what makes that build
reproducible.

## 6. Apache-2.0 also requires the text

Apache-2.0 §4(a) obliges us to give recipients a copy of the licence, with no
source obligation attached. The collector tracks that separately from copyleft so
that a missing Apache text is reported rather than passing silently just because
it is permissive.

Both Apache-2.0 components now ship their text:

- **OpenSSL** — its own `LICENSE`, as the library distributes it.
- **LLVM libc++** — the LLVM section of the Android NDK's `NOTICE.toolchain`
  (NDK 27.1.12297006), which is the toolchain that builds this library. It
  carries the Apache-2.0 appendix and the LLVM exception clause in full.

The collector now runs clean: `wrote NOTICE.txt and 12 licence text(s) for 16
libraries`, with no errors and no warnings.

Both were taken from a distributor's own copy on this machine rather than fetched
over the network. Licence texts are legal documents; reproducing one from memory
is not acceptable, and depending on a third-party host at release time is not
either.

## 7. Upstream OpenCode

`anomalyco/opencode` is MIT, consumed as a dependency rather than forked
(`.claude/rules/architecture.md` A1). Divergence is tracked in
`docs/UPSTREAM_SYNC.md`. MIT requires the copyright notice and permission notice
to accompany the software; `NOTICE.txt` carries the attribution and the source
URL.
