package com.dicar.vehicle.data.source.diplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiPlusParseTest {

    @Test
    fun `parses alias value pairs`() {
        val map = DiPlusDataSource.parseVal("SOC:82.5|Speed:0|Gear:1")
        assertEquals("82.5", map["SOC"])
        assertEquals("0", map["Speed"])
        assertEquals("1", map["Gear"])
    }

    @Test
    fun `tolerates empty and malformed pairs`() {
        val map = DiPlusDataSource.parseVal("SOC:|:5|Broken|Steer:-73.9")
        assertEquals("", map["SOC"])
        assertEquals("-73.9", map["Steer"])
        assertFalse(map.containsKey("Broken"))
        assertEquals(2, map.size)
    }

    @Test
    fun `template aliases are unique and safe`() {
        val aliases = DiPlusDataSource.PARAMS.map { it.first }
        assertEquals(aliases.size, aliases.toSet().size)
        assertTrue(aliases.all { a -> a.all { it.isLetterOrDigit() } })
        assertTrue(DiPlusDataSource.TEMPLATE.startsWith("Speed:{车速}|"))
    }
}
