# Third-party components of the patcher app

Every library the app or its engine ships with must be listed here with its licence before it is
added to a build file. Build-only and test-only tools are listed separately.

## Shipped in the app

| Component | Coordinates | Licence | Used by |
|---|---|---|---|
| libadb-android | `com.github.MuntashirAkon:libadb-android` | GPL-3.0-or-later **or** Apache-2.0 (we use Apache-2.0) | `:adb` |
| spake2-java (Android binding) | `com.github.MuntashirAkon.spake2-java:spake2-android` | LGPL (dynamically linked native library; see note) | `:adb`, via libadb-android |
| Bouncy Castle | `org.bouncycastle:bcprov-jdk15to18` | MIT | `:adb`, via libadb-android |
| Jetpack Compose (BOM, UI, foundation, runtime) | `androidx.compose:compose-bom` and the libraries it pins | Apache-2.0 | `:app` |
| Material 3 (incl. the Expressive APIs: `MaterialExpressiveTheme`, `MaterialShapes`, wavy progress) | `androidx.compose.material3:material3` | Apache-2.0 | `:app` |
| Material Icons Extended | `androidx.compose.material:material-icons-extended` | Apache-2.0 | `:app` |
| Compose Google Fonts (downloadable fonts API; Roboto Flex is fetched at runtime through Google Play services, no font file is bundled) | `androidx.compose.ui:ui-text-google-fonts` | Apache-2.0 (Roboto Flex itself: SIL OFL 1.1) | `:app` |
| Navigation Compose | `androidx.navigation:navigation-compose` | Apache-2.0 | `:app` |
| Lifecycle (runtime and ViewModel for Compose) | `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | Apache-2.0 | `:app` |
| Activity Compose, Core KTX | `androidx.activity:activity-compose`, `androidx.core:core-ktx` | Apache-2.0 | `:app` |

Note: libadb-android states that its spake2 dependency is LGPL. It is linked dynamically as a
separate library, which keeps the app's own licence choice open; the exact licence text of each
version in use must be checked when the version is pinned.

Engine libraries (smali/baksmali/dexlib2, ARSCLib, apksig) are added by Phase 1 together with
their rows here.

## Build and test only (never in the app)

| Component | Coordinates | Licence |
|---|---|---|
| Compose Driver (headless Compose renderer with an HTTP API; `tools/compose-driver`, test classpath only) | `io.github.jdemeulenaere:compose-driver` 0.5.0 | Apache-2.0 (from the artifact POM) |
| Robolectric (runs the Compose Driver server and any Android unit test) | `org.robolectric:robolectric` | MIT (from the artifact POM) |
| AndroidX Test JUnit ext | `androidx.test.ext:junit` | Apache-2.0 |
| Compose UI Test manifest (debug builds with the driver enabled only) | `androidx.compose.ui:ui-test-manifest` | Apache-2.0 |
| Compose tooling and tooling-preview (`@Preview`, debug builds only) | `androidx.compose.ui:ui-tooling`, `ui-tooling-preview` | Apache-2.0 |
| JUnit 4, kotlinx-coroutines-test | `junit:junit`, `org.jetbrains.kotlinx:kotlinx-coroutines-test` | EPL-1.0, Apache-2.0 |
