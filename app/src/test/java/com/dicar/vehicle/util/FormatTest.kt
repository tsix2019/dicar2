package com.dicar.vehicle.util

import com.dicar.vehicle.data.model.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun `null shows NA`() {
        assertEquals("N/A", Format.num(null, 1, "℃"))
        assertEquals("N/A", Format.text(null))
        assertEquals("N/A", Format.text("  "))
        assertEquals("N/A", Format.level(null))
    }

    @Test
    fun `number formatting`() {
        assertEquals("62 km/h", Format.num(61.6f, unit = "km/h"))
        assertEquals("22.5 ℃", Format.num(22.5f, 1, "℃"))
        assertEquals("3.342", Format.num(3.342f, 3))
        assertEquals("关", Format.level(0))
        assertEquals("2 档", Format.level(2))
    }

    @Test
    fun `cell voltage diff needs both values`() {
        assertNull(VehicleState(cellVoltageMax = 3.34f).cellVoltageDiffMv)
        assertEquals(13f, VehicleState(cellVoltageMax = 3.342f, cellVoltageMin = 3.329f).cellVoltageDiffMv!!, 0.01f)
    }
}
