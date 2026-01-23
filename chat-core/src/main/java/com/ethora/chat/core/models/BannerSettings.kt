package com.ethora.chat.core.models

import java.util.Calendar

/**
 * Settings for off-clinic hours banner
 */
data class BannerSettings(
    val isEnabled: Boolean = false,
    val startHour: Int = 21, // 0-23
    val endHour: Int = 7,    // 0-23
    val bannerText: String = "off-clinic hours"
) {
    /**
     * Check if current time is within the off-clinic hours range
     */
    fun isCurrentlyActive(): Boolean {
        if (!isEnabled) return false

        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)

        // Handle case where endHour < startHour (e.g., 21:00 to 7:00)
        return if (endHour < startHour) {
            // Active if currentHour >= startHour OR currentHour < endHour
            currentHour >= startHour || currentHour < endHour
        } else {
            // Active if currentHour >= startHour AND currentHour < endHour
            currentHour >= startHour && currentHour < endHour
        }
    }
}
