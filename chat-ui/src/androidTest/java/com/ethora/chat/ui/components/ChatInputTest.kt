package com.ethora.chat.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke test for `ChatInput`.
 *
 * Demonstrates the canonical Compose-test pattern this SDK uses:
 *   1. Render the composable in isolation with `composeTestRule.setContent`.
 *   2. Drive it with the same callbacks the host app would pass.
 *   3. Use semantic finders (`onNodeWithText` / `onNodeWithTag`) — never
 *      raw text matching against rendered glyphs, since localization and
 *      Material defaults change.
 *
 * Run on an emulator (API 26+) or device:
 *   ./gradlew :chat-ui:connectedDebugAndroidTest
 *
 * These tests live alongside the SDK source so they run on every PR
 * without needing a host app. End-to-end flows that need a real
 * server, FCM, or login go in `ethora-sample-android/.maestro/` instead
 * — see "Testing" in the README.
 */
@RunWith(AndroidJUnit4::class)
class ChatInputTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersInputAndFiresCallbackOnSend() {
        var sentText: String? = null
        composeTestRule.setContent {
            ChatInput(
                onSendMessage = { text, _ -> sentText = text },
                canSendMessage = true,
            )
        }

        // The placeholder text in the input field is the most stable
        // semantic anchor — IDs aren't set on Material3 OutlinedTextField
        // children by default. If/when we add testTags in source, swap
        // these to onNodeWithTag for better resilience.
        composeTestRule.onNodeWithText("Type a message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type a message").performTextInput("hello")
        composeTestRule.onNodeWithText("Send", ignoreCase = true).performClick()

        // Assert the host's onSendMessage callback received the typed text.
        // If the assertion fires before recomposition completes, wrap with
        // composeTestRule.waitForIdle() — Compose-test usually handles this
        // automatically, but file-attach paths trigger async work that
        // does need an explicit wait.
        assert(sentText == "hello") { "Expected onSendMessage to receive 'hello' but got '$sentText'" }
    }
}
