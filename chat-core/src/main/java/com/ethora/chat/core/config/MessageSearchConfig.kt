package com.ethora.chat.core.config

/**
 * The one rule for message search (web helpers/isMessageSearchEnabled.ts).
 * Opt-in: the host sets `enableMessageSearch` AND an `appId` (the archive
 * endpoint is scoped by app). A leftover `disableMessageSearch = true` still
 * wins as a hard off.
 */
fun isMessageSearchEnabled(config: ChatConfig?): Boolean =
    config?.enableMessageSearch == true &&
        config.disableMessageSearch != true &&
        !config.appId.isNullOrBlank()
