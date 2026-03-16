# Ethora Chat SDK for Android

A fully-featured chat component for Android that mirrors the functionality of the web version, built with Jetpack Compose and Kotlin.

---

## Installation & Running the App

### Prerequisites

- **Android Studio** (Arctic Fox or newer, recommended: latest stable)
- **JDK 17** or higher
- **Android SDK** (API 34 or higher recommended)
- An **Android device** or **emulator** (API 24+)

### Step 1: Clone the Repository

```bash
git clone https://github.com/dappros/ethora-sdk-android.git
cd ethora-sdk-android
```

### Step 2: Open in Android Studio

1. Launch **Android Studio**
2. Select **File → Open**
3. Choose the `ethora-sdk-android` folder
4. Wait for Gradle sync to complete (first run may take a few minutes)

### Step 3: Configure Environment (Optional)

The app uses a `.env` file for configuration, similar to React projects. Values are injected at build time.

1. Copy the example file (if `.env` does not exist):
   ```bash
   cp chat-app/.env.example chat-app/.env
   ```
2. Edit `chat-app/.env`. Structure aligns with React (`VITE_ETHORA_*`) and preshent-mobile (`PRESHENT_*`):

| Variable | Maps to (React) | Description | Default |
|----------|-----------------|-------------|---------|
| `API_BASE_URL` | `VITE_ETHORA_CHAT_BASE_URL` | Backend API base URL | `https://api.ethoradev.com/v1` |
| `APP_ID` | `VITE_ETHORA_APP_ID` | Ethora app ID | `646cc8dc96d4a4dc8f7b2f2d` |
| `API_TOKEN` | `VITE_ETHORA_API_TOKEN` | App JWT token (Authorization header) | — |
| `APP_XMPP_SERVICE` | `VITE_ETHORA_APP_XMPP_SERVICE` | XMPP WebSocket URL | `wss://xmpp.ethoradev.com:5443/ws` |
| `XMPP_SERVICE` | `VITE_ETHORA_XMPP_SERVICE` | Conference domain | `conference.xmpp.ethoradev.com` |
| `XMPP_HOST` | `VITE_ETHORA_XMPP_HOST` | XMPP server host | `xmpp.ethoradev.com` |
| `DEFAULT_LOGIN_EMAIL` | — | Test login email | `yukiraze9@gmail.com` |
| `DEFAULT_LOGIN_PASSWORD` | — | Test login password | `Qwerty123` |

Legacy names (`XMPP_DEV_SERVER`, `XMPP_CONFERENCE`) are supported. If `.env` is missing, defaults are used. **Do not commit `API_TOKEN`** — add `.env` to `.gitignore` if it contains secrets.

### Step 4: Configure Firebase (Optional, for Push Notifications)

If you want push notifications:

1. Add your `google-services.json` to `chat-app/` (replace the existing one)
2. Ensure the `package_name` in the JSON matches `com.ethora.chat.app` (or your custom `applicationId` in `chat-app/build.gradle.kts`)
3. Register your app's SHA-1 fingerprint in [Firebase Console](https://console.firebase.google.com/) → Project Settings → Your App → Add fingerprint

Get your debug SHA-1:
```bash
./gradlew :chat-app:signingReport
```

### Step 5: Build the Project

```bash
# Mac/Linux
./gradlew :chat-app:assembleDebug

# Windows
gradlew.bat :chat-app:assembleDebug
```

Or in Android Studio: **Build → Make Project** (Ctrl+F9 / Cmd+F9)

### Step 6: Run the App

**Option A — From Android Studio**

1. Select the **chat-app** run configuration
2. Choose a device or emulator
3. Click **Run** (green play button) or press **Shift+F10**

**Option B — From command line**

```bash
# Install and launch on connected device/emulator
# Mac/Linux:
./gradlew :chat-app:installDebug
adb shell am start -n com.ethora.chat.app/.MainActivity

# Windows: use gradlew.bat instead of ./gradlew
```

### Default Login

The sample app uses credentials from `.env` (or defaults):
- **Email:** `yukiraze9@gmail.com`
- **Password:** `Qwerty123`

You can change these in `MainActivity.kt` or use your own Ethora account.

### Common Issues

