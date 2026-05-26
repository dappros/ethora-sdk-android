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

// Local-only include of the gitignored `sample-chat-app/` folder so that
// `./gradlew :sample-chat-app:assembleDebug` works from the SDK root, as it
// used to before the sample was extracted to dappros/ethora-sample-android.
// Guarded so the build doesn't fail if the folder isn't present locally.
// Do NOT commit a change here that removes the guard — upstream expects this
// file to not reference the sample.
if (file("sample-chat-app/build.gradle.kts").exists()) {
    include(":sample-chat-app")
    project(":sample-chat-app").projectDir = file("sample-chat-app")
}