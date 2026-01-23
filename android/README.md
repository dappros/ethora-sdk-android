# Ethora Chat Component for Android

A native Android chat component library that fully mirrors the web version's functionality and configuration. Built with Jetpack Compose, Kotlin Coroutines, and following Android best practices.

## Features

- Full XMPP chat functionality
- Real-time messaging
- Room management
- Message history and caching
- Typing indicators
- Media support (images, videos, files)
- Customizable UI with Material Design 3
- All configuration options from web version

## Installation

Add the library to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":chat-core"))
    implementation(project(":chat-ui"))
}
```

Or if published to Maven:

```kotlin
dependencies {
    implementation("com.ethora:chat-core:1.0.0")
    implementation("com.ethora:chat-ui:1.0.0")
}
```

## Quick Start

```kotlin
import com.ethora.chat.Chat
import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.ChatColors
import com.ethora.chat.core.config.XMPPSettings

@Composable
fun MyChatScreen() {
    val config = ChatConfig(
        colors = ChatColors(
            primary = "#4287f5",
            secondary = "#42f5e9"
        ),
        xmppSettings = XMPPSettings(
            devServer = "wss://xmpp.ethoradev.com:5443/ws",
            host = "xmpp.ethoradev.com",
            conference = "conference.xmpp.ethoradev.com"
        ),
        baseUrl = "https://api.ethoradev.com/v1"
    )
    
    Chat(config = config)
}
```

## Configuration

The `ChatConfig` class supports all configuration options from the web version:

- **UI Settings**: `disableHeader`, `disableMedia`, `colors`
- **Login**: `googleLogin`, `jwtLogin`, `userLogin`, `customLogin`
- **XMPP**: `xmppSettings`, `baseUrl`, `customAppToken`
- **Rooms**: `disableRooms`, `defaultRooms`, `customRooms`
- **Styling**: `roomListStyles`, `chatRoomStyles`, `backgroundChat`, `bubleMessage`
- **Features**: `disableTypingIndicator`, `messageNotifications`, `eventHandlers`
- **Custom Components**: `customComponents`

See the full configuration in `ChatConfig.kt` for all available options.

## Architecture

The library is structured in two modules:

- **chat-core**: Core business logic (XMPP, networking, state management, persistence)
- **chat-ui**: UI components built with Jetpack Compose

## Requirements

- Android SDK 24+
- Kotlin 1.9.20+
- Jetpack Compose

## License

AGPL