| Issue | Solution |
|-------|----------|
| **Gradle sync failed** | Ensure you have a stable internet connection. Try **File → Invalidate Caches → Restart**. |
| **No devices found** | Start an emulator from **Tools → Device Manager**, or connect a physical device with USB debugging enabled. |
| **Build fails with SDK error** | Open **File → Project Structure** and verify Android SDK path and API level. |
| **App crashes on launch** | Check Logcat for errors. Ensure `google-services.json` is valid if using push notifications. |

### Custom API & XMPP Server

To use your own backend, edit `chat-app/.env`:

```
API_BASE_URL=https://api.yourdomain.com/v1
XMPP_DEV_SERVER=wss://xmpp.yourdomain.com:5443/ws
XMPP_HOST=xmpp.yourdomain.com
XMPP_CONFERENCE=conference.xmpp.yourdomain.com
```

Then rebuild the app. Values are injected at build time, just like React's `.env`.

---

## Features

- ✅ Real-time messaging via XMPP WebSocket
- ✅ Message history with pagination
- ✅ Media support (images, videos, PDFs)
- ✅ User avatars and profiles
- ✅ Typing indicators
- ✅ Message reactions, editing, and deletion
- ✅ Unread message counters
- ✅ Persistent message storage
- ✅ Customizable UI and styling
- ✅ JWT and Google login support
- ✅ Configurable XMPP settings

## Packaging the chat component for use in any Kotlin project

The chat is split into two libraries: **chat-core** (API, XMPP, persistence) and **chat-ui** (Compose UI and the `Chat` composable). You publish them to a Maven repository; clients add two dependencies and one Composable screen. **All project-specific data is passed through config** — no `.env` or hardcoded backends. **Updates:** client only changes the version number and syncs.

---

## Client integration guide (instruction for your clients)

Give this section to anyone who embeds your chat in their app.

### Step 1: Add repository and dependencies

In the **root** of the client’s project, open `settings.gradle.kts` (or `settings.gradle`). In `dependencyResolutionManagement { repositories { ... } }` add the repository where you publish the chat (you provide the URL, e.g. GitHub Packages or your Maven server):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.pkg.github.com/YOUR_ORG/ethora-sdk-android") }
        // Or your Maven repo: maven { url = uri("https://your-server.com/repo") }
    }
}
```

In the **app module** `build.gradle.kts` (the module where the chat screen will live), add:

```kotlin
dependencies {
    implementation("com.ethora:chat-core:1.0.0")
    implementation("com.ethora:chat-ui:1.0.0")
}
```

Replace `1.0.0` with the version you give them. Sync the project.

### Step 2: Where to place the chat in the project

The chat is a **single Composable**: you build a `ChatConfig`, call `ChatStore.setConfig` and `ApiClient.setBaseUrl`, then call `Chat(config = config)`. **Where** that Composable appears is up to the client: full screen, one tab, a navigation route, or a bottom sheet.

**Option A — Full screen (one Activity = chat):**

```kotlin
// In the Activity that should show only chat
setContent {
    ChatScreen(config = myChatConfig)
}
```

**Option B — One tab in BottomNavigation / TabRow:**

```kotlin
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    when (selectedTab) {
        0 -> HomeScreen()
        1 -> ChatScreen(config = myChatConfig)
        2 -> SettingsScreen()
    }
    BottomNavigationBar(selectedTab, onTabSelected = { selectedTab = it })
}
```

**Option C — One route in Jetpack Navigation:**

```kotlin
NavHost(navController, startDestination = "home") {
    composable("home") { HomeScreen() }
    composable("chat") {
        ChatScreen(config = myChatConfig)
    }
}
```

So: the client **creates one Composable** (e.g. `ChatScreen`) that receives config and shows the chat. They use that Composable in the place where the chat should appear (Activity, tab, or route). No need to copy any of your source code.

### Step 3: Build config and show the chat

The client implements `ChatScreen` (or similar) like this. All data (URLs, tokens, XMPP, optional DNS fallback) comes from **their** backend or config — they pass it into `ChatConfig`:

```kotlin
@Composable
fun ChatScreen(config: ChatConfig) {
    ChatStore.setConfig(config)
    ApiClient.setBaseUrl(config.baseUrl ?: AppConfig.defaultBaseURL, config.customAppToken)
    Chat(config = config, modifier = Modifier.fillMaxSize())
}
```

They build `ChatConfig` wherever they get their settings (ViewModel, repository, or static). Example:

```kotlin
val config = ChatConfig(
    baseUrl = "https://api.yourproject.com/v1",
    appId = "your-app-id",
    customAppToken = "your-app-token",
    xmppSettings = XMPPSettings(
        devServer = "wss://xmpp.yourproject.com/ws",
        host = "xmpp.yourproject.com",
        conference = "conference.xmpp.yourproject.com"
    ),
    jwtLogin = JWTLoginConfig(token = userJwtToken, enabled = true),
    defaultLogin = true,
    dnsFallbackOverrides = mapOf(/* host -> IP if needed */),
    disableHeader = true,
    newArch = true
)
```

Required imports: `com.ethora.chat.Chat`, `com.ethora.chat.core.config.*`, `com.ethora.chat.core.store.ChatStore`, `com.ethora.chat.core.networking.ApiClient`, `androidx.compose.ui.Modifier`.

### Step 4: How to get updates

When you release a new version (e.g. `1.0.1`), the client **only** changes the version in the same two lines and syncs:

```kotlin
implementation("com.ethora:chat-core:1.0.1")
implementation("com.ethora:chat-ui:1.0.1")
```

No code changes in their app if the public API of the chat (e.g. `ChatConfig`, `Chat`) is compatible. They pull the new AARs from your repository on the next build.

---

## For you (SDK author): publishing to a repository and updates

### Publish to Maven Local (for quick tests)

From the root of this repository:

```bash
./gradlew :chat-core:publishToMavenLocal :chat-ui:publishToMavenLocal
```

Clients would then add `mavenLocal()` in their `repositories` and use the same `implementation(...)` lines. Use this for local checks only.

### Publish to a Maven repository (for distribution to clients)

To give clients a stable URL and let them update by changing the version:

1. **Choose a repository:** GitHub Packages, your own Maven server (e.g. Nexus, Artifactory), or another host. You need a URL like `https://maven.pkg.github.com/OWNER/REPO` or `https://your-company.com/maven`.
2. **Configure publishing** in `chat-core/build.gradle.kts` and `chat-ui/build.gradle.kts`: add the repository to the `publishing` block and, if needed, credentials. Example for a generic Maven repo:

