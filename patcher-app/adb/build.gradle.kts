// ADB client: wireless-debugging pairing, mDNS discovery, shell, install and pull.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.openshm.patcher.adb"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit4)
}
