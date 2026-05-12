package com.ethora.chat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
        composeTestRule.onNodeWithText("Sending failed", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(MessageBubbleTestTags.STATUS_FAILED).assertIsDisplayed()
    }

    @Test
    fun pendingOwnMessageRendersOnlyTheSendingMarker() {
        // Per-message contract: a row with `pending = true` and no failure
        // signal must show the in-bubble clock, not the green check or
        // the error icon.
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("in flight").copy(pending = true),
                isUser = true,
            )
        }
        composeTestRule.onNodeWithTag(MessageBubbleTestTags.STATUS_SENDING).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_SENT).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_FAILED).assertCountEquals(0)
    }

    @Test
    fun confirmedOwnMessageRendersOnlyTheSentMarker() {
        // The mirror of the previous test — a confirmed row must show only
        // the green check, never the clock or the error icon.
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("delivered"),
                isUser = true,
            )
        }
        composeTestRule.onNodeWithTag(MessageBubbleTestTags.STATUS_SENT).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_SENDING).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_FAILED).assertCountEquals(0)
    }

    @Test
    fun perMessageStatusIsIndependentAcrossBubbles() {
        // Spec requirement (Bug 2): "Every outgoing optimistic message
        // should show its own sending indicator directly in that message
        // bubble. The sending marker must belong only to that specific
        // message. Sending state must not be global for the whole chat or
        // shared across multiple message bubbles."
        //
        // Render three own bubbles side-by-side: one sending, one sent,
        // one failed. The composition uses three INDEPENDENT MessageBubble
        // calls — there is no shared "isSending" flag. We assert that
        // each status tag appears exactly once across the whole tree.
        composeTestRule.setContent {
            Column {
                MessageBubble(
                    message = textMessage("body sending").copy(pending = true),
                    isUser = true,
                )
                MessageBubble(
                    message = textMessage("body sent"),
                    isUser = true,
                )
                MessageBubble(
                    message = textMessage("body failed"),
                    isUser = true,
                    sendFailed = true,
                )
            }
        }
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_SENDING).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_SENT).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_FAILED).assertCountEquals(1)
        // The "Sending failed" copy belongs only to the failed bubble,
        // even though that bubble is rendered with the default
        // showTimestamp value as the OTHER bubbles. (Prior to the fix the
        // copy was gated on `showTimestamp` set by ChatRoomView to
        // `isLastInGroup`, so a failed bubble in the middle of a same-
        // sender run would have its failure text suppressed.)
        composeTestRule.onAllNodesWithText("Sending failed", substring = true).assertCountEquals(1)
    }

    @Test
    fun incomingMessageDoesNotShowAnyOutgoingStatusMarker() {
        // Status markers (sending / sent / failed) are own-user only —
        // they must NOT appear on incoming bubbles even if the message
        // looks identical otherwise.
        composeTestRule.setContent {
            MessageBubble(
                message = textMessage("hello from peer"),
                isUser = false,
            )
        }
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_SENDING).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_SENT).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(MessageBubbleTestTags.STATUS_FAILED).assertCountEquals(0)
    }
}
