package com.ethora.chat.core.config

/**
 * Reaction gates, same key names as the web and React Native SDKs.
 *
 *  - [enabled]: `false` turns reactions off (the chips and the menu row).
 *  - [quickReactions]: shortcodes on the quick bar, first 8 used. Null uses the default set.
 *  - [picker]: `false` hides the full emoji sheet, leaving only the quick bar.
 */
data class ReactionsConfig(
    val enabled: Boolean? = null,
    val quickReactions: List<String>? = null,
    val picker: Boolean? = null
)
