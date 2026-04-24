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

// Note: `:sample-chat-app` was previously included here as a nested
// module, but the sample was extracted to its own repository
// (dappros/ethora-sample-android) on 2026-04-21 and the
// `sample-chat-app/` directory no longer exists in this repo.
// Re-including it makes Gradle fail configuration before any task
// can run. Host apps now consume the SDK either via JitPack
// (com.github.dappros:ethora-sdk-android) or via a composite build
// (`includeBuild` pointing at this repo from the sample's
// settings.gradle.kts).
