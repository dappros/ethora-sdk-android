package com.ethora.chat.core.xmpp

import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.Date

/**
 * Pure-JVM unit tests for [TimestampUtils].
 *
 * This util is the load-bearing piece keeping Android's message-history
 * ordering bit-for-bit aligned with web. Server-assigned archive IDs
 * arrive as numeric strings in any of four time scales (s / ms / µs / ns),
 * sometimes embedded inside a longer ID string. Each branch below
 * documents the bound it crosses.
 *
 * First test under `chat-core/src/test/` — runs with
 *   `./gradlew :chat-core:test`
 * (the existing aggregate tests live in `ethora-component/src/test/`).
 */
class TimestampUtilsTest {

    // --- normalizeTimestampValue ---------------------------------------

    @Test
    fun `seconds value is scaled up to milliseconds`() {
        // 1_704_067_200 (= 2024-01-01 in seconds) < 1e11 → treated as
        // seconds and multiplied by 1000.
        val out = TimestampUtils.normalizeTimestampValue(1_704_067_200.0)
        assertEquals(1_704_067_200_000L, out)
    }

    @Test
    fun `millisecond-range value passes through unchanged`() {
        // 1_704_067_200_000 falls in [1e11, 1e14] → already ms.
        val out = TimestampUtils.normalizeTimestampValue(1_704_067_200_000.0)
        assertEquals(1_704_067_200_000L, out)
    }

    @Test
    fun `microsecond-range value is scaled down to milliseconds`() {
        // 1_704_067_200_000_000 falls in (1e14, 1e17] → µs → /1000.
        val out = TimestampUtils.normalizeTimestampValue(1_704_067_200_000_000.0)
        assertEquals(1_704_067_200_000L, out)
    }

    @Test
    fun `nanosecond-range value is scaled down to milliseconds`() {
        // 1.7e18 > 1e17 → ns → /1_000_000. Use a value that survives
        // double-precision rounding cleanly.
        val out = TimestampUtils.normalizeTimestampValue(1_700_000_000_000_000_000.0)
        assertEquals(1_700_000_000_000L, out)
    }

    @Test
    fun `zero and negative values are clamped to zero`() {
        assertEquals(0L, TimestampUtils.normalizeTimestampValue(0.0))
        assertEquals(0L, TimestampUtils.normalizeTimestampValue(-1.0))
        assertEquals(0L, TimestampUtils.normalizeTimestampValue(-1e15))
    }

    @Test
    fun `non-finite values are clamped to zero`() {
        assertEquals(0L, TimestampUtils.normalizeTimestampValue(Double.NaN))
        assertEquals(0L, TimestampUtils.normalizeTimestampValue(Double.POSITIVE_INFINITY))
        assertEquals(0L, TimestampUtils.normalizeTimestampValue(Double.NEGATIVE_INFINITY))
    }

    // --- getTimestampFromUnknown ---------------------------------------

    @Test
    fun `null input returns zero`() {
        assertEquals(0L, TimestampUtils.getTimestampFromUnknown(null))
    }

    @Test
    fun `Date input returns its millisecond time`() {
        val date = Date(1_704_067_200_000L)
        assertEquals(1_704_067_200_000L, TimestampUtils.getTimestampFromUnknown(date))
    }

    @Test
    fun `Number input is normalised through the same s-ms-µs-ns ladder`() {
        // Long is a Number — should hit the Number branch and normalise.
        assertEquals(
            1_704_067_200_000L,
            TimestampUtils.getTimestampFromUnknown(1_704_067_200L)
        )
        // Int seconds → ms.
        assertEquals(
            1_704_067_200_000L,
            TimestampUtils.getTimestampFromUnknown(1_704_067_200)
        )
    }

    @Test
    fun `numeric String input is parsed and normalised`() {
        assertEquals(
            1_704_067_200_000L,
            TimestampUtils.getTimestampFromUnknown("1704067200000")
        )
        // Numeric seconds string also normalises up.
        assertEquals(
            1_704_067_200_000L,
            TimestampUtils.getTimestampFromUnknown("1704067200")
        )
    }

    @Test
    fun `ISO-8601 String is parsed`() {
        // 2024-01-01T00:00:00Z = 1_704_067_200_000 ms epoch.
        assertEquals(
            1_704_067_200_000L,
            TimestampUtils.getTimestampFromUnknown("2024-01-01T00:00:00Z")
        )
    }

    @Test
    fun `legacy XEP-0091 compact String is parsed`() {
        // 20241003T15:30:10 in UTC → 2024-10-03T15:30:10Z.
        // Compute the expected ms once with the same SDF the SUT uses so
        // we don't bake the timezone math twice in this test.
        val expected = java.text.SimpleDateFormat("yyyyMMdd'T'HH:mm:ss").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse("20241003T15:30:10")!!.time
        assertEquals(
            expected,
            TimestampUtils.getTimestampFromUnknown("20241003T15:30:10")
        )
    }

    @Test
    fun `String with embedded long-digit chunk extracts it`() {
        // Pattern used in IDs like "send-text-message-1704067200000".
        assertEquals(
            1_704_067_200_000L,
            TimestampUtils.getTimestampFromUnknown("send-text-message-1704067200000")
        )
    }

    @Test
    fun `empty or unparseable Strings return zero`() {
        assertEquals(0L, TimestampUtils.getTimestampFromUnknown(""))
        assertEquals(0L, TimestampUtils.getTimestampFromUnknown("   "))
        // No long-digit run, not parseable as date.
        assertEquals(0L, TimestampUtils.getTimestampFromUnknown("not-a-timestamp"))
    }

    @Test
    fun `unsupported types return zero`() {
        // Booleans, lists, custom objects all fall through the null/Date/
        // Number/String chain and hit the `return 0L` at the end.
        assertEquals(0L, TimestampUtils.getTimestampFromUnknown(true))
        assertEquals(0L, TimestampUtils.getTimestampFromUnknown(listOf(1, 2, 3)))
    }
}
