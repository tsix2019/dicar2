package com.dicar.vehicle.data.source.bydauto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DexTypeScannerTest {

    /** 手工拼一个只含 header + string_ids + type_ids + string_data 的最小 dex。 */
    private fun miniDex(vararg descriptors: String): ByteArray {
        val headerSize = 0x70
        val n = descriptors.size
        val stringIdsOff = headerSize
        val typeIdsOff = stringIdsOff + n * 4
        val dataOff = typeIdsOff + n * 4
        val data = descriptors.map { byteArrayOf(it.length.toByte()) + it.toByteArray() + 0 }
        val total = dataOff + data.sumOf { it.size }
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n035\u0000".toByteArray())
        buf.putInt(0x3C, stringIdsOff)
        buf.putInt(0x40, n)
        buf.putInt(0x44, typeIdsOff)
        var cursor = dataOff
        data.forEachIndexed { i, bytes ->
            buf.putInt(stringIdsOff + i * 4, cursor)
            buf.putInt(typeIdsOff + i * 4, i)
            bytes.forEachIndexed { j, b -> buf.put(cursor + j, b) }
            cursor += bytes.size
        }
        return buf.array()
    }

    @Test
    fun `finds bydauto types only`() {
        val dex = miniDex(
            "Landroid/hardware/bydauto/ac/BYDAutoAcDevice;",
            "Ljava/lang/Object;",
            "Landroid/hardware/bydauto/BYDAutoFeatureIds\$Ac;",
        )
        val result = DexTypeScanner.scan(dex, "Landroid/hardware/bydauto/")
        assertEquals(3, result.typeCount)
        assertEquals(
            listOf("Landroid/hardware/bydauto/ac/BYDAutoAcDevice;", "Landroid/hardware/bydauto/BYDAutoFeatureIds\$Ac;"),
            result.matches,
        )
    }

    @Test
    fun `rejects non dex input`() {
        assertEquals(0, DexTypeScanner.scan(ByteArray(200), "L").typeCount)
        assertEquals(0, DexTypeScanner.scan("PK".toByteArray(), "L").typeCount)
    }
}
