package com.ethora.chat.core.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFileUrlTest {

    @Test
    fun `secure-files hosts are detected`() {
        assertTrue(SecureFileUrl.isSecureFileUrl("https://secure-files.ethora.com/a/b.png"))
        assertFalse(SecureFileUrl.isSecureFileUrl("https://api.chat.ethora.com/v1/x"))
        assertFalse(SecureFileUrl.isSecureFileUrl("file:///data/a.png"))
        assertFalse(SecureFileUrl.isSecureFileUrl(null))
        assertFalse(SecureFileUrl.isSecureFileUrl(""))
    }

    @Test
    fun `token is appended to secure urls`() {
        val out = SecureFileUrl.appendFileToken("https://secure-files.ethora.com/a/b.png", "tok123")
        assertEquals("https://secure-files.ethora.com/a/b.png?ft=tok123", out)
    }

    @Test
    fun `existing ft is replaced, not duplicated`() {
        val out = SecureFileUrl.appendFileToken("https://secure-files.ethora.com/a.png?ft=old&x=1", "new")
        assertTrue(out.contains("ft=new"))
        assertFalse(out.contains("ft=old"))
        assertEquals(1, Regex("ft=").findAll(out).count())
        assertTrue(out.contains("x=1"))
    }

    @Test
    fun `non secure urls are returned unchanged`() {
        val plain = "https://cdn.example.com/a.png"
        assertEquals(plain, SecureFileUrl.appendFileToken(plain, "tok"))
        assertEquals("file:///data/a.png", SecureFileUrl.appendFileToken("file:///data/a.png", "tok"))
    }

    @Test
    fun `missing token leaves secure url unchanged`() {
        val url = "https://secure-files.ethora.com/a.png"
        assertEquals(url, SecureFileUrl.appendFileToken(url, null))
        assertEquals(url, SecureFileUrl.appendFileToken(url, ""))
    }

    @Test
    fun `null url yields empty string`() {
        assertEquals("", SecureFileUrl.appendFileToken(null, "tok"))
    }
}
