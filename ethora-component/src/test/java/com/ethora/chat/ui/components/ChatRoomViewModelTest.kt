package com.ethora.chat.ui.components

import com.ethora.chat.core.models.Message
import com.ethora.chat.core.models.Room
import com.ethora.chat.core.models.RoomType
import com.ethora.chat.core.models.User
import com.ethora.chat.core.store.MessageStore
import com.ethora.chat.core.store.RoomStore
import com.ethora.chat.core.xmpp.XMPPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        RoomStore.clear()
        MessageStore.clear()
    }

    @After
    fun tearDown() {
        MessageStore.clear()
        RoomStore.clear()
    }

    @Test
    fun `loadMoreMessages keeps hasMore true when empty page arrives before historyComplete`() =
        runTest(mainDispatcherRule.dispatcher) {
        val room = makeRoom(historyComplete = false)
        RoomStore.setRooms(listOf(room))
        MessageStore.setMessagesForRoom(
            room.jid,
            listOf(
                makeMessage(room.jid, "100", 100L),
                makeMessage(room.jid, "200", 200L)
            )
        )

        val client = mock<XMPPClient>()
        whenever(client.isFullyConnected()).thenReturn(true)
        whenever(client.ensureConnected(5_000)).thenReturn(true)
        whenever(client.getHistory(room.jid, 50, null)).thenReturn(emptyList())
        whenever(client.getHistory(room.jid, 30, "100")).thenReturn(emptyList())

        val viewModel = ChatRoomViewModel(room, client)
        advanceUntilIdle()

        viewModel.loadMoreMessages()
        advanceUntilIdle()

        assertTrue(
            "Empty history without Room.historyComplete=true should be treated as a transient failure, not end-of-history",
            viewModel.hasMoreMessages.value
        )
    }

    private fun makeRoom(historyComplete: Boolean?) = Room(
        id = "room-1",
        jid = "room-1@conference.xmpp.example.com",
        name = "room-1",
        title = "Room 1",
        type = RoomType.GROUP,
        historyComplete = historyComplete
    )

    private fun makeMessage(roomJid: String, id: String, timestamp: Long) = Message(
        id = id,
        user = User(id = "user-1", name = "User 1"),
        date = java.util.Date(timestamp),
        body = "message-$id",
        roomJid = roomJid,
        timestamp = timestamp
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher: TestDispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
