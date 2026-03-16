plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.hilt)
    id("com.google.gms.google-services")
}

// Load .env file (like React's env) - values are injected into BuildConfig at build time
fun loadEnv(): Map<String, String> {
    val envFile = file("${projectDir}/.env")
    if (!envFile.exists()) return emptyMap()
    return envFile.readLines()
        .filter { it.contains("=") && !it.trimStart().startsWith("#") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            val v = value.trim()
            val unquoted = if (v.length >= 2 && v.first() == '"' && v.last() == '"') v.drop(1).dropLast(1) else v
            key.trim() to unquoted.trim()
        }
}

val env = loadEnv()

fun env(key: String, default: String) = env[key]?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "com.ethora.chat.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ethora.chat.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injected from .env (aligned with React VITE_ETHORA_* / preshent-mobile PRESHENT_*)
        buildConfigField("String", "API_BASE_URL", "\"${env("API_BASE_URL", env("CHAT_BASE_URL", "https://api.ethoradev.com/v1"))}\"")
        buildConfigField("String", "APP_ID", "\"${env("APP_ID", "646cc8dc96d4a4dc8f7b2f2d")}\"")
        buildConfigField("String", "API_TOKEN", "\"${env("API_TOKEN", "")}\"")
        buildConfigField("String", "XMPP_DEV_SERVER", "\"${env("APP_XMPP_SERVICE", env("XMPP_DEV_SERVER", "wss://xmpp.ethoradev.com:5443/ws"))}\"")
        buildConfigField("String", "XMPP_HOST", "\"${env("XMPP_HOST", "xmpp.ethoradev.com")}\"")
        buildConfigField("String", "XMPP_CONFERENCE", "\"${env("XMPP_SERVICE", env("XMPP_CONFERENCE", "conference.xmpp.ethoradev.com"))}\"")
        buildConfigField("String", "DEFAULT_LOGIN_EMAIL", "\"${env("DEFAULT_LOGIN_EMAIL", "yukiraze9@gmail.com")}\"")
        buildConfigField("String", "DEFAULT_LOGIN_PASSWORD", "\"${env("DEFAULT_LOGIN_PASSWORD", "Qwerty123")}\"")
        buildConfigField("String", "USER_TOKEN", "\"${env("USER_TOKEN", env("CHAT_TOKEN", ""))}\"")
        buildConfigField("Boolean", "USE_PRESHENT_JWT_AUTH", "${env("USE_PRESHENT_JWT_AUTH", "false").lowercase() == "true"}")
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
        jvmTarget = libs.versions.jvmTarget.get()
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    // Chat modules
    implementation(project(":chat-core"))
    implementation(project(":chat-ui"))
    
    // AppCompat for Material theme
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Dependency Injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-installations-ktx")

    // Testing
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
