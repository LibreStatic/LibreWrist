# Third-party components of the patcher app

Every library the app or its engine ships with must be listed here with its licence before it is
added to a build file. Build-only and test-only tools are listed separately.

## Shipped in the app

| Component | Coordinates | Licence | Used by |
|---|---|---|---|
| libadb-android | `com.github.MuntashirAkon:libadb-android:3.1.1` (JitPack) | GPL-3.0-or-later **or** Apache-2.0 (we use Apache-2.0; per-file SPDX header `GPL-3.0-or-later OR Apache-2.0`; the POM only says "Other") | `:adb` |
| spake2-java (Android binding) | `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` (JitPack, pulled by libadb-android) | LGPL-3.0 (POM; dynamically linked native library, see note) | `:adb`, via libadb-android |
| Bouncy Castle | `org.bouncycastle:bcprov-jdk15to18:1.81` (Maven Central; also pulled by libadb-android at the same version) | MIT (Bouncy Castle Licence) | `:adb` (self-signed ADB certificate) and libadb-android |
| Conscrypt | `org.conscrypt:conscrypt-android:2.5.3` (Maven Central) | Apache-2.0 | `:adb`: TLS 1.3 and key-material export for pairing, loaded by libadb-android reflectively |
| AndroidX Annotation | `androidx.annotation:annotation:1.9.1` (pulled by libadb-android) | Apache-2.0 | `:adb`, via libadb-android |

Note: libadb-android states that its spake2 dependency is LGPL. It is linked dynamically as a
separate library, which keeps the app's own licence choice open; the exact licence text of each
version in use must be checked when the version is pinned. Verified for the pinned versions: the
libadb-android 3.1.1 sources carry `GPL-3.0-or-later OR Apache-2.0` headers (one file also carries
`BSD-3-Clause`), and the spake2-android 2.2.1 POM declares LGPL-3.0. The native libraries of
spake2-android and conscrypt-android 2.5.3 have 16 KiB ELF segment alignment (checked on arm64-v8a).

Considered and not used: `sun-security-android` (the certificate generator libadb-android's README
suggests) is GPL-2.0 per its POM, so the ADB certificate is generated with Bouncy Castle instead.

Engine libraries (smali/baksmali/dexlib2, ARSCLib, apksig) are added by Phase 1 together with
their rows here.

## Build and test only (never in the app)

| Component | Coordinates | Licence |
|---|---|---|
| Compose Driver | `io.github.jdemeulenaere:compose-driver` | to be checked when added |
