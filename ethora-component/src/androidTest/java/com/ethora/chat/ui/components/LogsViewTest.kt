package com.ethora.chat.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethora.chat.core.store.LogStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke tests for `LogsView`.
 *
 * `LogsView` reads from the global `LogStore` singleton, so we drive
 * it by calling `LogStore.info(...)` in the test setup rather than
 * passing a list as a prop. The `@Before` / `@After` hooks make sure
 * each test starts with an empty store and doesn't leak entries to
 * the next test.
 */
@RunWith(AndroidJUnit4::class)
class LogsViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun resetLogStore() {
        LogStore.clear()
    }

    @After
    fun tearDownLogStore() {
        LogStore.clear()
    }

    @Test
    fun rendersFilterFieldAndLogEntries() {
        LogStore.info(tag = "Auth", message = "auth.success", category = "auth")
        LogStore.info(tag = "XMPP", message = "presence.join", category = "xmpp")

        composeTestRule.setContent {
            LogsView()
        }

        // The filter input is the most stable structural anchor —
        // OutlinedTextField label "Filter logs" is set in
        // LogsView.kt:96.
        composeTestRule.onNodeWithText("Filter logs").assertIsDisplayed()

        // Both entries should render (substring match because the
        // row composes "tag — message" or similar).
        composeTestRule.onNodeWithText("auth.success", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("presence.join", substring = true).assertIsDisplayed()
    }

    @Test
    fun queryFilterHidesNonMatchingEntries() {
        LogStore.info(tag = "Auth", message = "auth.success", category = "auth")
        LogStore.info(tag = "XMPP", message = "presence.join", category = "xmpp")

        composeTestRule.setContent {
            LogsView()
        }

        // Type a query that matches one of the two entries. The filter
        // input itself is also a node whose text is the typed query, so
        // we can't use onNodeWithText("auth.success") (it matches both
        // the input field and the log row → "Expected at most 1 node").
        composeTestRule.onNodeWithText("Filter logs").performTextInput("auth.success")

        // After filtering, both the OutlinedTextField holding the typed
        // query AND the matching log row contain "auth.success" → 2 nodes.
        composeTestRule.onAllNodesWithText("auth.success", substring = true)
            .assertCountEquals(2)
        // The non-matching entry must be filtered out.
        composeTestRule.onAllNodesWithText("presence.join", substring = true)
            .assertCountEquals(0)
    }
}
