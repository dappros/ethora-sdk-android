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
    id("maven-publish")
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

// Root publication restored after the 5645139 experiment.
//
// Background: v1.0.21's root 'root' publication and :ethora-component's
// 'release' publication both claimed artifactId 'ethora-sdk-android'
// and collided — Gradle warned, JitPack shipped whichever Gradle
// processed last. Experiment 5645139 removed the root publication
// entirely; the build compiled fine, but JitPack's artifact-discovery
// step expects a root-level POM at the canonical coordinate and
// returned 'No build artifacts found' (build 9b8126f — "BUILD
// SUCCESSFUL / No build artifacts found").
//
// Fixing both the collision AND the discovery gap by giving the two
// publications DISTINCT artifactIds:
//
//   com.github.dappros:ethora-sdk-android:<version>  — pom-only,
//     from this root publication. Serves existing consumers; its POM
//     declares a transitive runtime dependency on ethora-component so
//     Gradle resolves the real AAR when someone writes:
//         implementation("com.github.dappros:ethora-sdk-android:<ver>")
//
//   com.github.dappros:ethora-component:<version>  — the real AAR,
//     produced by :ethora-component (see ethora-component/build.gradle.kts
//     afterEvaluate block — its artifactId is also renamed to
//     "ethora-component" in the same commit that brings this block back).
//
// JitPack's yml invokes both publishToMavenLocal tasks, so both POMs
// land in ~/.m2 and both coordinates become resolvable.
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
                dependencyNode.appendNode("scope", "runtime")
            }
        }
    }
}

tasks.named("publishToMavenLocal") {
    dependsOn(":ethora-component:publishReleasePublicationToMavenLocal")
}
