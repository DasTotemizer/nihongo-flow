buildscript {
    // The Android Gradle plugin goes on the root classpath only when an SDK is
    // present, so the pure-Kotlin core still builds on machines without one.
    val hasAndroidSdk = gradle.extra["hasAndroidSdk"] as Boolean
    repositories {
        mavenCentral()
        if (hasAndroidSdk) google()
    }
    dependencies {
        if (hasAndroidSdk) classpath(libs.android.gradle)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Shared setup for the pure-Kotlin core modules.
subprojects {
    if (path.startsWith(":core:")) {
        apply(plugin = "java-library")
        apply(plugin = "org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(false)
            }
        }
        val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
            "testImplementation"(catalog.findLibrary("kotlin-test").get())
            "testImplementation"(catalog.findLibrary("junit-jupiter").get())
            "testRuntimeOnly"(catalog.findLibrary("junit-launcher").get())
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}
