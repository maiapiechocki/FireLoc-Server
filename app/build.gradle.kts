plugins {
    // Apply necessary plugins using aliases from libs.versions.toml
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.googleGmsServices) // Apply Google Services plugin HERE
}

android {
    namespace = "com.fireloc.fireloc" // Ensure this matches your package
    compileSdk = 34 // Use version from libs.versions.toml if defined

    defaultConfig {
        applicationId = "com.fireloc.fireloc" // Corrected package name casing if needed
        minSdk = 26 // Use version from libs.versions.toml if defined
        targetSdk = 34 // Use version from libs.versions.toml if defined
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Keep NDK block for ONNX runtime
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Enable BuildConfig for release if needed for logging check
            // buildConfigField("boolean", "DEBUG", "false")
        }
        debug {
            isMinifyEnabled = false
            // Enable BuildConfig for debug if needed for logging check
            buildConfigField("boolean", "DEBUG", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true // Keep if using ViewBinding
        buildConfig = true // Enable BuildConfig for debug logging check
    }
    sourceSets {
        // Keep assets if your ONNX model is in src/main/assets
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
        // Keep pickFirsts for ONNX Runtime native libs
        jniLibs.pickFirsts.addAll(listOf(
            "lib/arm64-v8a/libc++_shared.so",
            "lib/armeabi-v7a/libc++_shared.so"
        ))
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
    buildToolsVersion = "34.0.0"
}

dependencies {
    // --- Core AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // --- Firebase ---
    implementation(platform(libs.firebase.bom)) // Firebase Bill of Materials
    implementation(libs.firebase.auth.ktx)    // Firebase Authentication (Kotlin extensions)
    implementation("com.google.firebase:firebase-analytics-ktx") // Optional: Analytics

    // --- Google Sign-In & Location ---
    implementation(libs.play.services.auth)   // Google Sign-In SDK (via Play Services)
    // **** ENSURE THIS IS PRESENT AND ALIAS IS CORRECT ****
    implementation(libs.play.services.location) // Google Location Services

    // --- Networking (for /registerDevice call) ---
    implementation(libs.retrofit)             // Retrofit
    implementation(libs.converter.gson)       // Gson converter for Retrofit
    implementation(libs.logging.interceptor)  // OkHttp logging interceptor

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android) // For background tasks and async operations

    // --- CameraX ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // --- Lifecycle & Activity ---
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx) // Activity KTX for registerForActivityResult etc.

    // --- ONNX Runtime (Keep if YoloModel.kt is used) ---
    implementation(libs.onnxruntime.android) // Assuming alias exists

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}