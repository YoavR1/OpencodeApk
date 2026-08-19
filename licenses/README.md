# Vendored licence texts

Full licence texts for components the APK redistributes whose text is **not**
shipped inside the package we build from. `scripts/runtime/collect-licenses.py`
reads them from here and copies them into the APK.

They are vendored rather than fetched because a release must not depend on a
network round-trip to a third-party host, and because the exact text that
accompanied a given build is part of what makes the build reproducible.

| File | For | Provenance |
|---|---|---|
| `GPL-2.0.txt` | Git (`libgit.so`, `libgit-remote-http.so`) | Git's own `LICENSE.txt`, as distributed by Git for Windows. Includes Git's note that v2 is the only version that applies to it. |
| `LGPL-2.1.txt` | GNU libiconv (`libiconv.so`) | libiconv's own `COPYING.LIB`. Its `README` line 149 states the libraries are LGPL while the `iconv` *program* is GPL — we ship only the library. |

Termux's packages ship a `copyright` file for some components (Node.js, ICU,
curl, PCRE2, c-ares, libffi, zlib, expat); those are collected from the staging
trees directly and are not duplicated here.
