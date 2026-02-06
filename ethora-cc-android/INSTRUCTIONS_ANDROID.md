# Ethora Android Chat SDK Integration Guide

This folder contains a standalone version of the Ethora Chat Component for Android. You can integrate this into your existing Android project by following these steps.

## Included Modules
- `chat-core`: Core logic, networking, XMPP client, and data stores.
- `chat-ui`: Jetpack Compose components and the main `Chat` entry point.
- `gradle/libs.versions.toml`: Version catalog containing all required dependencies.

## Installation Steps

### 1. Copy the SDK folder
Copy the `ethora-chat-sdk` folder into your project's root directory.

### 2. Include modules in `settings.gradle.kts`
Open your project's `settings.gradle.kts` and add the following lines:

```kotlin
include(":chat-core")
include(":chat-ui")

project(":chat-core").projectDir = file("ethora-chat-sdk/chat-core")
project(":chat-ui").projectDir = file("ethora-chat-sdk/chat-ui")
```

### 3. Configure Version Catalog
The SDK utilizes a Version Catalog (`libs.versions.toml`) to manage all dependencies and plugin versions consistently.

1. **Copy the file**: Copy `ethora-chat-sdk/gradle/libs.versions.toml` to your project's `gradle/` folder (overwrite or merge if you already have one).
2. **Register the catalog**: Open your **ROOT** `settings.gradle.kts` and ensure the `dependencyResolutionManagement` section looks like this:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}
```

### 4. Apply Plugins in your Root Project
Your root `build.gradle.kts` should define the necessary plugins (Hilt, Kotlin, Android) to match the SDK requirements:

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.48" apply false
}
```

### 5. Add dependencies to your App module
In your app's `build.gradle.kts`, add:

```kotlin
dependencies {
    implementation(project(":chat-core"))
    implementation(project(":chat-ui"))
    
    // Ensure you have Hilt enabled as the SDK depends on it
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-android-compiler:2.48")
}
```

### 5. Hilt Requirement
The SDK uses **Hilt** for dependency injection. Your `Application` class must be annotated with `@HiltAndroidApp`:

```kotlin
@HiltAndroidApp
class MyApplication : Application()
```

And your Activity must be annotated with `@AndroidEntryPoint`:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity()
```

## Usage Example

Once the sync is complete, you can use the `Chat` component in your Compose UI:

```kotlin
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import com.ethora.chat.Chat
import com.ethora.chat.core.config.*
import com.ethora.chat.core.service.LogoutService

fun getChatConfig(chatToken: String): ChatConfig {
    return ChatConfig(
        disableHeader = true,
        disableRooms = true,
        jwtLogin = JWTLoginConfig(token = chatToken, enabled = true),
        enableRoomsRetry = EnableRoomsRetryConfig(enabled = true, helperText = "Initializing..."),
        newArch = true,
        baseUrl = "https://api.ethoradev.com/v1",
        xmppSettings = XMPPSettings(
            devServer = "wss://xmpp.ethoradev.com:5443/ws",
            host = "xmpp.ethoradev.com",
            conference = "conference.xmpp.ethoradev.com"
        )
    )
}

@Composable
fun MyChatScreen(chatToken: String, onLogout: () -> Unit) {
    val config = remember(chatToken) { getChatConfig(chatToken) }
    
    Scaffold { padding ->
        Chat(
            config = config,
            modifier = Modifier.padding(padding)
        )
    }
}
```

## Troubleshooting
- **Dependency Conflicts**: If you see duplicate class errors (e.g., `xpp3`), ensure you use the exclusions defined in the SDK's `build.gradle.kts` files.
- **Hilt Errors**: Ensure your project is correctly configured for Hilt (plugin in root `build.gradle.kts` and app `build.gradle.kts`).

---
Ethora Team - Beta Version
