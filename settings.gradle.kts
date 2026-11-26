// Nihongo Flow
//
// The core modules are plain Kotlin with no Android dependency, so they build and
// test anywhere a JDK is available, and can later move to Kotlin Multiplatform for
// an iOS client. The Android app module is only included when an Android SDK is
// present (CI always has one).

import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

fun androidSdkAvailable(): Boolean {
    if (!System.getenv("ANDROID_HOME").isNullOrBlank()) return true
    if (!System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()) return true
    val localProps = file("local.properties")
    if (localProps.isFile) {
        val props = Properties().apply { localProps.inputStream().use { load(it) } }
        if (!props.getProperty("sdk.dir").isNullOrBlank()) return true
    }
    return false
}

val hasAndroidSdk = androidSdkAvailable()
gradle.extra["hasAndroidSdk"] = hasAndroidSdk

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (hasAndroidSdk) google()
    }
}

rootProject.name = "nihongo-flow"

include(":core:model")
include(":core:scheduler")
include(":core:holiday")
include(":core:workload")
include(":core:study")

if (hasAndroidSdk) {
    include(":data")
    include(":app")
} else {
    logger.lifecycle("Nihongo Flow: no Android SDK found, building the core modules only.")
}
