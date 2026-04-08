plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services") apply false
}

val hasGoogleServicesJson = listOf(
    file("google-services.json"),
    file("src/google-services.json"),
    file("src/debug/google-services.json"),
    file("src/release/google-services.json")
).any { it.exists() }

if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
    println("sample-chat-app: google-services.json found, enabling Firebase Google Services plugin")
} else {
    println("sample-chat-app: google-services.json not found, building without Google Services plugin")
}

fun loadEnvFile(): Map<String, String> {
    val candidates = listOf(
        rootProject.file("chat-app/.env"),
        rootProject.file(".env"),
        project.file(".env"),
        file("${rootDir}/sample-chat-app/.env")
    )
    val envFile = candidates.firstOrNull { it.exists() && it.isFile } ?: return emptyMap()
    println("sample-chat-app: using env file ${envFile.absolutePath}")
    return envFile.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf("=")
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSurrounding("\"")
            key to value
        }
}

val fileEnv = loadEnvFile()

fun envOrDefault(vararg keys: String, default: String = ""): String {
    for (key in keys) {
        val fromSystem = System.getenv(key)?.takeIf { it.isNotBlank() }
        if (fromSystem != null) return fromSystem
        val fromFile = fileEnv[key]?.takeIf { it.isNotBlank() }
        if (fromFile != null) return fromFile
    }
    return default
}

android {
    namespace = "com.ethora.samplechatapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Keep package aligned with IDE run target to avoid "Activity class does not exist" mismatch.
        applicationId = "com.ethora"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "ETHORA_APP_ID", "\"${envOrDefault("ETHORA_APP_ID", "APP_ID", default = "CHANGE_ME_APP_ID")}\"")
        buildConfigField("String", "ETHORA_API_BASE_URL", "\"${envOrDefault("ETHORA_API_BASE_URL", "API_BASE_URL", default = "CHANGE_ME_API_BASE_URL")}\"")
        buildConfigField("String", "ETHORA_USER_JWT", "\"${envOrDefault("ETHORA_USER_JWT", "USER_TOKEN", default = "")}\"")
        buildConfigField("String", "ETHORA_ROOM_JID", "\"${envOrDefault("ETHORA_ROOM_JID", "ROOM_JID", default = "")}\"")
        buildConfigField("String", "ETHORA_XMPP_SERVER_URL", "\"${envOrDefault("ETHORA_XMPP_SERVER_URL", "XMPP_SERVER_URL", default = "CHANGE_ME_XMPP_SERVER_URL")}\"")
        buildConfigField("String", "ETHORA_XMPP_HOST", "\"${envOrDefault("ETHORA_XMPP_HOST", "XMPP_HOST", default = "CHANGE_ME_XMPP_HOST")}\"")
        buildConfigField("String", "ETHORA_XMPP_CONFERENCE", "\"${envOrDefault("ETHORA_XMPP_CONFERENCE", "XMPP_CONFERENCE", default = "CHANGE_ME_XMPP_CONFERENCE")}\"")
        buildConfigField(
            "String",
            "ETHORA_DNS_FALLBACK_OVERRIDES",
            "\"${envOrDefault("ETHORA_DNS_FALLBACK_OVERRIDES", "DNS_FALLBACK_OVERRIDES", default = "")}\""
        )
        println(
            "sample-chat-app BuildConfig: applicationId=com.ethora, " +
                "appId=${envOrDefault("ETHORA_APP_ID", "APP_ID", default = "CHANGE_ME_APP_ID")}, " +
                "apiBase=${envOrDefault("ETHORA_API_BASE_URL", "API_BASE_URL", default = "CHANGE_ME_API_BASE_URL")}"
        )
    }

    // Match source test host: use custom debug keystore if it exists.
    signingConfigs {
        getByName("debug") {
            val debugKeystore = file("debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            val debugKeystore = file("debug.keystore")
            if (debugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(project(":ethora-component"))
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-common")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
