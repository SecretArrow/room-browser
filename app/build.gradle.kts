import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val baseVersionName = "1.0.0"
// CI produces incrementing version codes per pipeline run (spec section 57);
// local builds fall back to 1.
val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val baseVersionCode = if (ciBuildNumber > 0) ciBuildNumber else 1

android {
    namespace = "com.roombrowser"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.roombrowser"
        minSdk = 28
        targetSdk = 35
        versionCode = baseVersionCode
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // ABI-split release builds (spec section 57)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Per-ABI output naming + distinct version codes (modern Variant API).
    androidComponents {
        onVariants { variant ->
            val buildTypeName = variant.buildType ?: "release"
            variant.outputs.forEach { output ->
                val impl = output as? VariantOutputImpl ?: return@forEach
                val abi = impl.filters.firstOrNull()?.identifier
                val abiRank = when (abi) {
                    "arm64-v8a" -> 4
                    "armeabi-v7a" -> 3
                    "x86_64" -> 2
                    "x86" -> 1
                    else -> 0
                }
                impl.versionCode.set(abiRank * 100_000 + baseVersionCode)
                impl.outputFileName.set(
                    "room-browser-v$baseVersionName-${abi ?: "universal"}-$buildTypeName.apk"
                )
            }
        }
    }

    val envKeystore = providers.environmentVariable("ROOMBROWSER_KEYSTORE").orNull
    val envStorePassword = providers.environmentVariable("ROOMBROWSER_STORE_PASSWORD").orNull
    val envKeyAlias = providers.environmentVariable("ROOMBROWSER_KEY_ALIAS").orNull
    val envKeyPassword = providers.environmentVariable("ROOMBROWSER_KEY_PASSWORD").orNull

    signingConfigs {
        if (envKeystore != null && envStorePassword != null && envKeyAlias != null && envKeyPassword != null) {
            create("releaseFromEnv") {
                storeFile = file(envKeystore)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing configuration comes from environment variables —
            // no secrets are ever committed to Git (spec section 58).
            signingConfig = signingConfigs.findByName("releaseFromEnv")
                ?: signingConfigs.getByName("debug") // dev fallback only
        }
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("releaseFromEnv")
                ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.okhttp)
    implementation(libs.okhttp.doh)
    implementation(libs.zxing.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
