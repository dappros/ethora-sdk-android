plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.hilt)
    `maven-publish`
}

android {
    namespace = "com.ethora.component"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(
                "../chat-core/src/main/java",
                "../chat-ui/src/main/java"
            )
            res.srcDirs(
                "../chat-core/src/main/res",
                "../chat-ui/src/main/res"
            )
            assets.srcDirs(
                "../chat-core/src/main/assets",
                "../chat-ui/src/main/assets"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

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
    implementation("xpp3:xpp3_min:1.1.4c")

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
}

afterEvaluate {
    val cmdProps = gradle.startParameter.projectProperties
    val resolvedGroup = cmdProps["group"] ?: "com.github.dappros"
    val resolvedVersion = cmdProps["version"] ?: libs.versions.versionName.get()
    // artifactId kept as "ethora-sdk-android" to match the root
    // publication's artifactId. Gradle emits a warning about the
    // coordinate collision — that's expected and benign. The root
    // task ('publishRootPublicationToMavenLocal') runs first and
    // writes a pom-only artifact; this task runs after it
    // (dependsOn wired in the root build.gradle.kts) and overwrites
    // the pom with the real AAR+pom+sources+module files. JitPack's
    // artifact discovery then ships the full AAR bundle at
    // com.github.dappros:ethora-sdk-android:<version>.
    //
    // An earlier experiment (91e542d) gave the two publications
    // distinct artifactIds ("ethora-sdk-android" vs "ethora-component")
    // to eliminate the warning. That broke JitPack: JitPack only
    // exposes the single coordinate it auto-discovers at the root,
    // so the split produced a pom-only artifact with a transitive
    // dep on a second coordinate ("ethora-component") that JitPack
    // never published — consumers 404'd resolving the transitive.
    println("[JitPack] Publishing ethora-sdk-android AAR → $resolvedGroup:ethora-sdk-android:$resolvedVersion")
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = resolvedGroup
                artifactId = "ethora-sdk-android"
                version = resolvedVersion
            }
        }
    }
}
