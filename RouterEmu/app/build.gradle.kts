plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.routeremu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.routeremu"
        minSdk = 26          // Foreground services + modern process APIs
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // The native qemu-system-mips binary must be built for the ABI(s)
        // you intend to ship. We restrict packaging to those ABIs so the
        // executable we drop into filesDir actually matches the device.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // qemu-system-mips (and any shared libs it needs) live here. Gradle will
    // package them into the APK under assets/, from which we copy the binary
    // into context.filesDir at runtime and chmod it executable (native libs
    // placed under jniLibs are stripped/relinked by the build system, which
    // we don't want for a standalone emulator binary — assets keeps it inert).
    sourceSets["main"].assets.srcDirs("src/main/assets")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
