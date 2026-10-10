// Development-only Compose Driver host. This project has no code plugin on purpose: it exists so
// settings.gradle.kts can include it on demand, and :app/build.gradle.kts then compiles
// src/test/kotlin (the server test) into its unit-test source set together with the Compose Driver,
// Robolectric and AndroidX Test dependencies. Nothing here is part of the app or its runtime classpath.
// Use scripts/compose-driver.sh instead of calling Gradle by hand; see README.md.
plugins {
    base
}
