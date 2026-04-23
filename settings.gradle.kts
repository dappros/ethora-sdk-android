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

// Include sample app as a single Android-application module under the
// logical path `:sample-chat-app`. The actual project lives in the
// `sample-chat-app/app/` directory (the sample keeps its own `app`
// subfolder so it can also be opened/built standalone via its own
// wrapper in `sample-chat-app/`). Redirecting the projectDir here lets
// you run from the SDK root:
//     ./gradlew :sample-chat-app:installDebug
// without any restructuring of the sample source layout.
include(":sample-chat-app")
project(":sample-chat-app").projectDir = file("sample-chat-app/app")
