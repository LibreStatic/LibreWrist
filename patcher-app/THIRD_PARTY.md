# Third-party components of the patcher app

Every library the app or its engine ships with must be listed here with its licence before it is
added to a build file. Build-only and test-only tools are listed separately.

## Shipped in the app

| Component | Coordinates | Licence | Used by |
|---|---|---|---|
| libadb-android | `com.github.MuntashirAkon:libadb-android` | GPL-3.0-or-later **or** Apache-2.0 (we use Apache-2.0) | `:adb` |
| spake2-java (Android binding) | `com.github.MuntashirAkon.spake2-java:spake2-android` | LGPL (dynamically linked native library; see note) | `:adb`, via libadb-android |
| Bouncy Castle | `org.bouncycastle:bcprov-jdk15to18` | MIT | `:adb`, via libadb-android |

Note: libadb-android states that its spake2 dependency is LGPL. It is linked dynamically as a
separate library, which keeps the app's own licence choice open; the exact licence text of each
version in use must be checked when the version is pinned.

Engine libraries (smali/baksmali/dexlib2, ARSCLib, apksig) are added by Phase 1 together with
their rows here.

## Build and test only (never in the app)

| Component | Coordinates | Licence |
|---|---|---|
| Compose Driver | `io.github.jdemeulenaere:compose-driver` | to be checked when added |
