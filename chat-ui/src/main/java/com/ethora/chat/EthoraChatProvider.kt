package com.ethora.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.ethora.chat.core.config.ChatConfig

/**
 * Wraps any part of the app with the SDK's background bootstrap — the Android
 * equivalent of web's `<XmppProvider config={...}>` (xmppProvider.tsx) combined
 * with its `initBeforeLoad` side-effect.
 *
 * Mount this ABOVE any screen that needs to read chat state (e.g. a home
 * screen rendering an unread dot with `useUnread()`), even before the user
 * enters the actual chat. When `config.initBeforeLoad == true`:
 *
 *   • POST /users/client with the configured JWT
 *   • populate UserStore + RoomStore (cache first, then /chats/my)
 *   • open the single shared XMPPClient and wait for it online
 *   • sync per-room `lastViewedTimestamp` from the server's chatjson
 *     private store (XEP-0049)
 *   • preload the latest 20 messages per room via
 *     `IncrementalHistoryLoader.updateMessagesTillLast`
 *
 * The bootstrap is idempotent and key-cached (see `EthoraChatBootstrap`), so
 * composing this provider multiple times or alongside the main `Chat`
 * composable never opens a second socket.
 *
 * Typical usage:
 * ```
 * setContent {
 *   EthoraChatProvider(config = myChatConfig) {
 *     MyAppNavHost()
 *   }
 * }
 * ```
 */
@Composable
fun EthoraChatProvider(
    config: ChatConfig,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(config.initBeforeLoad, config.jwtLogin?.token, config.xmppSettings) {
        if (config.initBeforeLoad == true) {
            EthoraChatBootstrap.initialize(context, config)
        }
    }
    content()
}