```kotlin
afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "MyMaven"
                url = uri("https://your-server.com/maven-repo")
                credentials {
                    username = project.findProperty("mavenUser") as String? ?: ""
                    password = project.findProperty("mavenPassword") as String? ?: ""
                }
            }
        }
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.ethora"
                artifactId = "chat-core"
                version = libs.versions.versionName.get()
            }
        }
    }
}
```

3. **Publish:** run `./gradlew :chat-core:publish :chat-ui:publish` (with `-PmavenUser=... -PmavenPassword=...` if required).
4. **Give clients:** the repository URL and the dependency coordinates `com.ethora:chat-core:X.Y.Z` and `com.ethora:chat-ui:X.Y.Z`. They add the repo once; for updates they only change `X.Y.Z`.

Version number is taken from `gradle/libs.versions.toml` (`versionName`). Bump it there before each release so clients can request the new version.

---

## Quick reference: client setup (copy-paste checklist)

| Step | Where | What to do |
|------|--------|------------|
| 1 | `settings.gradle.kts` | Add `maven { url = uri("YOUR_REPO_URL") }` in `repositories`. |
| 2 | `app/build.gradle.kts` | Add `implementation("com.ethora:chat-core:VERSION")` and `implementation("com.ethora:chat-ui:VERSION")`. |
| 3 | Any screen (Activity / tab / route) | Create a Composable that calls `ChatStore.setConfig(config)`, `ApiClient.setBaseUrl(...)`, then `Chat(config = config, modifier = Modifier.fillMaxSize())`. |
| 4 | Same Composable or ViewModel | Build `ChatConfig` with `baseUrl`, `xmppSettings`, `appId`, `customAppToken`, optional `jwtLogin`, optional `dnsFallbackOverrides`. |
| Updates | `app/build.gradle.kts` | Change `VERSION` in the two `implementation` lines and sync. |

---

## Installation (when developing the SDK itself)

Add the chat modules as **project** dependencies (same repo):

```kotlin
dependencies {
    implementation(project(":chat-core"))
    implementation(project(":chat-ui"))
}
```

Or use the published artifacts as in the section above (Maven Local or your own Maven repository).

## Quick Start

### Basic Usage

