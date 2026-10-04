package com.dicar.vehicle.data.source.bydauto.adb

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** 车机调试口无法连接 / 未授权 / 辅助进程起不来，统一抛这个，便于向用户展示友好原因。 */
class AdbUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 管理到车机本机 adbd（127.0.0.1:5555）的连接，并在其上启动/维持辅助进程 [com.dicar.vehicle.helper.HelperMain]。
 *
 * - ADB 密钥持久化在 App 私有目录，首次连接触发车机「允许调试」弹窗（勾一律允许后不再弹）；
 * - 辅助进程以 shell 身份运行，App 通过它读写被签名权限保护的车辆数据；
 * - 所有外部方法都在 Repository 的单 IO 线程上调用，这里再用锁兜底防止重入。
 */
class AdbTransport(context: Context) {

    private val appContext = context.applicationContext
    private val keyDir = File(appContext.filesDir, "adb")
    private val lock = Any()

    private var dadb: Dadb? = null
    private var session: HelperSession? = null

    /** 快速探测调试口是否开着（不做握手），用于判断 BYDAuto-ADB 这条路是否值得尝试。 */
    fun isPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), PORT_PROBE_TIMEOUT_MS); true }
    } catch (e: Exception) {
        false
    }

    /** 建立连接与辅助进程（已建立则直接返回），不可用时抛 [AdbUnavailableException]。 */
    fun ensureConnected() = synchronized(lock) { ensureSession(); Unit }

    /** 发送一行请求，返回一行应答；连接/辅助进程异常时抛 [AdbUnavailableException]。 */
    fun request(line: String): String = withSession { it.request(line) }

    /** 批量请求：一次往返完成全部子请求。 */
    fun requestBatch(lines: List<String>): List<String> = withSession { it.requestBatch(lines) }

    private fun <T> withSession(block: (HelperSession) -> T): T = synchronized(lock) {
        val s = ensureSession()
        try {
            block(s)
        } catch (e: AdbUnavailableException) {
            throw e
        } catch (e: Exception) {
            resetLocked() // 流坏了就整条重建，下一轮重新握手
            throw AdbUnavailableException("与辅助进程通信失败：${e.message}", e)
        }
    }

    fun reset() = synchronized(lock) { resetLocked() }

    private fun ensureSession(): HelperSession {
        session?.let { if (it.alive) return it }
        val connection = dadb ?: createDadb().also { dadb = it }

        val apk = appContext.applicationInfo.sourceDir
        // exec 让 app_process 取代 sh，流关闭时辅助进程随之退出；stdout 只走协议，日志走 logcat
        val cmd = "CLASSPATH=$apk exec app_process /system/bin --nice-name=dicar_helper " +
            "com.dicar.vehicle.helper.HelperMain"
        val shell = try {
            connection.openShell(cmd)
        } catch (e: Exception) {
            resetLocked()
            throw AdbUnavailableException("连接车机调试失败（确认已开启无线调试并在车机上允许本机调试）：${e.message}", e)
        }

        val s = HelperSession(shell)
        val ready = try {
            s.readLine()
        } catch (e: Exception) {
            s.close(); throw AdbUnavailableException("辅助进程无响应：${e.message}", e)
        }
        if (ready == null || !ready.startsWith("READY")) {
            s.close()
            throw AdbUnavailableException("辅助进程启动失败：${ready ?: "无输出"}")
        }
        Log.i(TAG, "helper ready: $ready")
        session = s
        return s
    }

    private fun createDadb(): Dadb {
        keyDir.mkdirs()
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
            Log.i(TAG, "generated adb key")
        }
        val keyPair = AdbKeyPair.read(priv, pub)
        return try {
            Dadb.create(HOST, PORT, keyPair, connectTimeout = CONNECT_TIMEOUT_MS, socketTimeout = SOCKET_TIMEOUT_MS, keepAlive = true)
        } catch (e: Exception) {
            throw AdbUnavailableException("无法创建车机调试连接：${e.message}", e)
        }
    }

    private fun resetLocked() {
        session?.close(); session = null
        runCatching { dadb?.close() }; dadb = null
    }

    private companion object {
        const val TAG = "AdbTransport"
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val PORT_PROBE_TIMEOUT_MS = 300
        const val CONNECT_TIMEOUT_MS = 8_000
        const val SOCKET_TIMEOUT_MS = 5_000
    }
}
