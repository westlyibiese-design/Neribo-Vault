plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing comes from environment variables so no secret ever lives in the repository.
// When RELEASE_KEYSTORE_PATH is not set (ordinary debug CI), the release build type stays unsigned.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.westly.neribovault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.westly.neribovault"
        minSdk = 24
        targetSdk = 36
        // The release workflow sets VERSION_CODE and VERSION_NAME. Without them the values
        // are the same as before, so ordinary debug builds keep installing over each other.
        versionCode = System.getenv("VERSION_CODE")?.trim()?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.1.0"

        // The shared Neribo cloud project. Read from the SUPABASE_URL and SUPABASE_ANON_KEY
        // environment variables (GitHub Actions secrets) or Gradle properties, never from source.
        // Both are empty in builds without them, and the app then only offers "your own project".
        fun cleanBuildValue(name: String): String {
            val raw = System.getenv(name) ?: (project.findProperty(name) as? String) ?: ""
            return raw.trim().replace("\\", "").replace("\"", "")
        }
        buildConfigField("String", "SUPABASE_URL", "\"${cleanBuildValue("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cleanBuildValue("SUPABASE_ANON_KEY")}\"")
        // Web OAuth client ID for Google sign-in (GitHub secret GOOGLE_WEB_CLIENT_ID). Empty without it.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${cleanBuildValue("GOOGLE_WEB_CLIENT_ID")}\"")
    }

    // Stable debug key so every build installs over the previous one and keeps its data.
    // The keystore file is added after the first green build (see generate-keystore.yml).
    signingConfigs {
        create("neribo") {
            storeFile = file("neribo-debug.keystore")
            storePassword = "android"
            keyAlias = "neribodebug"
            keyPassword = "android"
        }
        val releaseKeystore = releaseKeystorePath
        if (releaseKeystore != null) {
            create("neriboRelease") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (file("neribo-debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("neribo")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("neriboRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Data
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google sign-in (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Other
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // java.time on minSdk 24
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
