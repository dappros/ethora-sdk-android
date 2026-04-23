// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.6.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.48")
        classpath("com.google.gms:google-services:4.4.4")
    }
}

plugins {
    id("maven-publish")
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
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

// Root publication — intentionally claims the same coordinate as
// :ethora-component's 'release' publication. Gradle warns about this
// ("Multiple publications with coordinates ... will overwrite each
// other"), which is the behaviour we're relying on: the root task
// runs first (pom-only), :ethora-component runs second (real AAR +
// pom + sources + module) and overwrites the pom with the full
// artifact bundle. JitPack's discovery then serves the full AAR at
//   com.github.dappros:ethora-sdk-android:<version>
//
// This is how v1.0.21 shipped and how it kept working. Two
// alternative factorings were tried on tf-dev and both regressed:
//   5645139 — dropped the root publication. JitPack's discovery
//             step expects a root POM and returned 'No build
//             artifacts found'.
//   91e542d — split root (artifactId=ethora-sdk-android, pom-only)
//             and :ethora-component (artifactId=ethora-component,
//             AAR) into distinct coordinates. JitPack only exposes
//             one coordinate per repo, so the split produced a
//             pom-only artifact with a transitive dep on a second
//             coordinate that JitPack never published — consumers
//             404'd resolving the transitive.
//
// Reverting to the v1.0.21 layout because it's the shape JitPack
// actually supports. Cleaning up the warning would need either a
// different distribution (e.g. publishing directly to Maven Central
// / Google Maven with per-module coordinates) or a larger refactor
// where the SDK is a single module, neither of which is in scope for
// tf-dev testing.
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
