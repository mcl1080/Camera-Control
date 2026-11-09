plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mcl1080.cameracontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mcl1080.cameracontrol"
        minSdk = 29
        targetSdk = 35
        // CI passes these from the release tag (see .github/workflows/release.yml).
        versionCode = (findProperty("latlo.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("latlo.versionName") as String?) ?: "0.1.0"
    }

    // Release signing comes from environment variables so the keystore never lives in the
    // repo. Without them, a release build is signed with the debug key so it still installs
    // (fine for local testing, but not for published releases: the release workflow requires them).
    val releaseKeystore = System.getenv("LATLO_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LATLO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LATLO_KEY_ALIAS")
                keyPassword = System.getenv("LATLO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    }
}

dependencies {
    implementation(project(":ptpip"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
