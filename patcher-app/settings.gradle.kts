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
