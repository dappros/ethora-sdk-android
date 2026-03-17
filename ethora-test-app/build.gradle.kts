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
        buildConfigField("String", "APP_ID", "\"${env("APP_ID", "699c6923429c2757ac8ab6a4")}\"")
        buildConfigField("String", "API_TOKEN", "\"${env("API_TOKEN", "JWT eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJkYXRhIjp7InBhcmVudEFwcElkIjpudWxsLCJpc0FsbG93ZWROZXdBcHBDcmVhdGUiOnRydWUsImlzQmFzZUFwcCI6dHJ1ZSwiZ29vZ2xlU2VydmljZXNKc29uIjoiIiwiZ29vZ2xlU2VydmljZUluZm9QbGlzdCI6IiIsIlJFQUNUX0FQUF9TVFJJUEVfUFVCTElTSEFCTEVfS0VZIjoiIiwiUkVBQ1RfQVBQX1NUUklQRV9TRUNSRVRfS0VZIjoiIiwic2lnbm9uT3B0aW9ucyI6W10sImFmdGVyTG9naW5QYWdlIjoiY2hhdHMiLCJhdmFpbGFibGVNZW51SXRlbXMiOnsiY2hhdHMiOnRydWUsInByb2ZpbGUiOnRydWUsInNldHRpbmdzIjp0cnVlfSwiYWxsb3dVc2Vyc1RvQ3JlYXRlUm9vbXMiOnRydWUsImFpQm90Ijp7InRyaWdnZXIiOiJhbnlfbWVzc2FnZSIsInByb21wdCI6IllvdSBhcmUgYSBoZWxwZnVsIGFzc2lzdGFudC4iLCJpc1JBRyI6dHJ1ZSwidG90YWxTaXRlU291cmNlU2l6ZSI6MH0sIl9pZCI6IjY5OWM2OTIzNDI5YzI3NTdhYzhhYjZhNCIsImFwcFRva2VucyI6W10sImRlZmF1bHRSb29tcyI6W10sImRpc3BsYXlOYW1lIjoiVml0YWxsIERldiIsImRvbWFpbk5hbWUiOiJhcHAiLCJjcmVhdG9ySWQiOiI2OTljNjkyMzQyOWMyNzU3YWM4YWI2YTUiLCJ1c2Vyc0NhbkZyZWUiOnRydWUsImRlZmF1bHRBY2Nlc3NBc3NldHNPcGVuIjp0cnVlLCJkZWZhdWx0QWNjZXNzUHJvZmlsZU9wZW4iOnRydWUsImJ1bmRsZUlkIjoiY29tLmV0aG9yYSIsInByaW1hcnlDb2xvciI6IiMwMDNFOUMiLCJjb2luU3ltYm9sIjoiRVRPIiwiY29pbk5hbWUiOiJFdGhvcmEgQ29pbiJ9LCJpYXQiOjE3NzE4NTgyMTF9.WGeM2-YpryLsBvNuNJekrfqUf2f6b8lryWZj2ZuEN1w")}\"")
        buildConfigField("String", "XMPP_DEV_SERVER", "\"${env("XMPP_DEV_SERVER", env("APP_XMPP_SERVICE", "wss://xmpp.messenger-dev.vitall.com/ws"))}\"")
        buildConfigField("String", "XMPP_HOST", "\"${env("XMPP_HOST", "xmpp.messenger-dev.vitall.com")}\"")
        buildConfigField("String", "XMPP_CONFERENCE", "\"${env("XMPP_CONFERENCE", env("XMPP_SERVICE", "conference.xmpp.messenger-dev.vitall.com"))}\"")
        buildConfigField("String", "USER_TOKEN", "\"${env("USER_TOKEN", "")}\"")
        buildConfigField("String", "DEFAULT_LOGIN_EMAIL", "\"${env("DEFAULT_LOGIN_EMAIL", "admin@example.com")}\"")
        buildConfigField("String", "DEFAULT_LOGIN_PASSWORD", "\"${env("DEFAULT_LOGIN_PASSWORD", "admin123")}\"")
        buildConfigField("String", "DNS_FALLBACK_OVERRIDES", "\"${env("DNS_FALLBACK_OVERRIDES", "")}\"")
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
