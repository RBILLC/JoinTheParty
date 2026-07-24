import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jointheparty.app"
    compileSdk = 35
    // Pinned NDK (technical-requirements.md §4). Note: r28.2 rather than the
    // originally-specced r27 — r27 is not installed on the build hosts and
    // r28.2 is; the pin is what matters. Recorded in docs/.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.jointheparty.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Required by the vendored spotify-auth AAR's manifest (its
        // LoginActivity intent-filter is placeholder-parameterized). Same
        // redirect our own PKCE flow registers (tech-req §3.1).
        manifestPlaceholders["redirectSchemeName"] = "jointheparty"
        manifestPlaceholders["redirectHostName"] = "callback"

        // User-provided app registration (developer.spotify.com dashboard).
        buildConfigField(
            "String", "SPOTIFY_CLIENT_ID",
            "\"e010515b16e34b86b77a2a0798126ede\"",
        )

        // ACRCloud trial credentials live in the GITIGNORED
        // android/local.properties (acr.host / acr.key / acr.secret) so the
        // secret never enters git history. Empty values keep recognition
        // safely inert.
        // (Top-level import: inside android{} the bare name `java` resolves
        // to Gradle's JavaPluginExtension accessor, shadowing the package.)
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "ACR_HOST", "\"${localProps.getProperty("acr.host", "")}\"")
        buildConfigField("String", "ACR_KEY", "\"${localProps.getProperty("acr.key", "")}\"")
        buildConfigField("String", "ACR_SECRET", "\"${localProps.getProperty("acr.secret", "")}\"")

        externalNativeBuild {
            cmake {
                // -DANDROID_STL=c++_shared is required by com.google.oboe's
                // Prefab package (NAT-02, cpp/CMakeLists.txt find_package
                // (oboe)): AGP's Prefab integration reads this argument to
                // select which prebuilt oboe .so variant to expose to CMake
                // *before* CMake itself runs, so it cannot be supplied from
                // CMakeLists.txt — every Oboe integration requires it here.
                // Without it, configureCMakeDebug fails with
                // "User is using a static STL but library requires a
                // shared STL".
                arguments += listOf(
                    "-DSYNCCORE_BUILD_TESTS=OFF",
                    "-DANDROID_STL=c++_shared",
                )
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.4"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        prefab = true  // Oboe ships CMake config via Prefab (NAT-02)
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    // Real Spotify SDKs, vendored from the public GitHub release
    // v0.8.0-appremote_v2.1.0-auth (replaces the com.spotify.* stubs).
    // gson is App Remote's documented transitive requirement.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(files("libs/spotify-auth-release-2.1.0.aar"))
    implementation(libs.gson)
    implementation(libs.oboe)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
