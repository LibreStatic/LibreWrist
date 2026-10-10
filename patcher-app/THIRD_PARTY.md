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
| Jetpack Compose (BOM, UI, foundation, runtime) | `androidx.compose:compose-bom` and the libraries it pins | Apache-2.0 | `:app` |
| Material 3 (incl. the Expressive APIs: `MaterialExpressiveTheme`, `MaterialShapes`, wavy progress) | `androidx.compose.material3:material3` | Apache-2.0 | `:app` |
| Material Icons Extended | `androidx.compose.material:material-icons-extended` | Apache-2.0 | `:app` |
| Compose Google Fonts (downloadable fonts API; Roboto Flex is fetched at runtime through Google Play services, no font file is bundled) | `androidx.compose.ui:ui-text-google-fonts` | Apache-2.0 (Roboto Flex itself: SIL OFL 1.1) | `:app` |
| Navigation Compose | `androidx.navigation:navigation-compose` | Apache-2.0 | `:app` |
| Lifecycle (runtime and ViewModel for Compose) | `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | Apache-2.0 | `:app` |
| Activity Compose, Core KTX | `androidx.activity:activity-compose`, `androidx.core:core-ktx` | Apache-2.0 | `:app` |

Note: libadb-android states that its spake2 dependency is LGPL. It is linked dynamically as a
separate library, which keeps the app's own licence choice open; the exact licence text of each
version in use must be checked when the version is pinned. Verified for the pinned versions: the
libadb-android 3.1.1 sources carry `GPL-3.0-or-later OR Apache-2.0` headers (one file also carries
`BSD-3-Clause`), and the spake2-android 2.2.1 POM declares LGPL-3.0. The native libraries of
spake2-android and conscrypt-android 2.5.3 have 16 KiB ELF segment alignment (checked on arm64-v8a).

Considered and not used: `sun-security-android` (the certificate generator libadb-android's README
suggests) is GPL-2.0 per its POM, so the ADB certificate is generated with Bouncy Castle instead.

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
| Compose Driver (headless Compose renderer with an HTTP API; `tools/compose-driver`, test classpath only) | `io.github.jdemeulenaere:compose-driver` 0.5.0 | Apache-2.0 (from the artifact POM) |
| Robolectric (runs the Compose Driver server and any Android unit test) | `org.robolectric:robolectric` | MIT (from the artifact POM) |
| AndroidX Test JUnit ext and runner (on-device tests of `:adb`) | `androidx.test.ext:junit`, `androidx.test:runner` | Apache-2.0 |
| Compose UI Test manifest (debug builds with the driver enabled only) | `androidx.compose.ui:ui-test-manifest` | Apache-2.0 |
| Compose tooling and tooling-preview (`@Preview`, debug builds only) | `androidx.compose.ui:ui-tooling`, `ui-tooling-preview` | Apache-2.0 |
| JUnit 4, kotlinx-coroutines-test | `junit:junit`, `org.jetbrains.kotlinx:kotlinx-coroutines-test` | EPL-1.0, Apache-2.0 |
