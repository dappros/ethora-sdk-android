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
}
