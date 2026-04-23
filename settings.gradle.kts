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
// sample-chat-app module extracted into the dappros/ethora-sample-android
// repo on 2026-04-21 (commit 86fd0a2 "Stop tracking sample-chat-app").
// The directory no longer exists in this repo, so the stale include was
// failing standalone builds and would fail composite-build consumers too.
