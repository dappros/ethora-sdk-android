package com.ethora.chat.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatXMPPClientOwnershipTest {
    @Test
    fun `shared bootstrap client is not disconnected on Chat dispose`() {
        val shared = Any()

        assertFalse(ChatXMPPClientOwnership.shouldDisconnectOnDispose(shared, shared))
    }

    @Test
    fun `non shared client is disconnected on Chat dispose`() {
        assertTrue(ChatXMPPClientOwnership.shouldDisconnectOnDispose(Any(), Any()))
    }

    @Test
    fun `missing client is not disconnected on Chat dispose`() {
        assertFalse(ChatXMPPClientOwnership.shouldDisconnectOnDispose(null, Any()))
    }
}
