plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.ethora.samplechatapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ethora"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "ETHORA_APP_ID", "\"6998429ba125477a74a7dcef\"")
        buildConfigField("String", "ETHORA_API_BASE_URL", "\"https://api-dev.preshent.com/v1/\"")
        buildConfigField("String", "ETHORA_USER_JWT", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJkYXRhIjp7InR5cGUiOiJjbGllbnQiLCJ1c2VySWQiOiIwMTk5ZGRiNS1iZGE5LTc1NWEtYmRmMS0xYTdkMWNmYjdiZGEiLCJhcHBJZCI6IjY5YTgzYjczOTRjNzhiYzFlZDMyYWM3NSJ9fQ.TAFxbKnRrdq-CGnaxwDvh081XWCftJlOOs54W8w_i-o\"")
        buildConfigField("String", "ETHORA_ROOM_JID", "\"6998429ba125477a74a7dcef_test-room-v\"")
        buildConfigField("String", "ETHORA_XMPP_SERVER_URL", "\"wss://xmpp-dev.preshent.com/ws\"")
        buildConfigField("String", "ETHORA_XMPP_HOST", "\"xmpp-dev.preshent.com\"")
        buildConfigField("String", "ETHORA_XMPP_CONFERENCE", "\"conference.xmpp-dev.preshent.com\"")
        buildConfigField(
            "String",
            "ETHORA_DNS_FALLBACK_OVERRIDES",
            "\"api-dev.preshent.com=34.174.203.35,xmpp-dev.preshent.com=34.174.203.35,conference.xmpp-dev.preshent.com=34.174.203.35\""
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
    implementation("com.github.dappros:ethora-sdk-android:v1.0.19")
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
