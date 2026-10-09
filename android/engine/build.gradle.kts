plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// This module owns every reference to a concrete rendering engine in the tree.
// `:app` depends on it as `implementation`, so `android.webkit` is not on
// `:app`'s compile classpath -- the facade in com.roombrowser.engine is the
// only thing that crosses the boundary, and EngineBoundaryTest enforces it.
android {
    namespace = "com.roombrowser.engine"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 28
        // No targetSdk: a library is not an application and AGP rejects one
        // here. The consuming :app owns the platform behaviour contract.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    // `api`, not `implementation`: Profile and ProfileId appear in the facade
    // signatures, so every consumer needs them on its own classpath.
    api(project(":core:domain"))

    // The DOCUMENT_START_SCRIPT API (WebViewCompat.addDocumentStartJavaScript)
    // is the only way the page-world scripts can be installed before the page's
    // own first script. Not `api`: no androidx.webkit type appears in a facade
    // signature.
    implementation(libs.androidx.webkit)

    // setProxy suspends until the engine acknowledges the change; a page loaded
    // before that point would go out over the real address.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
