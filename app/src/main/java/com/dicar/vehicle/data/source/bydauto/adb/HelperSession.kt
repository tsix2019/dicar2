package com.dicar.vehicle.data.source.bydauto.adb

import android.util.Log
import dadb.AdbShellPacket
import dadb.AdbShellStream

/**
 * 包一层 shell v2 流，提供「写一行 / 读一行」的协议接口。
 *
 * 用 shell v2（[AdbShellStream]）而不是裸 shell：它把 stdout / stderr 分帧，
 * 辅助进程或 app_process 偶发打到 stderr 的告警不会混进协议，只取 StdOut 帧拼行。
 */
class HelperSession(private val shell: AdbShellStream) {

    @Volatile
    var alive = true
        private set

    private val pending = ByteLineBuffer()

    fun request(line: String): String {
        writeLine(line)
        return readLine() ?: throw IllegalStateException("辅助进程已退出")
    }

    private fun writeLine(line: String) {
        shell.write(line + "\n")
    }

    /** 读一行（不含换行）；辅助进程退出返回 null。 */
    fun readLine(): String? {
        while (true) {
            pending.nextLine()?.let { return it }
            val packet = try {
                shell.read()
            } catch (e: Exception) {
                alive = false
                throw e
            }
            when (packet) {
                is AdbShellPacket.StdOut -> pending.append(packet.payload)
                is AdbShellPacket.StdError -> Log.w(TAG, "helper stderr: ${String(packet.payload).trim()}")
                is AdbShellPacket.Exit -> {
                    alive = false
                    return pending.nextLine()
                }
            }
        }
    }

    fun close() {
        alive = false
        runCatching { shell.close() }
    }

    private companion object {
        const val TAG = "HelperSession"
    }
}