```kotlin
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatColors
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.config.JWTLoginConfig

@Composable
fun MyChatScreen() {
    val config = ChatConfig(
        disableHeader = true,
        colors = ChatColors(
            primary = "#5E3FDE",
            secondary = "#E1E4FE"
        ),
        disableRooms = true,
        jwtLogin = JWTLoginConfig(
            token = "your-jwt-token",
            enabled = true
        ),
        defaultLogin = true,
        disableInteractions = true,
        chatRoomStyles = mapOf(
            "borderRadius" to "16dp"
        ),
        newArch = true,
        customAppToken = "your-api-token",
        disableMedia = true,
        baseUrl = "https://api.ethoradev.com",
        disableProfilesInteractions = true,
        xmppSettings = XMPPSettings(
            devServer = "wss://xmpp.ethoradev.com:5443/ws",
            host = "xmpp.ethoradev.com",
            conference = "conference.xmpp.ethoradev.com",
            xmppPingOnSendEnabled = true
        ),
        botMessageAutoScroll = true,
        enableRoomsRetry = EnableRoomsRetryConfig(
            enabled = true,
            helperText = "Initializing room..."
        )
    )

    Chat(
        config = config,
        roomJID = "${appId}_${workspaceId}@conference.xmpp.ethoradev.com",
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
    )
}
```

## Complete Configuration Example

This example matches the web version's configuration exactly:

```kotlin
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethora.chat.Chat
import com.ethora.chat.core.config.*

@Composable
fun ChatScreen(
    chatUser: String, // JWT token
    appId: String,
    workspaceId: String
) {
    val config = ChatConfig.builder()
        // Basic UI Settings
        .disableHeader(true)
        .disableMedia(true)
        .colors(
            ChatColors(
                primary = "#5E3FDE",
                secondary = "#E1E4FE"
            )
        )
        
        // Login Configuration
        .jwtLogin(
            JWTLoginConfig(
                token = chatUser,
                enabled = true
            )
        )
        .defaultLogin(true)
        
        // Google Login (optional)
        .googleLogin(
            GoogleLoginConfig(
                enabled = true,
                firebaseConfig = FirebaseConfig(
                    apiKey = "AIzaSyDQdkvvxKKx4-WrjLQoYf08GFARgi_qO4g",
                    authDomain = "ethora-668e9.firebaseapp.com",
                    projectId = "ethora-668e9",
                    storageBucket = "ethora-668e9.appspot.com",
                    messagingSenderId = "972933470054",
                    appId = "1:972933470054:web:d4682e76ef02fd9b9cdaa7"
                )
            )
        )
        
        // Room Settings
        .disableRooms(true)
        .disableInteractions(true)
        .newArch(true)
        
        // Styling
        .chatRoomStyles(
            mapOf(
                "borderRadius" to "16dp"
            )
        )
        
        // Secondary Send Button
        .secondarySendButton(
            SecondarySendButtonConfig(
                enabled = true,
                messageEdit = "Your custom message text", // Custom message text for secondary button
                label = "Send", // Optional button label
                buttonStyles = mapOf(
                    "whiteSpace" to "nowrap",
                    "width" to "60dp"
                ),
                hideInputSendButton = true,
                overwriteEnterClick = true
            )
        )
        
        // API & XMPP Settings
        .baseUrl("https://api.ethoradev.com") // Your API base URL
        .customAppToken("your-api-token")
        .xmppSettings(
            XMPPSettings(
                devServer = "wss://xmpp.ethoradev.com:5443/ws",
                host = "xmpp.ethoradev.com",
                conference = "conference.xmpp.ethoradev.com",
                xmppPingOnSendEnabled = true
            )
        )
        
        // Features
        .disableProfilesInteractions(true)
        .botMessageAutoScroll(true)
        .enableRoomsRetry(
            EnableRoomsRetryConfig(
                enabled = true,
                helperText = "Initializing room..."
            )
        )
        
        // Message Text Filter
        .messageTextFilter(
            MessageTextFilterConfig(
                enabled = true,
                filterFunction = { text ->
                    // Remove metadata tags (matches web version)
                    text.replace(Regex("<metadata>[\\S\\s]*?</metadata>"), "")
                        .trim()
                }
            )
        )
        
        // Chat Header Additional (custom component)
        .chatHeaderAdditional(
            ChatHeaderAdditionalConfig(
                enabled = true,
                element = { 
                    // Your custom composable for header additional content
                    // This is a @Composable lambda
                }
            )
        )
        
        .build()

    Chat(
        config = config,
        roomJID = "${appId}_${workspaceId}@conference.xmpp.ethoradev.com",
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
    )
}
```

