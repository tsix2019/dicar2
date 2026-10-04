package com.dicar.vehicle.data.source.bydauto.adb

import java.io.ByteArrayOutputStream

/**
 * 把分片到达的字节流按 '\n' 拼成行。shell v2 的 StdOut 帧边界和行边界不一致，
 * 需要自己缓冲。独立成类便于单测。
 */
class ByteLineBuffer {

    private val buffer = ByteArrayOutputStream(256)

    fun append(bytes: ByteArray, length: Int = bytes.size) {
        buffer.write(bytes, 0, length)
    }

    /** 取出下一整行（不含换行、去掉结尾 '\r'）；没有完整行返回 null。 */
    fun nextLine(): String? {
        val bytes = buffer.toByteArray()
        val nl = bytes.indexOf('\n'.code.toByte())
        if (nl < 0) return null
        val line = String(bytes, 0, nl, Charsets.UTF_8).trimEnd('\r')
        buffer.reset()
        if (nl + 1 < bytes.size) buffer.write(bytes, nl + 1, bytes.size - nl - 1)
        return line
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) if (this[i] == b) return i
        return -1
    }
}
