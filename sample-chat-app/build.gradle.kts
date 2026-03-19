plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ethora.samplechatapp"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ethora.samplechatapp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "ETHORA_APP_ID", "\"CHANGE_ME_APP_ID\"")
        buildConfigField("String", "ETHORA_API_BASE_URL", "\"CHANGE_ME_API_BASE_URL\"")
        buildConfigField("String", "ETHORA_APP_TOKEN", "\"CHANGE_ME_APP_TOKEN\"")
        buildConfigField("String", "ETHORA_USER_JWT", "\"\"")
        buildConfigField("String", "ETHORA_ROOM_JID", "\"CHANGE_ME_ROOM_JID\"")
        buildConfigField("String", "ETHORA_XMPP_SERVER_URL", "\"wss://CHANGE_ME_XMPP_WS\"")
        buildConfigField("String", "ETHORA_XMPP_HOST", "\"CHANGE_ME_XMPP_HOST\"")
        buildConfigField("String", "ETHORA_XMPP_CONFERENCE", "\"conference.CHANGE_ME_XMPP_HOST\"")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    val useLocalEthora = providers.gradleProperty("ethoraLocal")
        .map { it.toBoolean() }
        .orElse(false)
        .get()

    if (useLocalEthora) {
        implementation(project(":ethora-component"))
    } else {
        implementation("com.github.dappros.ethora-sdk-android:ethora-component:v1.0.1")
    }

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
