package com.dicar.vehicle.data.source.bydauto

import com.dicar.vehicle.data.source.bydauto.BydDevice.CallResult
import com.dicar.vehicle.helper.HelperMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 辅助进程 render（出）与 App parseReply（入）必须严格对称。 */
class HelperWireTest {

    private fun roundTrip(value: Any?): CallResult = AdbBydAccess.parseReply(HelperMain.render(value))

    @Test
    fun `int round trips`() {
        assertEquals(42, (roundTrip(42) as CallResult.Value).value)
        assertEquals(-2147482645, (roundTrip(-2147482645) as CallResult.Value).value)
    }

    @Test
    fun `boolean renders as int`() {
        assertEquals(1, (roundTrip(true) as CallResult.Value).value)
        assertEquals(0, (roundTrip(false) as CallResult.Value).value)
    }

    @Test
    fun `double round trips`() {
        assertEquals(535.2, (roundTrip(535.2) as CallResult.Value).value as Double, 1e-9)
    }

    @Test
    fun `int array round trips`() {
        val v = roundTrip(intArrayOf(12, 0, 35)) as CallResult.Value
        assertArrayEquals(intArrayOf(12, 0, 35), v.value as IntArray)
    }

    @Test
    fun `null renders as N`() {
        assertEquals("N", HelperMain.render(null))
        assertNull((roundTrip(null) as CallResult.Value).value)
    }

    @Test
    fun `missing and error`() {
        assertTrue(AdbBydAccess.parseReply("M") is CallResult.Missing)
        val e = AdbBydAccess.parseReply("E SecurityException:permission deny") as CallResult.Error
        assertTrue(e.error.message!!.contains("permission deny"))
    }
}
