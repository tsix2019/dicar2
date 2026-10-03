package com.dicar.vehicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueSanitizerTest {

    @Test
    fun `BYDAuto error codes become null`() {
        assertNull(ValueSanitizer.toDouble(-2147482645))
        assertNull(ValueSanitizer.toDouble(-2147482648))
        assertNull(ValueSanitizer.toDouble(Int.MIN_VALUE))
        assertNull(ValueSanitizer.toDouble(Double.NaN))
        assertNull(ValueSanitizer.toDouble(-2147482645.0))
        assertNull(ValueSanitizer.toDouble("abc"))
        assertNull(ValueSanitizer.toDouble(null))
    }

    @Test
    fun `normal values pass through`() {
        assertEquals(0.0, ValueSanitizer.toDouble(0)!!, 0.0)
        assertEquals(-40.0, ValueSanitizer.toDouble(-40)!!, 0.0)
        assertEquals(12.5, ValueSanitizer.toDouble(12.5f)!!, 0.0)
        assertEquals(88.0, ValueSanitizer.toDouble(" 88 ")!!, 0.0)
    }

    @Test
    fun `range check rejects raw invalid signals`() {
        assertNull(ValueSanitizer.int(0xFFFF, 0, 400))
        assertNull(ValueSanitizer.float(255, -40.0, 125.0))
        assertEquals(120, ValueSanitizer.int(120, 0, 400))
        assertEquals(2.5f, ValueSanitizer.float(25, 0.0, 100.0, scale = 0.1)!!, 1e-6f)
    }

    @Test
    fun `bool mapping`() {
        assertTrue(ValueSanitizer.bool(1)!!)
        assertEquals(false, ValueSanitizer.bool(0))
        assertEquals(true, ValueSanitizer.bool(2, trueValues = setOf(2)))
        assertNull(ValueSanitizer.bool(-2147482645))
    }
}
