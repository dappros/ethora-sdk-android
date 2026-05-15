package com.ethora.chat.core

/**
 * Main service entry point for external apps
 * Provides access to logout and other services
 * Matches web: export { logoutService } from './hooks/useLogout'
 */
object ChatService {
    /**
     * Logout service - allows external apps to logout from chat component
     * Matches web: logoutService from useLogout.tsx
     */
    val logout = com.ethora.chat.core.service.LogoutService

    /**
     * Lifecycle service - host app calls onChatPaused/onChatResumed when its
     * own UI navigates away from / back to the chat surface. Use this when
     * the SDK's auto-detection (Compose visibility + Android lifecycle) can't
     * see the transition — e.g. tab swap inside one Activity, or a host-side
     * overlay in the same window.
     */
    val lifecycle = com.ethora.chat.core.service.ChatLifecycleService
}
