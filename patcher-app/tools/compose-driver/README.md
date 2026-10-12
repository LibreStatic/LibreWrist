# Compose Driver harness

Development-only tooling that renders one Compose screen headlessly (Robolectric, no emulator, no
app install) and drives it over HTTP, so an agent can read the semantics tree, take a screenshot and
click, type, scroll or swipe. It uses the published
[Compose Driver](https://github.com/jdemeulenaere/compose-driver) library
(`io.github.jdemeulenaere:compose-driver`, Apache-2.0, version in `gradle/libs.versions.toml`).

Nothing ships with the app:

- `:tools:compose-driver` is only included in the build when one of its tasks is requested, or with
  `-Popenshm.composeDriver` (which the wrapper script passes, and which you can also set for IDE sync).
- `:app` is an application module, so it cannot be a dependency of the tools module. Instead
  `app/build.gradle.kts` adds `src/test/kotlin` of this directory to its own unit-test source set and
  puts Compose Driver, Robolectric and AndroidX Test on the **test** classpath, only while the tools
  module is included. The app's runtime classpath is never touched.
- The fake `UiState` and the zero-argument screens live in `app/src/debug/kotlin/.../preview`, which
  release builds do not compile.

## Commands

Everything goes through `compose-driver.sh` (bash). Run it from `patcher-app/`:

```bash
export JAVA_HOME=<a JDK 21 or newer>        # Robolectric's SDK 36 sandbox needs Java 21+
tools/compose-driver/compose-driver.sh list
tools/compose-driver/compose-driver.sh start WelcomeDriver     # returns once GET /status answers "ok"
tools/compose-driver/compose-driver.sh tree                    # semantics tree
tools/compose-driver/compose-driver.sh shot welcome            # build/compose-driver/screenshots/welcome.png
tools/compose-driver/compose-driver.sh click text="Get started"
tools/compose-driver/compose-driver.sh reset AcquireDriver     # switch screen without rebuilding
tools/compose-driver/compose-driver.sh stop
```

(`help` prints every command; the table and selectors are the same as in the upstream README.)

Selectors: `tag=<testTag>`, `text=<exact text>`, plus `substring` / `ignorecase`. Without a selector the
root is used. Prefer the semantic tree for iteration and take a screenshot only for a visual check.

### Viewport and options

- Default: Pixel 8-like phone, `w412dp-h915dp-420dpi` (1081 x 2401 px screenshots), SDK 36, light theme.
- `--dark` adds the `night` qualifier; `--font-scale 2.0` checks large-text wrapping;
  `--qualifiers w360dp-h640dp` layers other Robolectric qualifiers over the default.
- Each option needs a `start` (it restarts the server); `reset` keeps the current configuration.

### Environment

- `JAVA_HOME`: the test JVM is the Gradle JVM, and Robolectric SDK 36 refuses to run below Java 21.
- `COMPOSE_DRIVER_PORT` (default `8137`).
- `COMPOSE_DRIVER_GRADLE_ARGS`, for example `--max-workers=2` on a loaded host.
- `COMPOSE_DRIVER_START_TIMEOUT` seconds (default 900; a cold build takes about five minutes).
- Server log: `build/compose-driver/server.log`.

## Adding a screen

1. Make the screen a stateless `XxxScreen(state, callbacks)` composable with an immutable UiState.
2. Add fake state to `app/src/debug/kotlin/io/openshm/patcher/preview/PreviewData.kt`, built from the
   real catalogue where possible. Fake data never goes into `src/main`.
3. Add an `XxxSample()` in `ScreenSamples.kt`, a `@ScreenPreviews` preview in `ScreenPreviews.kt`
   (light/dark, font scale 1.0/2.0, dynamic/fallback color) and a public zero-argument
   `fun XxxDriver()` in `DriverPreviews.kt` (the wrapper lists `fun Name()` lines of `*DriverPreviews.kt`).
4. Use visible text and content descriptions in selectors; add `Modifier.testTag` only where a control
   cannot be selected reliably otherwise.

Fonts: Roboto Flex is requested through Google Play services, which the headless renderer does not
have, so renders use the system font. Line breaks can therefore differ slightly from a device.

## README walkthrough screenshots

The walkthrough images in `docs/images/walkthrough/` are captured on an emulator, not with the
driver, so they show real system bars. The debug-only `WalkthroughActivity` renders one `XxxDriver`
full screen with fake data:

```bash
adb shell am start -W -S -n io.openshm.patcher/.preview.WalkthroughActivity --es screen WelcomeDriver
adb exec-out screencap -p > welcome.png
```

A screen must be listed in its `when` before it can be captured. On a software-rendered emulator,
wait several seconds before capturing.
