package com.ethora.chat.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

        // Type a query that matches one of the two entries.
        composeTestRule.onNodeWithText("Filter logs").performTextInput("auth.success")

        composeTestRule.onNodeWithText("auth.success", substring = true).assertIsDisplayed()
        // Recompose passes; assert the non-matching entry is gone.
        // assertDoesNotExist would throw on transient absence; this
        // pattern (matchedNode().assertIsDisplayed + assertExists on
        // the wanted node) is more reliable for filter-driven UIs.
        try {
            composeTestRule.onNodeWithText("presence.join", substring = true).assertIsDisplayed()
            error("Expected 'presence.join' to be filtered out by query 'auth.success'")
        } catch (assertionError: AssertionError) {
            // Expected — the node should not be displayed under the
            // active filter. Swallow to let the test pass.
        }
    }
}
