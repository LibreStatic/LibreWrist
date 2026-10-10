plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.openshm.patcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.openshm.patcher"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin {
    compilerOptions {
        // Material 3 Expressive is the design language of the app, so its experimental APIs are used throughout.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":adb"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Previews and their fake UiState live in src/debug only (never compiled into release builds).
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Development-only Compose Driver: present only when settings.gradle.kts included
// :tools:compose-driver (a task of that project, or -Popenshm.composeDriver). :app is an application
// module and cannot be a dependency of another module, so the harness's server test is compiled
// into :app's unit-test source set, where it sees the debug previews. Nothing here reaches the app.
rootProject.findProject(":tools:compose-driver")?.let { driver ->
    android.sourceSets.getByName("test").kotlin.directories.add(driver.projectDir.resolve("src/test/kotlin").path)
    dependencies {
        testImplementation(platform(libs.compose.bom))
        testImplementation(libs.compose.driver)
        testImplementation(libs.robolectric)
        testImplementation(libs.androidx.test.ext)
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
    val composable = providers.gradleProperty("composeDriver.composable")
    val port = providers.gradleProperty("composeDriver.port").orElse("8137")
    val qualifiers = providers.gradleProperty("composeDriver.qualifiers")
    val fontScale = providers.gradleProperty("composeDriver.fontScale")
    tasks.withType<Test>().configureEach {
        // The server test blocks until the process is stopped; never treat it as up to date.
        outputs.upToDateWhen { false }
        maxHeapSize = "2g"
        // Robolectric's SDK 36 runtime pokes FileDescriptor internals that newer JDKs no longer export.
        jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        testLogging { showStandardStreams = true }
        composable.orNull?.let { systemProperty("compose.driver.composable", it) }
        systemProperty("compose.driver.port", port.get())
        qualifiers.orNull?.let { systemProperty("compose.driver.qualifiers", it) }
        fontScale.orNull?.let { systemProperty("compose.driver.fontScale", it) }
    }
}

// Our own code (watch-mod, alarm-fork, shm-bridge apps) and the signed catalogue as placeholder-form
// assets; see own-code.gradle.kts.
extra["ownCode.sdkDirectory"] = androidComponents.sdkComponents.sdkDirectory.map { it.asFile.path }
apply(from = "own-code.gradle.kts")
@Suppress("UNCHECKED_CAST")
val ownCodeAssets = extra["ownCode.assetsTask"] as TaskProvider<Task>
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(ownCodeAssets, { task: Task ->
        // The task's `assets` property (DirectoryProperty), read reflectively: its class lives in the script.
        @Suppress("UNCHECKED_CAST")
        task.javaClass.getMethod("getAssets").invoke(task) as org.gradle.api.file.DirectoryProperty
    })
}
