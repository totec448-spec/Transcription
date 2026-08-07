import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing material lives outside the repository. A clone without it still
// builds — the release type falls back to the debug key below — so the absent
// file is a normal state, not an error.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.example.transcription"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.totec448spec.transcription"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Debug-signed only when no release keystore is present, which keeps
            // a fresh clone buildable. Such a build is not distributable.
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
            // AGP otherwise stamps META-INF/version-control-info.textproto into the
            // APK with the commit the build came from. Shipping a build's git
            // revision inside the binary is state the artifact does not need.
            vcsInfo {
                include = false
            }
            optimization {
                enable = false
            }
        }
        debug {
            // Same reasoning as the release type. Debug APKs are attached to CI
            // runs as artifacts, so they leave the machine too.
            vcsInfo {
                include = false
            }
        }
    }

    // AGP otherwise writes an encrypted dependency list into the APK signing
    // block (pair ID 0x504b4453). It exists for Play Console diagnostics, which
    // this app is not distributed through, so it is metadata the artifact does
    // not need either.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // Only the kotlinx-coroutines debug agent reads this, and nothing
            // here enables that agent. The okhttp public-suffix list stays: it
            // is data okhttp actually reads at runtime.
            excludes += "DebugProbesKt.bin"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.ffmpeg.kit.full)
    // ffmpeg-kit-maintained 8.1.7 references this tiny runtime helper but its POM
    // does not declare it. Without the explicit dependency MP3 conversion succeeds
    // at native level and then crashes while FFmpegKit builds the Java session result.
    implementation(libs.smart.exception.java)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
