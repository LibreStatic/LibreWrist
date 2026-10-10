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
    api(libs.smali.dexlib2)
    implementation(libs.smali.smali)
    implementation(libs.smali.baksmali)
    implementation(libs.arsclib)
    implementation(libs.apksig)

    testImplementation(libs.junit4)
}

// Environment variables of the opt-in equivalence oracle (see OracleTest); forwarded to the test
// JVM through Gradle so that the daemon's own environment does not matter.
val oracleVariables = listOf("SHM_ORACLE_APKS", "SHM_ORACLE_WORK", "SHM_ORACLE_ONLY", "SHM_ORACLE_KEEP")

tasks.test {
    // Heap for the equivalence oracle, which handles whole APKs; the Gradle daemon stays small.
    maxHeapSize = "3g"
    systemProperty("shm.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    // The tests read the committed catalogue and its signature: re-run them when it changes.
    inputs.dir(rootProject.projectDir.parentFile.resolve("catalogue")).withPropertyName("catalogue")
    for (name in oracleVariables) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
    // An oracle run depends on files outside the build; never skip it as up to date.
    if (providers.environmentVariable("SHM_ORACLE_APKS").isPresent) outputs.upToDateWhen { false }
}
