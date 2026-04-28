package com.ethora.chat.core.config

/**
 * Controls automatic retry behaviour for failed text and media sends.
 *
 * **Default is off (`autoRetry = false`)** — once a message fails, it stays
 * visible in the failed state until the user explicitly taps **Retry** or
 * **Delete**. This is the safer default for production apps where silent
 * background retries can mask connectivity issues from the user.
 *
 * To restore the legacy auto-retry behaviour, opt in:
 * ```kotlin
 * ChatConfig(
 *     retryConfig = RetryConfig(autoRetry = true, maxAttempts = 3)
 * )
 * ```
 *
 * **Mid-session toggling**: when [autoRetry] is set to `false` while a retry
 * is already in flight, the in-flight attempt is allowed to complete; no
 * further retries are scheduled afterwards. Already-queued failed messages
 * stay visible in the failed state for the user to act on.
 */
data class RetryConfig(
    /**
     * When `false` (default) the SDK does not schedule any background retry
     * for failed text or media sends. Each failure goes directly to the
     * permanently-failed state and the user must tap Retry or Delete.
     */
    val autoRetry: Boolean = false,

    /**
     * Maximum silent retry attempts before a message is locked into the
     * permanently-failed state. Only consulted when [autoRetry] is `true`.
     * Defaults to 3 to match the previous hard-coded value.
     */
    val maxAttempts: Int = 3
)
