plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.hilt)
    `maven-publish`
}

android {
    namespace = "com.ethora.chat.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // XMPP - exclude duplicate xpp3 classes
    implementation("org.igniterealtime.smack:smack-core:4.4.7") {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("org.igniterealtime.smack:smack-tcp:4.4.7") {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("org.igniterealtime.smack:smack-extensions:4.4.7") {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("org.igniterealtime.smack:smack-android:4.4.7") {
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation("org.igniterealtime.smack:smack-android-extensions:4.4.7") {
        exclude(group = "xpp3", module = "xpp3")
    }
    // WebSocket support - using OkHttp WebSocket directly
    // Note: Smack WebSocket modules not available for 4.4.7, will implement custom WebSocket handler
    // Use xpp3_min to avoid duplicates
    implementation("xpp3:xpp3_min:1.1.4c")

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Dependency Injection
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.ethora"
                artifactId = "chat-core"
                
                val providedVersion = project.findProperty("version")?.toString()
                version = if (!providedVersion.isNullOrBlank() && providedVersion != "unspecified") {
                    providedVersion
                } else {
                    System.getenv("VERSION") ?: libs.versions.versionName.get()
                }
            }
        }
    }
}
