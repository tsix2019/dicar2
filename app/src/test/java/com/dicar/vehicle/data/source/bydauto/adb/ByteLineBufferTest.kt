package com.dicar.vehicle.data.source.bydauto.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteLineBufferTest {

    private fun ByteLineBuffer.feed(s: String) = append(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `assembles a line split across chunks`() {
        val b = ByteLineBuffer()
        b.feed("READ")
        assertNull(b.nextLine())
        b.feed("Y uid=2000\nI 4")
        assertEquals("READY uid=2000", b.nextLine())
        assertNull(b.nextLine())
        b.feed("2\n")
        assertEquals("I 42", b.nextLine())
    }

    @Test
    fun `multiple lines in one chunk`() {
        val b = ByteLineBuffer()
        b.feed("OK\nI 1\nD 2.5\n")
        assertEquals("OK", b.nextLine())
        assertEquals("I 1", b.nextLine())
        assertEquals("D 2.5", b.nextLine())
        assertNull(b.nextLine())
    }

    @Test
    fun `trims trailing carriage return and handles utf8`() {
        val b = ByteLineBuffer()
        b.feed("E 无权限\r\nN\n")
        assertEquals("E 无权限", b.nextLine())
        assertEquals("N", b.nextLine())
    }
}
