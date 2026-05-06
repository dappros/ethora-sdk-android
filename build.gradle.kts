// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.51.1")
        classpath("com.google.gms:google-services:4.4.4")
    }
}

plugins {
    id("maven-publish")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}

group =
    (findProperty("group") as String?)
        ?: System.getenv("GROUP")
        ?: "com.github.dappros"
version =
    (findProperty("version") as String?)
        ?: System.getenv("VERSION")
        ?: "local-SNAPSHOT"

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    group = rootProject.group
    version = rootProject.version
    layout.buildDirectory.set(file("/tmp/android_build/${rootProject.name}/${project.name}"))
}

// Root POM aggregator at `com.github.dappros:ethora-sdk-android:<version>` with
// packaging=pom. JitPack's post-build sweep needs this artifact to register the
// build as a multi-module publication; without it the AAR sits in ~/.m2 but
// JitPack returns "No build artifacts found" (verified on the v1.0.32 build
// where this publication was removed).
//
// The :ethora-component module also publishes at the SAME coordinate. Gradle
// prints a "Multiple publications with coordinates ... will overwrite each
// other" warning. The warning is benign and intentional: the install command
// in jitpack.yml runs the root publish task FIRST, then the module publish
// task, so the module's POM (with the real transitive dependency list) wins
// in ~/.m2 and is what integrators get when resolving
// `com.github.dappros:ethora-sdk-android:<version>`. v1.0.30 and earlier
// shipped with this exact warning and resolved cleanly on JitPack — it was
// only the v1.0.32 attempt without the root publication that broke discovery.
publishing {
    publications {
        create<MavenPublication>("root") {
            val resolvedGroupId = project.group.toString()
            val resolvedVersion = project.version.toString()

            groupId = resolvedGroupId
            artifactId = "ethora-sdk-android"
            version = resolvedVersion
            pom.packaging = "pom"
            pom.withXml {
                val dependenciesNode = asNode().appendNode("dependencies")
                val dependencyNode = dependenciesNode.appendNode("dependency")
                dependencyNode.appendNode("groupId", resolvedGroupId)
                dependencyNode.appendNode("artifactId", "ethora-component")
                dependencyNode.appendNode("version", resolvedVersion)
            }
        }
    }
}

tasks.named("publishToMavenLocal") {
    dependsOn(":ethora-component:publishReleasePublicationToMavenLocal")
}
