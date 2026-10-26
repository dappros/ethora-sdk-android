package com.ethora.chat.core.push

import com.ethora.chat.core.config.ChatConfig
import com.ethora.chat.core.config.PushNotificationsConfig
import com.ethora.chat.core.config.XMPPSettings
import com.ethora.chat.core.models.User
import com.ethora.chat.core.networking.GatewaySubscriptionPayload
import com.ethora.chat.core.networking.PushEndpointPayload
import com.ethora.chat.core.networking.PushSubscriptionPayload
import com.ethora.chat.core.store.ChatStore
import com.ethora.chat.core.store.UserStore
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushRegistrationTest {

    private class FakeTransport(
        private val subscribeResponses: MutableList<PushHttpResult> = mutableListOf(),
        private val unsubscribeCode: Int = 200,
        private val endpointCode: Int = 200
    ) : PushTransport {
        val subscribeCalls = mutableListOf<Pair<String, PushSubscriptionPayload>>()
        val unsubscribeCalls = mutableListOf<String>()
        val endpointCalls = mutableListOf<String>()
        val gatewayCalls = mutableListOf<Triple<String, String, GatewaySubscriptionPayload>>()

        override suspend fun subscribe(baseUrl: String, appId: String, payload: PushSubscriptionPayload, authorization: String): PushHttpResult {
            subscribeCalls += appId to payload
            return subscribeResponses.removeAt(0)
        }

        override suspend fun unsubscribe(baseUrl: String, appId: String, registrationToken: String, authorization: String): PushHttpResult {
            unsubscribeCalls += registrationToken
            return PushHttpResult(unsubscribeCode, null)
        }

        override suspend fun deleteEndpoint(baseUrl: String, endpoint: String, authorization: String): PushHttpResult {
            endpointCalls += endpoint
            return PushHttpResult(endpointCode, null)
        }

        override suspend fun gatewaySubscribe(gatewayUrl: String, authorization: String, payload: GatewaySubscriptionPayload): PushHttpResult {
            gatewayCalls += Triple(gatewayUrl, authorization, payload)
            return PushHttpResult(201, null)
        }
    }

    private val gson = Gson()
    private val enabled = PushNotificationsConfig(enabled = true)

    @Before
    fun setUp() {
        UserStore.setUser(User(id = "u1", token = "session-token", refreshToken = "r", appId = "app1", xmppUsername = "alice@xmpp.example.com"))
        ChatStore.setConfig(
            ChatConfig(
                appId = "app1",
                baseUrl = "https://api.example.com/v1",
                xmppSettings = XMPPSettings(
                    xmppServerUrl = "wss://xmpp.example.com/ws",
                    host = "xmpp.example.com",
                    conference = "conference.xmpp.example.com"
                )
            )
        )
    }

    @After
    fun tearDown() {
        PushRegistration.transport = RetrofitPushTransport
        ChatStore.setConfig(null)
        UserStore.clear()
    }

    @Test
    fun `token type is detected from the registration token`() {
        assertEquals(PushRegistration.TOKEN_TYPE_EXPO, PushRegistration.detectTokenType("ExponentPushToken[abc]"))
        assertEquals(PushRegistration.TOKEN_TYPE_APNS, PushRegistration.detectTokenType("a".repeat(64)))
        assertEquals(PushRegistration.TOKEN_TYPE_FCM, PushRegistration.detectTokenType("fcm-token:xyz"))
    }

    @Test
    fun `422 not allowed retries once without tokenType`() = runTest {
        val fake = FakeTransport(
            subscribeResponses = mutableListOf(
                PushHttpResult(422, """{"message":"tokenType not allowed"}"""),
                PushHttpResult(200, null)
            )
        )
        PushRegistration.transport = fake

        val ok = PushRegistration.register("fcm-1", "fcm", enabled, appId = "app1", baseUrl = "https://api.example.com/v1/")

        assertTrue(ok)
        assertEquals(2, fake.subscribeCalls.size)
        assertEquals("fcm", fake.subscribeCalls[0].second.tokenType)
        assertEquals(null, fake.subscribeCalls[1].second.tokenType)
        val wire = gson.toJson(fake.subscribeCalls[1].second)
        assertFalse(wire.contains("tokenType"))
        assertTrue(wire.contains("\"registrationToken\":\"fcm-1\""))
    }

    @Test
    fun `direct subscribe sends tokenType and the user's app id`() = runTest {
        val fake = FakeTransport(subscribeResponses = mutableListOf(PushHttpResult(200, null)))
        PushRegistration.transport = fake

        assertTrue(PushRegistration.register("fcm-2", "fcm", enabled, appId = "config-app", baseUrl = "https://api.example.com/v1/"))

        assertEquals("app1", fake.subscribeCalls.single().first)
        assertEquals("fcm", fake.subscribeCalls.single().second.tokenType)
        assertEquals("android", fake.subscribeCalls.single().second.deviceType)
    }

    @Test
    fun `expo tokens are refused client side`() = runTest {
        val fake = FakeTransport()
        PushRegistration.transport = fake

        assertFalse(PushRegistration.register("ExponentPushToken[x]", "expo", enabled))
        assertTrue(fake.subscribeCalls.isEmpty())
    }

    @Test
    fun `unregister falls back to users endpoints on 404`() = runTest {
        val fake = FakeTransport(unsubscribeCode = 404)
        PushRegistration.transport = fake

        assertTrue(PushRegistration.unregister("fcm-3", "session-token", appId = "app1", baseUrl = "https://api.example.com/v1/"))

        assertEquals(listOf("fcm-3"), fake.unsubscribeCalls)
        assertEquals(listOf("fcm-3"), fake.endpointCalls)
        assertEquals("""{"endpoint":"fcm-3"}""", gson.toJson(PushEndpointPayload("fcm-3")))
    }

    @Test
    fun `unregister does not fall back on other errors`() = runTest {
        val fake = FakeTransport(unsubscribeCode = 500)
        PushRegistration.transport = fake

        assertFalse(PushRegistration.unregister("fcm-4", "session-token", appId = "app1", baseUrl = "https://api.example.com/v1/"))
        assertTrue(fake.endpointCalls.isEmpty())
    }

    @Test
    fun `gateway mode posts the subscription to the gateway with a bearer token`() = runTest {
        val fake = FakeTransport()
        PushRegistration.transport = fake
        val gateway = PushNotificationsConfig(enabled = true, apiUrl = "https://push.example.com/api/v1/", projectName = "demo")

        assertTrue(PushRegistration.register("fcm-5", "fcm", gateway))

        val call = fake.gatewayCalls.single()
        assertEquals("https://push.example.com/api/v1/subscriptions", call.first)
        assertEquals("Bearer session-token", call.second)
        assertEquals("alice@xmpp.example.com@xmpp.example.com", call.third.jid)
        assertEquals("demo", call.third.projectName)
        assertTrue(fake.subscribeCalls.isEmpty())
    }

    @Test
    fun `gateway mode without a project name is refused`() = runTest {
        val fake = FakeTransport()
        PushRegistration.transport = fake

        assertFalse(PushRegistration.register("fcm-6", "fcm", PushNotificationsConfig(enabled = true, apiUrl = "https://push.example.com/api/v1")))
        assertTrue(fake.gatewayCalls.isEmpty())
    }

    @Test
    fun `room jid is read from the first known payload key`() {
        assertEquals("room@conference.example.com", PushNotificationManager.roomJidFromPayload(mapOf("chatJid" to "", "roomJid" to "room@conference.example.com")))
        assertEquals("a@c", PushNotificationManager.roomJidFromPayload(mapOf("jid" to "a@c", "roomJid" to "b@c")))
        assertEquals(null, PushNotificationManager.roomJidFromPayload(mapOf("type" to "message")))
    }

    @Test
    fun `subscribe payload omits tokenType when null`() {
        assertEquals("""{"registrationToken":"t","deviceType":"android"}""", gson.toJson(PushSubscriptionPayload("t")))
        assertEquals("""{"registrationToken":"t","deviceType":"android","tokenType":"fcm"}""", gson.toJson(PushSubscriptionPayload("t", tokenType = "fcm")))
        assertEquals(
            """{"projectName":"p","registrationToken":"t","deviceType":"android","jid":"j"}""",
            gson.toJson(GatewaySubscriptionPayload("p", "t", "android", "j"))
        )
    }
}
