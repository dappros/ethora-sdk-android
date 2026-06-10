import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // androidTest variant merges every dep's META-INF; multiple
            // OSGi-style jars (logging-interceptor, jspecify, smack-*)
            // ship the same MANIFEST.MF under /META-INF/versions/9/OSGI-INF/
            // and AGP's mergeDebugAndroidTestJavaResource fails on the
            // duplicate. The sample-android build has the same exclusion;
            // mirror it here so :ethora-component:connectedDebugAndroidTest
            // gets through the merge step.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    testOptions {
        unitTests {
            // Default to returning Java/Kotlin zero-values for any
            // android.* API the SUT touches (the most common one is
            // android.util.Log.d/.w/.e — logging from production
            // code path otherwise throws "Method not mocked" and
            // makes every test that exercises those code paths
            // fail in JVM unit-test mode. Robolectric is the heavier
            // alternative; this flag is sufficient for our pure-logic
            // tests).
            isReturnDefaultValues = true
        }
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
    implementation(libs.compose.ui.text.google.fonts)

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
    // Coil 3 network fetcher — required for remote http(s) image URLs.
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.mockito.core)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

// JitPack publishing is opt-in: run with -Ppublish=true (or set PUBLISH_SDK=true in env)
// to configure the Maven publication. Regular builds (e.g. the sample app) skip this
// entirely so there is no "[JitPack] Publishing ..." banner and no coupling to
// JitPack metadata during day-to-day development.
val shouldConfigurePublishing = (project.findProperty("publish")?.toString() == "true") ||
    (System.getenv("PUBLISH_SDK") == "true")

if (shouldConfigurePublishing) {
    afterEvaluate {
        val cmdProps = gradle.startParameter.projectProperties
        val resolvedGroup = cmdProps["group"] ?: "com.github.dappros"
        val resolvedVersion = cmdProps["version"] ?: libs.versions.versionName.get()
        println("[JitPack] Publishing ethora-sdk-android → $resolvedGroup:ethora-sdk-android:$resolvedVersion")
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
}
