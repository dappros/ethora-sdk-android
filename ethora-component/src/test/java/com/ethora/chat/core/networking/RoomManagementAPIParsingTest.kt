package com.ethora.chat.core.networking

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response shapes of the room-management endpoints, web parity. */
class RoomManagementAPIParsingTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun publicChatsDropReportedAndAdvanceOffsetByAllRows() {
        val raw = PublicChatsResponse(
            items = listOf(
                RawPublicChat(name = "a", title = " A ", description = " d ", picture = "p", e2ee = true),
                RawPublicChat(name = "b", reported = true),
                RawPublicChat(name = "", title = "no name")
            ),
            total = 10, offset = 0, limit = 50
        )
        val page = toPublicChatsPage(raw, offset = 0, limit = 50)
        assertEquals(listOf("a"), page.items.map { it.name })
        assertEquals("A", page.items[0].title)
        assertTrue(page.items[0].e2ee)
        assertEquals(3, page.nextOffset)
        assertEquals(10, page.total)
    }

    @Test
    fun userPayloadUnwrapsResultAndDropsEmailAndTags() {
        val user = parseUserPayload(
            json("{\"result\":{\"_id\":\"u1\",\"xmppUsername\":\"app_u1\",\"firstName\":\"Ann\",\"email\":\"x@y\",\"tags\":[\"t\"]}}")
        )
        assertEquals("u1", user!!.id)
        assertEquals("app_u1", user.xmppUsername)
        assertEquals("Ann", user.firstName)
        assertNull(user.lastName)
    }

    @Test
    fun userPayloadAcceptsOneElementArray() {
        val user = parseUserPayload(json("{\"data\":[{\"xmppUsername\":\"app_u2\",\"lastName\":\"Lee\"}]}"))
        assertEquals("app_u2", user!!.xmppUsername)
        assertEquals("Lee", user.lastName)
    }

    @Test
    fun userPayloadWithoutDisplayFieldsIsNull() {
        assertNull(parseUserPayload(json("{\"email\":\"x@y\"}")))
        assertNull(parseUserPayload(null))
    }

    @Test
    fun memberListKeepsOnlyValidEntries() {
        val members = parseMemberList(json("[{\"xmppUsername\":\"app_a\"},{\"email\":\"no\"}]"))
        assertEquals(listOf("app_a"), members.map { it.xmppUsername })
    }

    @Test
    fun muteEnvelopeYieldsServerMutedState() {
        assertEquals(true, muteFromEnvelope(MuteEnvelope(MuteResult("c", true))))
        assertNull(muteFromEnvelope(MuteEnvelope(null)))
    }

    @Test
    fun v2UrlReplacesV1Root() {
        assertEquals(
            "https://api.example.com/v2/chats/users",
            RoomManagementAPIHelper.v2UsersUrl("https://api.example.com/v1")
        )
        assertFalse(RoomManagementAPIHelper.v2UsersUrl("https://api.example.com/v1/").contains("/v1/"))
    }
}
