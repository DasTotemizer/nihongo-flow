import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.nihongoflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nihongoflow"
        minSdk = 26 // java.time, used by the FSRS library
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // A fixed debug key (committed, debug-only, not a secret) so each new CI build installs
    // over the previous one on a phone instead of needing an uninstall.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // The Anki import tests use the same packages as core:anki, made by the real Anki.
    sourceSets["test"].resources.srcDir("../core/anki/src/test/resources")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/module-info.class",
            )
        }
    }
}

ksp {
    // Exported schemas are committed so every future migration can be tested against them.
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scheduler"))
    implementation(project(":core:holiday"))
    implementation(project(":core:workload"))
    implementation(project(":core:study"))
    implementation(project(":core:anki")) {
        // The desktop jar is swapped for the Android build of the same library.
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.kotlin.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Desktop natives for zstd, so Robolectric tests can read current-format packages.
    testImplementation(libs.zstd.jni)
}
