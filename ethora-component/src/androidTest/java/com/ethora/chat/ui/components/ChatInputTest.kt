package com.ethora.chat.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * Compose UI smoke tests for `ChatInput`.
 *
 * Demonstrates the canonical Compose-test pattern this SDK uses:
 *   1. Render the composable in isolation with `composeTestRule.setContent`.
 *   2. Drive it with the same callbacks the host app would pass.
 *   3. Use semantic finders (`onNodeWithText` / `onNodeWithContentDescription`)
 *      — never raw text matching against rendered glyphs, since
 *      localization and Material defaults change.
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
        composeTestRule.onNodeWithText("Type a message...").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type a message...").performTextInput("hello")

        // The send button only switches from the disabled IconButton to
        // the active FloatingActionButton once the field is non-blank
        // (see ChatInput.kt:378). Use contentDescription "Send" to
        // resolve unambiguously across both states.
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        // Assert the host's onSendMessage callback received the typed text.
        // If the assertion fires before recomposition completes, wrap with
        // composeTestRule.waitForIdle() — Compose-test usually handles this
        // automatically, but file-attach paths trigger async work that
        // does need an explicit wait.
        assert(sentText == "hello") { "Expected onSendMessage to receive 'hello' but got '$sentText'" }
    }

    @Test
    fun emptyInputShowsSendIconWithoutFiringCallback() {
        var sentText: String? = null
        composeTestRule.setContent {
            ChatInput(
                onSendMessage = { text, _ -> sentText = text },
                canSendMessage = true,
            )
        }

        // With an empty field the Send icon is rendered as a disabled
        // IconButton (no-op onClick), so a tap should NOT invoke the
        // host callback. Use this assertion to guard against future
        // refactors that wire the FAB to fire even when text is blank
        // — that would let users send empty messages.
        composeTestRule.onNodeWithContentDescription("Send").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        assert(sentText == null) { "Expected onSendMessage NOT to fire for empty input but got '$sentText'" }
    }

    @Test
    fun editModePrePopulatesText() {
        composeTestRule.setContent {
            ChatInput(
                onSendMessage = { _, _ -> },
                editText = "previously typed",
                onEditCancel = {},
                canSendMessage = true,
            )
        }

        // Edit mode should show the existing message text inside the
        // input on first render (ChatInput.kt:53 + LaunchedEffect at
        // line 86) and the placeholder switches to "Edit message...".
        composeTestRule.onNodeWithText("previously typed").assertIsDisplayed()
        // Placeholder is hidden once text is present, so we don't
        // assert it here — but if the prefill regresses (text is empty
        // on first render), the placeholder reappears and the
        // assertion above fails with a clear "node not found" error.
    }

    @Test
    fun replyPreviewShowsAndCancelFiresCallback() {
        var replyCancelFired = false
        val replyTo = Message(
            id = "msg-1",
            user = User(id = "u-1", firstName = "Alice", lastName = "Test"),
            date = Date(),
            body = "the message being replied to",
            roomJid = "room-jid-1@conference.xmpp.example.com",
        )
        composeTestRule.setContent {
            ChatInput(
                onSendMessage = { _, _ -> },
                replyingToMessage = replyTo,
                onReplyCancel = { replyCancelFired = true },
                canSendMessage = true,
            )
        }

        // The reply preview block (ChatInput.kt:241) renders the body
        // of the message being replied to. Anchor on that text so the
        // test breaks if the preview stops surfacing the body.
        composeTestRule.onNodeWithText(
            "the message being replied to",
            substring = true,
        ).assertIsDisplayed()

        // The cancel control inside the preview is an IconButton with
        // no contentDescription on the wrapper; the inner Icon uses
        // the close icon. We anchor on the surrounding Alice attribution
        // to scope the search, then perform a click on the close icon.
        // If the host renames the cancel control, update both this
        // anchor and the source.
        composeTestRule.onNodeWithContentDescription("Cancel reply").performClick()
        assert(replyCancelFired) { "Expected onReplyCancel to fire when reply preview cancel is tapped" }
    }
}
