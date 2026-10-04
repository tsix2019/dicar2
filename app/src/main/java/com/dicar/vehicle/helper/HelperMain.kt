package com.dicar.vehicle.helper

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Looper

/**
 * 辅助进程入口，由 App 通过车机本机 adbd 以调试身份启动：
 *   CLASSPATH=<apk> app_process /system/bin com.dicar.vehicle.helper.HelperMain
 *
 * 作用：App 自身 UID 调 BYDAutoDeviceManager.getInt 会被车机服务端拒绝（签名权限），
 * 而本进程以 shell 身份运行，车机 autoservice 放行，于是能真正读写车辆数据。
 *
 * 协议：按行的「请求→应答」，stdin 收请求，stdout 回一行应答；日志只走 stderr / logcat，
 * 不污染 stdout。请求：
 *   PING | GI dev fid | GD dev fid | SI dev fid val | MG cls method argc args... | MC cls method argc args...
 * 应答：
 *   OK | I <int> | D <double> | A <csv> | N | S <text> | M（方法/设备缺失）| E <text>（异常）
 */
object HelperMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        try {
            // 部分 framework 调用需要主 Looper；app_process 下没有，先建一个
            @Suppress("DEPRECATION")
            runCatching { if (Looper.myLooper() == null) Looper.prepareMainLooper() }

            val reflect = HelperReflect(PermissiveContext(systemContext()))
            out.println("READY uid=${android.os.Process.myUid()}")
            out.flush()

            val reader = System.`in`.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val reply = runCatching { handle(line, reflect) }
                    .getOrElse { "E ${it.javaClass.simpleName}:${it.message}" }
                out.println(reply)
                out.flush()
            }
        } catch (t: Throwable) {
            out.println("FATAL ${t.javaClass.simpleName}:${t.message}")
            out.flush()
            System.err.println("helper fatal")
            t.printStackTrace()
        }
    }

    private fun handle(line: String, r: HelperReflect): String {
        val p = line.trim().split(' ')
        return when (p[0]) {
            "PING" -> "OK"
            "GI" -> render(r.getInt(p[1].toInt(), p[2].toInt()))
            "GD" -> render(r.getDouble(p[1].toInt(), p[2].toInt()))
            "SI" -> render(r.setInt(p[1].toInt(), p[2].toInt(), p[3].toInt()))
            "MG", "MC" -> {
                val cls = p[1]
                val method = p[2]
                val argc = p[3].toInt()
                val callArgs = IntArray(argc) { p[4 + it].toInt() }
                try {
                    render(r.call(cls, method, callArgs))
                } catch (e: NoSuchMethodException) {
                    "M"
                } catch (e: NoSuchElementException) {
                    "M"
                }
            }
            else -> "E 未知指令:${p[0]}"
        }
    }

    internal fun render(value: Any?): String = when (value) {
        null -> "N"
        is Int -> "I $value"
        is Long -> "I $value"
        is Short -> "I ${value.toInt()}"
        is Byte -> "I ${value.toInt()}"
        is Boolean -> "I ${if (value) 1 else 0}"
        is Double -> "D $value"
        is Float -> "D ${value.toDouble()}"
        is IntArray -> "A " + value.joinToString(",")
        is ShortArray -> "A " + value.joinToString(",")
        is ByteArray -> "A " + value.joinToString(",") { it.toInt().toString() }
        is FloatArray -> "A " + value.joinToString(",")
        is DoubleArray -> "A " + value.joinToString(",")
        else -> "S $value"
    }

    /** 反射取系统 Context（app_process 下无 hidden-api 限制）。 */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** 放行进程内的 BYDAUTO_* 预检查（服务端校验靠 shell 身份通过，这里是双保险）。 */
    private class PermissiveContext(base: Context) : ContextWrapper(base) {
        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(permission)

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)

        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
            if (!isByd(permission)) super.enforceCallingOrSelfPermission(permission, message)
        }

        private fun isByd(p: String?) = p?.startsWith("android.permission.BYDAUTO_") == true
    }
}
