import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Lists the translated locales for the per-app language setting (Android 13+).
    androidResources {
        generateLocaleConfig = true
    }
    namespace = "io.openshm.patcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.openshm.patcher"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Base URL of the raw `catalogue/` directory of the public repository (config.env CATALOGUE_URL,
        // or -PcatalogueUrl). Empty disables catalogue updates. Only https is accepted.
        val catalogueUrl = (providers.gradleProperty("catalogueUrl").orNull ?: System.getenv("CATALOGUE_URL") ?: "").trim()
        require(catalogueUrl.isEmpty() || (catalogueUrl.startsWith("https://") && catalogueUrl.none { it == '"' || it == '\\' || it.isWhitespace() })) {
            "CATALOGUE_URL must be an https:// URL without quotes, backslashes or spaces"
        }
        buildConfigField("String", "CATALOGUE_URL", "\"$catalogueUrl\"")
    }
    // The patcher's own fixed key, shared by every checkout (OPENSHM_KEYS_DIR, default
    // ~/.local/share/openshm/keys, see config.env). Without it builds fall back to the SDK debug key.
    val keysDir = System.getenv("OPENSHM_KEYS_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share/openshm/keys")
    val patcherStore = File(keysDir, "patcher.p12")
    val patcherPass = File(keysDir, "patcher.pass")
    val patcherSigning = if (patcherStore.isFile && patcherPass.isFile) {
        signingConfigs.create("openshm") {
            val password = patcherPass.readText().trim()
            storeFile = patcherStore
            storeType = "PKCS12"
            storePassword = password
            keyAlias = "patcher"
            keyPassword = password
        }
    } else {
        null
    }
    buildTypes {
        getByName("debug") { patcherSigning?.let { signingConfig = it } }
        getByName("release") { patcherSigning?.let { signingConfig = it } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    lint {
        // minSdk 31: a call above it must fail the build, not just appear in a report.
        error += "NewApi"
    }
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
    implementation(libs.bouncycastle.bcprov)
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
// Applied through the `apply {}` block on purpose: lint's Gradle detector follows
// `apply(from = "...")` into the referenced script and crashes on its top-level declarations
// ("findFirCompiledSymbol only works on compiled declarations", lint 32.3), which aborts the whole
// lint run, so no check (NewApi included) is enforced.
apply { from("own-code.gradle.kts") }
@Suppress("UNCHECKED_CAST")
val ownCodeAssets = extra["ownCode.assetsTask"] as TaskProvider<Task>
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(ownCodeAssets, { task: Task ->
        // The task's `assets` property (DirectoryProperty), read reflectively: its class lives in the script.
        @Suppress("UNCHECKED_CAST")
        task.javaClass.getMethod("getAssets").invoke(task) as org.gradle.api.file.DirectoryProperty
    })
}
