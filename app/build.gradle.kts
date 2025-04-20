plugins {
    // Apply necessary plugins using aliases from libs.versions.toml
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.googleGmsServices) // Apply Google Services plugin HERE
}

android {
    namespace = "com.fireloc.fireloc" // Ensure this matches your package
    compileSdk = 34

    defaultConfig {
        applicationId = "com.FireLoc.Fireloc"
        minSdk = 26
        targetSdk = 34
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
        }
        debug {
            isMinifyEnabled = false
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
}

dependencies {
    // --- Core AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // --- Firebase ---
    // Import the Firebase BoM - Declare only ONCE using the alias
    implementation(platform(libs.firebase.bom))
    // Declare Firebase products without versions
    implementation(libs.firebase.auth.ktx)
    implementation("com.google.firebase:firebase-analytics") // Example analytics dependency

    // --- Google Sign-In ---
    implementation(libs.play.services.auth)

    // --- Networking ---
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)

    // --- CameraX ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // --- Lifecycle & Activity ---
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)

    // --- ONNX Runtime ---
    implementation(libs.onnxruntime.android)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // --- REMOVED Duplicate Firebase BOM ---
    // implementation(platform("com.google.firebase:firebase-bom:33.12.0")) <-- REMOVED

}