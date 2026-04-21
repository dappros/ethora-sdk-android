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
