package com.ethora.chat.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
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

    // User.fullName falls through firstName+lastName → name → username → id.
    // Setting `name` directly avoids relying on the firstName+lastName path
    // (which requires both) so the bubble's author label resolves to a
    // predictable string in tests.
    private fun textMessage(
        body: String,
        isDeleted: Boolean? = null,
    ) = Message(
        id = "msg-${body.hashCode()}",
        user = User(id = "u-1", name = "Alice"),
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
        // For deleted messages the bubble swaps the body for the
        // canonical tombstone copy from MessageBubble.kt:212 — the
        // original body must NOT leak into the rendered tree.
        composeTestRule.onNodeWithText("This message was deleted.", substring = true)
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("you should not see this", substring = true)
            .assertCountEquals(0)
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
