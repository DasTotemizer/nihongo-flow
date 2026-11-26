import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.nihongoflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nihongoflow"
        minSdk = 26 // java.time, used by the FSRS library
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

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
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Robolectric's Android runtime jar, resolved by Gradle (see data/build.gradle.kts).
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
tasks.withType<Test>().configureEach {
    val runtimeJars = robolectricRuntime
    val jarDir = layout.buildDirectory.dir("robolectric-runtime")
    val shotDir = layout.buildDirectory.dir("screenshots")
    inputs.files(runtimeJars)
    doFirst {
        val dir = jarDir.get().asFile.apply { mkdirs() }
        runtimeJars.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir.absolutePath)
        systemProperty("screenshot.dir", shotDir.get().asFile.absolutePath)
    }
}
