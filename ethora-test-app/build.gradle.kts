plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.hilt)
}

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
    namespace = "com.ethora.test.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ethora.test.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"${env("API_BASE_URL", "https://api.messenger-dev.vitall.com")}\"")
        buildConfigField("String", "APP_ID", "\"${env("APP_ID", "646cc8dc96d4a4dc8f7b2f2d")}\"")
        buildConfigField("String", "API_TOKEN", "\"${env("API_TOKEN", "")}\"")
        buildConfigField("String", "XMPP_DEV_SERVER", "\"${env("XMPP_DEV_SERVER", "wss://xmpp.messenger-dev.vitall.com/ws")}\"")
        buildConfigField("String", "XMPP_HOST", "\"${env("XMPP_HOST", "xmpp.messenger-dev.vitall.com")}\"")
        buildConfigField("String", "XMPP_CONFERENCE", "\"${env("XMPP_CONFERENCE", "conference.xmpp.messenger-dev.vitall.com")}\"")
        buildConfigField("String", "USER_TOKEN", "\"${env("USER_TOKEN", "")}\"")
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
        isCoreLibraryDesugaringEnabled = true
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(project(":ethora-component"))
    
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-installations-ktx")

    debugImplementation(libs.compose.ui.tooling)
}
