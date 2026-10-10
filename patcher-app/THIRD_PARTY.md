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

### Patch engine (`:engine`)

| Component | Coordinates | Licence | Used for |
|---|---|---|---|
| smali / baksmali / dexlib2 / smali-util | `com.android.tools.smali:smali`, `smali-baksmali`, `smali-dexlib2`, `smali-util` 3.0.10 | BSD-3-Clause | dex disassembly and assembly |
| ARSCLib | `io.github.reandroid:ARSCLib` 1.4.0 | Apache-2.0 | binary AndroidManifest.xml and resources.arsc |
| apksig | `com.android.tools.build:apksig` 9.3.1 | Apache-2.0 | APK signing and signature verification |
| Kotlin standard library | `org.jetbrains.kotlin:kotlin-stdlib`, `org.jetbrains:annotations` | Apache-2.0 | language runtime |
| Guava (Android flavour) and its support artifacts | `com.google.guava:guava` 31.1-android, `failureaccess`, `listenablefuture`; `com.google.errorprone:error_prone_annotations`, `com.google.j2objc:j2objc-annotations` | Apache-2.0 | transitive of dexlib2 |
| Checker Framework annotations | `org.checkerframework:checker-qual` | MIT | transitive of Guava |
| JSR-305 | `com.google.code.findbugs:jsr305` | BSD-3-Clause | transitive of dexlib2 |
| JCommander | `org.jcommander:jcommander` | Apache-2.0 | transitive of smali |
| ANTLR 3 runtime, tool and ST4 / StringTemplate 3, ANTLR 2 | `org.antlr:antlr-runtime`, `antlr`, `ST4`, `stringtemplate`; `antlr:antlr` | BSD-3-Clause (ANTLR 2: BSD-style, public domain) | transitive of the smali assembler |
| Framework resource ids | `engine/src/main/resources/io/openshm/patcher/engine/framework-ids.txt` | Apache-2.0 (AOSP) | `android:` attribute and style ids; plain `name=id` data generated from the public Android SDK `android.jar` by `engine/tools/GenFrameworkIds.java` (no SDK code is copied) |

Licences were written down from the published metadata of the pinned versions; re-check the exact
text when a version changes. No jar is committed to this repository.

## Build and test only (never in the app)

| Component | Coordinates | Licence |
|---|---|---|
| Compose Driver | `io.github.jdemeulenaere:compose-driver` | to be checked when added |
