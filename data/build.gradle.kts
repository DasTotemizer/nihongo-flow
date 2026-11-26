// On-device storage: Room implementation of the StudyStore port from :core:study.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.nihongoflow.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Every schema version is committed, so later versions can be migration-tested.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:study"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(testFixtures(project(":core:study")))
    testImplementation(libs.kotlin.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}

// Robolectric normally downloads its Android runtime jar itself, outside Gradle. Resolving
// it through Gradle instead uses the same repositories, proxy settings and cache as every
// other dependency, and the tests then run with Robolectric in offline mode.
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
tasks.withType<Test>().configureEach {
    val runtimeJars = robolectricRuntime
    val jarDir = layout.buildDirectory.dir("robolectric-runtime")
    inputs.files(runtimeJars)
    doFirst {
        val dir = jarDir.get().asFile.apply { mkdirs() }
        runtimeJars.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir.absolutePath)
    }
}
