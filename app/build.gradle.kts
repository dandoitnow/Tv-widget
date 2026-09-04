plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.tvwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.tvwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
        // Glance is built on the Compose runtime, so the Compose compiler is required
        // even though the app ships no Compose UI of its own.
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Jetpack Glance (widgets) — carries the DataStore dependency used for widget state.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Daily refresh of the anticipated list.
    implementation(libs.androidx.work.runtime.ktx)

    // Widget state + cached feeds are persisted as JSON in DataStore.
    implementation(libs.kotlinx.serialization.json)
}
