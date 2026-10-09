// Pure JVM patch engine: interprets catalogue/catalogue.json on APK binaries. It runs both on the
// host (tests, equivalence oracle) and inside the Android app, so it must stay Android-compatible:
// no AWT, no javax.xml transformers, nothing above the Java 17 API level that API 31 lacks.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit4)
}
