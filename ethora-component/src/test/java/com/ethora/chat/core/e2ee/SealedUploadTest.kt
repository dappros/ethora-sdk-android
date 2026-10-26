package com.ethora.chat.core.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SealedUploadTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("sealed-upload").toFile()

    @Test
    fun `a sealed file hides its name and type and opens with its key`() {
        val dir = tempDir()
        val source = File(dir, "holiday.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) }

        val sealed = SealedUpload.seal(source, "image/png", "holiday.png", File(dir, "out"))

        assertTrue(sealed.file.exists())
        assertFalse("random extension-free name", sealed.file.name.contains("."))
        assertFalse(sealed.file.name.contains("holiday"))
        assertFalse("ciphertext must not contain the plain bytes or the filename", String(sealed.file.readBytes(), Charsets.ISO_8859_1).contains("holiday"))

        val opened = OmemoFileEnvelope.open(sealed.file.readBytes(), sealed.keyMaterial)
        assertEquals("image/png", opened.meta.mimetype)
        assertEquals("holiday.png", opened.meta.originalname)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), opened.bytes)
    }

    @Test
    fun `every file gets its own key and name`() {
        val dir = tempDir()
        val source = File(dir, "a.txt").apply { writeText("same bytes") }
        val first = SealedUpload.seal(source, "text/plain", "a.txt", File(dir, "out"))
        val second = SealedUpload.seal(source, "text/plain", "a.txt", File(dir, "out"))
        assertFalse(first.keyMaterial == second.keyMaterial)
        assertFalse(first.file.name == second.file.name)
    }

    @Test
    fun `the sealed media body carries the keys and the caption`() {
        val body = OmemoFileEnvelope.sealedMediaBody(listOf("k1", "k2"), "hello \"there\"")
        val parsed = OmemoFileEnvelope.parseSealedMedia(body)!!
        assertEquals(listOf("k1", "k2"), parsed.keys)
        assertEquals("hello \"there\"", parsed.caption)
    }
}
