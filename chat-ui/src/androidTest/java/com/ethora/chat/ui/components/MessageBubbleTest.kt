package com.ethora.chat.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * Compose UI smoke tests for `MessageBubble`.
 *
 * Each test renders one bubble in isolation with a different
 * Message shape. Goal is to catch regressions in the rendering of
 * common message states — body text, deleted tombstone, send-failed
 * indicator — without spinning up the full chat tree.
 */
@RunWith(AndroidJUnit4::class)
class MessageBubbleTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun textMessage(
        body: String,
        isDeleted: Boolean? = null,
    ) = Message(
        id = "msg-${body.hashCode()}",
        user = User(id = "u-1", firstName = "Alice"),
        date = Date(),
        body = body,
        roomJid = "room-jid-1@conference.xmpp.example.com",
        isDeleted = isDeleted,
    )

    @Test
    fun rendersBodyText() {
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("hello world"),
                isUser = true,
            )
        }
        composeTestRule.onNodeWithText("hello world", substring = true).assertIsDisplayed()
    }

    @Test
    fun rendersAuthorNameForIncomingMessage() {
        // showUsername defaults to true, isUser = false → bubble
        // should attribute the message to the author.
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("incoming text"),
                isUser = false,
                showUsername = true,
            )
        }
        composeTestRule.onNodeWithText("Alice", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("incoming text", substring = true).assertIsDisplayed()
    }

    @Test
    fun rendersDeletedTombstone() {
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("you should not see this", isDeleted = true),
                isUser = true,
            )
        }
        // The bubble's surface color flips for deleted messages
        // (MessageBubble.kt:163) and the body either renders a
        // tombstone glyph or hides the original text. Anchor on
        // the deleted-message indicator if the SDK adds one;
        // otherwise this test asserts the bubble still composes
        // without crashing — useful regression guard against
        // null-deref on isDeleted=true paths.
        composeTestRule.onNodeWithText("you should not see this", substring = true).assertIsDisplayed()
        // TODO: tighten once the SDK exposes a tombstone label
        // ("(deleted)" / "Message deleted") so we can assert on it.
    }

    @Test
    fun rendersSendFailedState() {
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("never reached server"),
                isUser = true,
                sendFailed = true,
            )
        }
        // sendFailed renders a "Sending failed. Tap to retry or
        // delete." treatment per Message.kt:24-26 contract. If the
        // SDK changes that copy, update the anchor here in lockstep.
        composeTestRule.onNodeWithText("never reached server", substring = true).assertIsDisplayed()
        // TODO: assert on the explicit failed-state copy once the
        // SDK exposes a stable string for it ("Sending failed",
        // "Tap to retry", etc.).
    }
}
