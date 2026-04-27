package com.ethora.chat.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeFormatterTest {
    @Test
    fun `formats file sizes using binary Android units`() {
        assertEquals("999 B", FileSizeFormatter.format(999))
        assertEquals("1.00 KB", FileSizeFormatter.format(1024))
        assertEquals("1.50 KB", FileSizeFormatter.format(1536))
        assertEquals("1.00 MB", FileSizeFormatter.format(1024 * 1024))
    }

    @Test
    fun `formats string byte values and reports unknown invalid values`() {
        assertEquals("2.00 KB", FileSizeFormatter.format("2048"))
        assertEquals("Unknown size", FileSizeFormatter.format("bad-size"))
    }
}
