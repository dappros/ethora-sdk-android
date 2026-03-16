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

## Installation

Add the chat modules to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":chat-core"))
    implementation(project(":chat-ui"))
}
```

Or if using Maven:

```xml
<dependency>
    <groupId>com.ethora</groupId>
    <artifactId>chat-core</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>com.ethora</groupId>
    <artifactId>chat-ui</artifactId>
    <version>1.0.0</version>
</dependency>
```

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
