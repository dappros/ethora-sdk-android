// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.5.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.48")
        classpath("com.google.gms:google-services:4.4.4")
    }
}

plugins {
    // `maven-publish` no longer applied at the root. The former root
    // publication (see the removed block below) collided with
    // :ethora-component's own publication on the same coordinate
    // `com.github.dappros:ethora-sdk-android:<version>`, and JitPack
    // shipped whichever of the two the build processed last — so the
    // real AAR from :ethora-component was getting silently overwritten
    // by a pom-only proxy that just listed ethora-component as a
    // dependency.
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.48" apply false
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

// (Root publishing {} block intentionally removed — see plugins{} comment.)
//
// Existing consumers of `com.github.dappros:ethora-sdk-android:<version>`
// continue to work: :ethora-component's publication keeps that exact
// artifactId and now ships the real AAR directly rather than a pom-only
// proxy. Contents are identical — chat-core + chat-ui sources are
// merged into ethora-component via its sourceSets, so a transitive
// dependency on "ethora-component" was never actually needed.