## Configuration Options Reference

### Basic Settings

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `disableHeader` | `disableHeader` | `Boolean?` | Hide the chat header |
| `disableMedia` | `disableMedia` | `Boolean?` | Disable media message support |
| `colors.primary` | `colors.primary` | `String` | Primary theme color (hex) |
| `colors.secondary` | `colors.secondary` | `String` | Secondary theme color (hex) |

### Login Configuration

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `jwtLogin.token` | `jwtLogin.token` | `String` | JWT token for authentication |
| `jwtLogin.enabled` | `jwtLogin.enabled` | `Boolean` | Enable JWT login |
| `googleLogin.enabled` | `googleLogin.enabled` | `Boolean` | Enable Google login |
| `googleLogin.firebaseConfig` | `googleLogin.firebaseConfig` | `FirebaseConfig` | Firebase configuration object |
| `defaultLogin` | `defaultLogin` | `Boolean?` | Use default login flow |

### Room Settings

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `disableRooms` | `disableRooms` | `Boolean?` | Hide room list |
| `disableInteractions` | `disableInteractions` | `Boolean?` | Disable user interactions |
| `newArch` | `newArch` | `Boolean?` | Use new architecture |

### XMPP Settings

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `xmppSettings.devServer` | `xmppSettings.devServer` | `String` | XMPP WebSocket URL (wss://...) |
| `xmppSettings.host` | `xmppSettings.host` | `String` | XMPP server host |
| `xmppSettings.conference` | `xmppSettings.conference` | `String` | Conference domain |
| `xmppSettings.xmppPingOnSendEnabled` | `xmppSettings.xmppPingOnSendEnabled` | `Boolean` | Enable ping on send |

### API Settings

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `baseUrl` | `baseUrl` | `String?` | API base URL |
| `customAppToken` | `customAppToken` | `String?` | API authentication token |

### Network / DNS fallback

| Android Config | Type | Description |
|----------------|------|-------------|
| `dnsFallbackOverrides` | `Map<String, String>?` | When system DNS fails (e.g. on emulator), use these hostname→IP pairs. See [DNS fallback](#dns-fallback-emulator--restricted-networks) below. |

### Styling

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `chatRoomStyles` | `chatRoomStyles` | `Map<String, Any>?` | Custom chat room styles |

### Features

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `disableProfilesInteractions` | `disableProfilesInteractions` | `Boolean?` | Disable profile interactions |
| `botMessageAutoScroll` | `botMessageAutoScroll` | `Boolean?` | Auto-scroll on bot messages |
| `enableRoomsRetry.enabled` | `enableRoomsRetry.enabled` | `Boolean` | Enable room retry |
| `enableRoomsRetry.helperText` | `enableRoomsRetry.helperText` | `String` | Retry helper text |
| `messageTextFilter.enabled` | `messageTextFilter.enabled` | `Boolean` | Enable message text filtering |
| `messageTextFilter.filterFunction` | `messageTextFilter.filterFunction` | `(String) -> String` | Custom filter function |

### Secondary Send Button

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `secondarySendButton.enabled` | `secondarySendButton.enabled` | `Boolean` | Enable secondary send button |
| `secondarySendButton.messageEdit` | `secondarySendButton.messageEdit` | `String` | Custom message text for secondary button |
| `secondarySendButton.label` | `secondarySendButton.label` | `String?` | Optional button label |
| `secondarySendButton.hideInputSendButton` | `secondarySendButton.hideInputSendButton` | `Boolean` | Hide default send button |
| `secondarySendButton.overwriteEnterClick` | `secondarySendButton.overwriteEnterClick` | `Boolean` | Override Enter key behavior |
| `secondarySendButton.buttonStyles` | `secondarySendButton.buttonStyles` | `Map<String, Any>?` | Custom button styles |

### Chat Header Additional

| Web Config | Android Config | Type | Description |
|------------|----------------|------|-------------|
| `chatHeaderAdditional.enabled` | `chatHeaderAdditional.enabled` | `Boolean` | Enable custom header element |
| `chatHeaderAdditional.element` | `chatHeaderAdditional.element` | `@Composable () -> Unit` | Custom composable for header |

### DNS fallback (emulator / restricted networks)

On emulators or in restricted networks, system DNS may fail to resolve your API/XMPP hostnames (`Unable to resolve host "api.example.com"`). You can pass a **DNS fallback map** through **config** so that when resolution fails, the SDK uses the IP addresses you provide. The same config works for any backend (Ethora, Preshent, Vital, or your own).

#### 1. How to get the IP address for a hostname

Run one of these on your **development machine** (Mac, Windows, or Linux). Use the same network as the device/emulator if possible.

**macOS / Linux (Terminal):**

```bash
# Option 1: nslookup (usually preinstalled)
nslookup api.yourdomain.com
# In the output, use the "Address" line under "Non-authoritative answer"

# Option 2: dig (macOS: built-in; Linux: often preinstalled)
dig +short api.yourdomain.com

# Option 3: host
host api.yourdomain.com
```

**Windows (Command Prompt or PowerShell):**

```cmd
nslookup api.yourdomain.com
```

In the output, take the **IPv4 address** (e.g. `34.174.203.35`) for each hostname you use: API host, XMPP host, and conference host (e.g. `api.yourdomain.com`, `xmpp.yourdomain.com`, `conference.xmpp.yourdomain.com`).

#### 2. Pass DNS fallback through config (not .env)

Configuration is done via **`ChatConfig`**. There is no dependency on `.env` in your app: you can build the map from remote config, feature flags, or hardcoded values.

**Type:** `dnsFallbackOverrides: Map<String, String>?`  
**Meaning:** for each hostname that might fail to resolve, map it to the IP address to use as fallback.

**Example:**

```kotlin
val config = ChatConfig(
    baseUrl = "https://api.yourproject.com/v1",
    xmppSettings = XMPPSettings(
        devServer = "wss://xmpp.yourproject.com/ws",
        host = "xmpp.yourproject.com",
        conference = "conference.xmpp.yourproject.com"
    ),
    dnsFallbackOverrides = mapOf(
        "api.yourproject.com" to "34.174.203.35",
        "xmpp.yourproject.com" to "34.174.203.35",
        "conference.xmpp.yourproject.com" to "34.174.203.35"
    ),
    // ... other options
)

ChatStore.setConfig(config)
ApiClient.setBaseUrl(config.baseUrl ?: AppConfig.defaultBaseURL, config.customAppToken)
```

**Rules:**

- **Keys** = exact hostnames used in your `baseUrl` and `xmppSettings` (no `https://`, no path, no port).
- **Values** = IPv4 (or IPv6) strings.
- **Optional:** pass `null` or an empty map if you don't need DNS fallback (e.g. on a real device with working DNS).
- Works for **any project** (Ethora, Preshent, Vital, custom): same config field, different hostnames and IPs.

**Minimal example (one host for both API and XMPP):**

```kotlin
dnsFallbackOverrides = mapOf(
    "api.example.com" to "1.2.3.4",
    "xmpp.example.com" to "1.2.3.4",
    "conference.xmpp.example.com" to "1.2.3.4"
)
```

The SDK uses system DNS first; only if resolution throws (e.g. `UnknownHostException`) does it use `dnsFallbackOverrides` for that hostname. Ethora hosts (`api.ethoradev.com`, `xmpp.ethoradev.com`) have a built-in fallback in the SDK; for all other hosts you supply the map via config.

## Advanced Usage

### Custom Components

```kotlin
val config = ChatConfig(
    customComponents = CustomComponents(
        customInputComponent = { props ->
            // Your custom input component
            // props: SendInputProps
        },
        customMessageComponent = { message, isUser, isReply ->
            // Your custom message component
        }
    )
)
```

### Event Handlers

```kotlin
val config = ChatConfig(
    eventHandlers = ChatEventHandlers(
        onMessageSent = { message, roomJID ->
            // Handle message sent
        },
        onMessageReceived = { message, roomJID ->
            // Handle message received
        },
        onRoomChanged = { room ->
            // Handle room change
        }
    )
)
```

### Message Notifications

```kotlin
val config = ChatConfig(
    messageNotifications = MessageNotificationConfig(
        enabled = true,
        onNotification = { message, room ->
            // Show notification
        }
    )
)
```

## Initialization

Before using the Chat component, you need to initialize the persistence and stores:

```kotlin
import com.ethora.chat.core.persistence.ChatPersistenceManager
import com.ethora.chat.core.persistence.ChatDatabase
import com.ethora.chat.core.persistence.MessageCache
import com.ethora.chat.core.store.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize persistence
        val persistenceManager = ChatPersistenceManager(this)
        val chatDatabase = ChatDatabase.getDatabase(this)
        val messageCache = MessageCache(chatDatabase)
        
        // Initialize stores
        RoomStore.initialize(persistenceManager)
        UserStore.initialize(persistenceManager)
        MessageStore.initialize(messageCache)
        ScrollPositionStore.initialize(this)
        
        setContent {
            // Your chat UI
        }
    }
}
```

## Room JID Format

The room JID follows this format:
```
{appId}_{workspaceId}@conference.xmpp.ethoradev.com
```

Example:
```kotlin
Chat(
    config = config,
    roomJID = "myapp_workspace123@conference.xmpp.ethoradev.com"
)
```

## Environment Variables (.env)

The **chat-app** module supports a `.env` file (like React), loaded at build time.

Create or edit `chat-app/.env` (same structure as React `VITE_ETHORA_*` / preshent-mobile `PRESHENT_*`):

```
API_BASE_URL=https://api.ethoradev.com/v1
APP_ID=646cc8dc96d4a4dc8f7b2f2d
API_TOKEN=JWT <your-app-token>
APP_XMPP_SERVICE=wss://xmpp.ethoradev.com:5443/ws
XMPP_SERVICE=conference.xmpp.ethoradev.com
XMPP_HOST=xmpp.ethoradev.com
DEFAULT_LOGIN_EMAIL=your@email.com
DEFAULT_LOGIN_PASSWORD=yourpassword
```

Legacy names `XMPP_DEV_SERVER` and `XMPP_CONFERENCE` are supported. Values are injected into `BuildConfig`. If `.env` is missing, defaults are used. See `chat-app/.env.example` for the full list. **Do not commit `API_TOKEN`** — add `.env` to `.gitignore`.

**Integrating into your own app?** Add the same Gradle logic from `chat-app/build.gradle.kts` (the `loadEnv()` function and `buildConfigField` entries) to read from your app's `.env`.

## Permissions

Add required permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Troubleshooting

### Messages Not Sending

1. Check XMPP connection status
2. Ensure presence is sent to room before sending messages (handled automatically)
3. Verify JWT token is valid
4. Check network connectivity
5. Check logs for XMPP errors

### History Not Loading

1. Verify `newArch` is set correctly
2. Check XMPP server connection
3. Ensure room JID is correct
4. Check MAM (Message Archive Management) support
5. Verify `historyComplete` flag is being set

### UI Issues

1. Verify Compose theme is applied
2. Check color configuration (use hex strings like "#5E3FDE")
3. Ensure proper layout constraints
4. Check if `disableHeader` or other UI flags are set correctly

## Logout Service

The chat component provides a logout service that allows external apps to logout from the chat component. This is useful when you want to logout the user from your app and also logout them from the chat.

### Usage

```kotlin
import com.ethora.chat.core.service.LogoutService

// Set a callback to be notified when logout completes (optional)
LogoutService.setOnLogoutCallback {
    // Handle logout completion
    // e.g., navigate to login screen, clear app state, etc.
}

// Perform logout
LogoutService.performLogout()
```

### What Logout Does

The logout service performs the following operations:

1. **Disconnects XMPP client** - Closes the WebSocket connection
2. **Clears all stores** - Clears UserStore, RoomStore, MessageStore, and ScrollPositionStore
3. **Clears persistence** - Removes all persisted data (user, rooms, messages, scroll positions)
4. **Calls callback** - Invokes the logout callback if set

### Example

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set logout callback
        LogoutService.setOnLogoutCallback {
            // Navigate to login screen or clear app state
            finish()
        }
        
        setContent {
            // Your app UI
        }
    }
    
    private fun handleLogout() {
        // Call logout service
        LogoutService.performLogout()
        // The callback will be called when logout completes
    }
}
```

### Check Login Status

```kotlin
// Check if user is logged in
val isLoggedIn = LogoutService.isLoggedIn()

// Get current user (if logged in)
val currentUser = LogoutService.getCurrentUser()
```

## Support

For issues and questions, please refer to the main project documentation or contact support.

## License

See LICENSE file for details.
