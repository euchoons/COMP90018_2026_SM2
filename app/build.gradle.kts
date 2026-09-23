import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

/*
 * Read local developer configuration outside the Android DSL.
 *
 * PLANTNET_API_KEY can be provided by:
 * 1. local.properties -> PLANTNET_API_KEY
 * 2. local.properties -> plantnet.api.key
 * 3. System environment variable -> PLANTNET_API_KEY
 *
 * local.properties should remain excluded from Git.
 */
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { stream ->
            load(stream)
        }
    }
}

val plantNetApiKey =
    localProperties.getProperty("PLANTNET_API_KEY")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: localProperties.getProperty("plantnet.api.key")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: System.getenv("PLANTNET_API_KEY")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: ""

val escapedPlantNetApiKey = plantNetApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "au.edu.unimelb.floraguide"

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "au.edu.unimelb.floraguide"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "PLANTNET_API_KEY",
            "\"$escapedPlantNetApiKey\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.toVersion(libs.versions.jvmTarget.get())

        targetCompatibility =
            JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            JvmTarget.fromTarget(
                libs.versions.jvmTarget.get(),
            ),
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.exifinterface)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.storage)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    implementation(libs.kotlinx.coroutines.play.services)
}
