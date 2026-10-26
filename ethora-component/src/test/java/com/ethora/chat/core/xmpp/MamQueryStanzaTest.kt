package com.ethora.chat.core.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round trips for the MAM query IQ and the <fin> it is answered with. */
class MamQueryStanzaTest {

    private val room = "room1@conference.xmpp.host"
    private val queryId = "$MAM_WINDOW_QUERY_PREFIX 42"

    private fun rsmBefore(xml: String): String? =
        Regex("<before>(\\d+)</before>").find(xml)?.groupValues?.get(1)

    private fun rsmAfter(xml: String): String? =
        Regex("<after>(\\d+)</after>").find(xml)?.groupValues?.get(1)

    @Test
    fun before_query_round_trips_its_cursor_and_max() {
        val xml = buildMamQueryStanza(room, queryId, max = 10, cursor = WindowCursor(before = 1704067200000123L))
        assertEquals("1704067200000123", rsmBefore(xml))
        assertNull(rsmAfter(xml))
        assertEquals("10", Regex("<max>(\\d+)</max>").find(xml)?.groupValues?.get(1))
        assertTrue(xml.contains("<query xmlns=\"urn:xmpp:mam:2\" queryid=\"mam-window: 42\">"))
        assertTrue(xml.startsWith("<iq type=\"set\" to=\"room1@conference.xmpp.host\""))
        assertFalse(xml.contains("<x "))
    }

    @Test
    fun after_query_uses_rsm_after() {
        val xml = buildMamQueryStanza(room, queryId, max = 11, cursor = WindowCursor(after = 1704067200000122L))
        assertEquals("1704067200000122", rsmAfter(xml))
        assertNull(rsmBefore(xml))
    }

    @Test
    fun latest_query_sends_an_empty_before() {
        val xml = buildMamQueryStanza(room, queryId, max = 30, cursor = WindowCursor())
        assertTrue(xml.contains("<before/>"))
    }

    @Test
    fun time_range_query_carries_the_data_form_and_no_before() {
        val xml = buildMamQueryStanza(
            room, queryId, max = 50,
            cursor = WindowCursor(start = "2026-07-14T10:00:00Z", end = "2026-07-14T10:00:10Z")
        )
        assertTrue(xml.contains("<x xmlns=\"jabber:x:data\" type=\"submit\">"))
        assertTrue(xml.contains("<field var=\"start\"><value>2026-07-14T10:00:00Z</value></field>"))
        assertTrue(xml.contains("<field var=\"end\"><value>2026-07-14T10:00:10Z</value></field>"))
        assertNull(rsmBefore(xml))
        assertFalse(xml.contains("<before/>"))
    }

    @Test
    fun fin_of_a_result_page_round_trips_complete_first_and_last() {
        val fin = """<iq type='result' id='$queryId' from='$room'><fin xmlns='urn:xmpp:mam:2' complete='true'><set xmlns='http://jabber.org/protocol/rsm'><first index='0'>1704067200000100</first><last>1704067200000109</last></set></fin></iq>"""
        val parsed = parseMamFin(fin)
        assertEquals(HistoryWindowFin(complete = true, first = 1704067200000100L, last = 1704067200000109L), parsed)
    }

    @Test
    fun fin_without_complete_and_without_rows_has_no_cursor() {
        val fin = "<iq type='result'><fin xmlns='urn:xmpp:mam:2'><set xmlns='http://jabber.org/protocol/rsm'/></fin></iq>"
        val parsed = parseMamFin(fin)
        assertEquals(HistoryWindowFin(complete = false, first = null, last = null), parsed)
    }

    @Test
    fun a_stanza_without_fin_is_not_a_page_end() {
        assertNull(parseMamFin("<message><result queryid='x'/></message>"))
        assertNull(parseMamFin("<iq type='result'><finally/></iq>"))
    }

    @Test
    fun query_values_are_xml_escaped() {
        val xml = buildMamQueryStanza("r&x@conf", "q\"1", 5, WindowCursor())
        assertTrue(xml.contains("to=\"r&amp;x@conf\""))
        assertTrue(xml.contains("id=\"q&quot;1\""))
    }
}
