plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.flareaward.serendip"
    // The current AndroidX / Compose releases ship AAR metadata that requires
    // compileSdk 37. Android Studio installs the platform automatically.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.flareaward.serendip"
        // Android 10+: scoped storage + MediaStore RELATIVE_PATH, modern FGS APIs.
        minSdk = 29
        // Android 16. targetSdk is set explicitly on purpose: AGP 9 would otherwise
        // default it to compileSdk, and the background / foreground-service rules
        // this app is written against are the ones of API 34–36.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9 built-in Kotlin: compiler options live in the top-level `kotlin {}` block.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Kotlin / coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // AndroidX core, lifecycle, activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose (versions from the BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Persistence
    implementation(libs.datastore.preferences)
    // Room 2.7+ ships coroutine/Flow support inside room-runtime (room-ktx is merged).
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)

    // Thumbnails / full-screen photos
    implementation(libs.coil.compose)

    // Unit tests (pure-Kotlin domain layer)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
