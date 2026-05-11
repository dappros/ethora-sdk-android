package com.ethora.chat.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * Compose UI tests for the message long-press action sheet.
 *
 * `MessageContextMenu` is the surface that backs Maestro flows 13, 14,
 * and 15 (edit, delete, react). Anchored on the textual labels Copy /
 * Edit / Delete / Retry because Material3 doesn't expose stable test
 * tags on plain Text composables. If the SDK adds testTags to
 * `ContextMenuItem`, switch these to `onNodeWithTag` for parity with
 * Maestro selectors.
 */
@RunWith(AndroidJUnit4::class)
class MessageContextMenuTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun textMessage(
        body: String = "hello",
        pending: Boolean? = null,
        sendFailed: Boolean? = null,
        isDeleted: Boolean? = null,
    ) = Message(
        id = "msg-${body.hashCode()}",
        user = User(id = "u-1", firstName = "Alice"),
        date = Date(),
        body = body,
        roomJid = "room-1@conference.xmpp.example.com",
        pending = pending,
        sendFailed = sendFailed,
        isDeleted = isDeleted,
    )

    @Test
    fun rendersNothingWhenInvisible() {
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(),
                isUser = true,
                visible = false,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
            )
        }
        // None of the four labels should be in composition when visible=false.
        composeTestRule.onNodeWithText("Copy").assertDoesNotExist()
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun rendersNothingForDeletedMessage() {
        // Even with visible=true, a deleted message short-circuits the
        // menu — guards against confusing UX where users try to react
        // to or edit a tombstone.
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(isDeleted = true),
                isUser = true,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Copy").assertDoesNotExist()
    }

    @Test
    fun ownMessageShowsCopyEditDelete() {
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(),
                isUser = true,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun receivedMessageShowsCopyOnly() {
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(),
                isUser = false,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun pendingOwnMessageWithResendHandlerOffersRetry() {
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(pending = true),
                isUser = true,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
                onResend = {},
            )
        }
        // pending + isUser + onResend != null → Retry is offered.
        // Edit is hidden because the message hasn't reached the server,
        // so editing what doesn't exist yet would be nonsensical.
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun sendFailedOwnMessageOffersRetry() {
        // Parallel to the pending case — the auto-retry timer can fire
        // sendFailed=true without pending ever flipping, so both paths
        // need to surface Retry.
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(sendFailed = true),
                isUser = true,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
                onResend = {},
            )
        }
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun pendingMessageWithoutResendHandlerOffersEditNotRetry() {
        // Without an onResend lambda the menu falls through canResend
        // and shows Edit instead — even though pending=true. Guards
        // against the host forgetting to pass onResend and the menu
        // then having no actionable items for a stuck message.
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(pending = true),
                isUser = true,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
                onResend = null,
            )
        }
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
    }

    @Test
    fun tappingCopyFiresOnCopyAndOnDismiss() {
        var copyFired = false
        var dismissed = false
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(),
                isUser = false,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = { dismissed = true },
                onCopy = { copyFired = true },
                onEdit = {}, onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Copy").performClick()
        assert(copyFired) { "Expected onCopy to fire" }
        assert(dismissed) { "Expected onDismiss to fire after tap (auto-close)" }
    }

    @Test
    fun ownMessageRendersExactlyThreeMenuItems() {
        // Regression guard: previous refactors have occasionally
        // duplicated the Delete entry or added a stray separator. Use
        // assertCountEquals to lock in the visible-item count for the
        // canonical own-message case.
        composeTestRule.setContent {
            MessageContextMenu(
                message = textMessage(),
                isUser = true,
                visible = true,
                tapX = 0f, tapY = 0f,
                boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 100f,
                onDismiss = {}, onCopy = {}, onEdit = {}, onDelete = {},
            )
        }
        // Three labels: Copy, Edit, Delete. Each is a unique string,
        // so the union covers exactly three distinct nodes.
        composeTestRule.onAllNodesWithText("Copy").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Edit").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Delete").assertCountEquals(1)
    }
}
