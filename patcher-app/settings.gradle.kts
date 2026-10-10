pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android and its spake2 binding are only published on JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeGroup("com.github.MuntashirAkon")
                includeGroupByRegex("com\\.github\\.MuntashirAkon\\..*")
            }
        }
    }
}

rootProject.name = "openshm-patcher"

include(":engine", ":adb", ":app")

// Development-only Compose Driver harness for agent UI inspection (see tools/compose-driver/README.md).
// Configured only when one of its tasks is requested, or with -Popenshm.composeDriver for IDE sync,
// so regular builds and test runs never resolve or compile it.
if (
    gradle.startParameter.taskNames.any { it.startsWith(":tools:compose-driver") } ||
    providers.gradleProperty("openshm.composeDriver").isPresent
) {
    include(":tools:compose-driver")
}
