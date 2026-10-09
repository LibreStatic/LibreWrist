// ADB client: wireless-debugging pairing, mDNS discovery, shell, install and pull.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.openshm.patcher.adb"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Protocol, TLS 1.3 and SPAKE2 pairing. Dual-licensed; we use the Apache-2.0 option.
    // Its POM declares its own dependencies (spake2-android, bcprov, annotation) as runtime only.
    implementation(libs.libadb.android)
    // TLS 1.3 key-material export for pairing (the platform Conscrypt does not expose it).
    implementation(libs.conscrypt.android)
    // Self-signed client certificate for the ADB key (X509V3CertificateGenerator).
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
