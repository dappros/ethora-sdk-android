pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ethora-chat-android"

include(":ethora-component")

// The sample app is NOT included in this settings file — this build is the
// SDK only (chat-core + chat-ui source sets collected under :ethora-component).
// The sample has its own self-contained Gradle build under `sample-chat-app/`
// with its own `settings.gradle.kts` and wrapper; build it from there.
