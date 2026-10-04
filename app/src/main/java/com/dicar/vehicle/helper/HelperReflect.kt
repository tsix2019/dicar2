package com.dicar.vehicle.helper

import android.content.Context
import java.lang.reflect.Method

/**
 * 辅助进程内的反射封装。运行在 app_process（调试身份）下，只依赖 android + kotlin，
 * 不引用 App 的任何业务类，避免把 Compose 等拖进来。
 *
 * - FID 直读/写：通过 BYDAutoDeviceManager.getInt/getDouble/setInt(dev, fid[, val])；
 * - 命名方法：在设备类 getInstance(Context) 上按方法名 + int 形参个数反射调用。
 *
 * 与 App 内的 BydDevice 逻辑重复是刻意的：辅助进程必须保持独立、最小依赖。
 */
class HelperReflect(private val ctx: Context) {

    private val manager: Any by lazy {
        val cls = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
        cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            ?: error("BYDAutoDeviceManager.getInstance 返回 null")
    }

    private val deviceCache = HashMap<String, Any?>()
    private val methodCache = HashMap<String, Method?>()

    fun getInt(dev: Int, fid: Int): Int = managerCall("getInt", dev, fid) as Int

    fun getDouble(dev: Int, fid: Int): Double = managerCall("getDouble", dev, fid) as Double

    fun setInt(dev: Int, fid: Int, value: Int): Int = managerCall("setInt", dev, fid, value) as Int

    /** 调用设备类的命名方法（getter 或 setter）。 */
    fun call(className: String, method: String, args: IntArray): Any? {
        val device = device(className) ?: throw NoSuchElementException("设备不可用:$className")
        val m = findMethod(device.javaClass, method, args.size)
            ?: throw NoSuchMethodException("$className.$method/${args.size}")
        return m.invoke(device, *boxArgs(m, args))
    }

    private fun managerCall(name: String, vararg args: Int): Any? {
        val m = findMethod(manager.javaClass, name, args.size)
            ?: throw NoSuchMethodException("manager.$name/${args.size}")
        return m.invoke(manager, *boxArgs(m, args))
    }

    private fun device(className: String): Any? = deviceCache.getOrPut(className) {
        runCatching {
            Class.forName(className).getMethod("getInstance", Context::class.java).invoke(null, ctx)
        }.getOrNull()
    }

    private fun findMethod(cls: Class<*>, name: String, argc: Int): Method? {
        val key = "${cls.name}#$name/$argc"
        if (methodCache.containsKey(key)) return methodCache[key]
        val found = cls.methods
            .filter { it.name == name && it.parameterTypes.size == argc && it.parameterTypes.all { p -> p.isPrimitive || p == Integer::class.java } }
            .sortedByDescending { it.parameterTypes.count { p -> p == Int::class.javaPrimitiveType } }
            .firstOrNull()
        methodCache[key] = found
        return found
    }

    private fun boxArgs(m: Method, args: IntArray): Array<Any> = Array(args.size) { i ->
        when (m.parameterTypes[i]) {
            Long::class.javaPrimitiveType -> args[i].toLong()
            Double::class.javaPrimitiveType -> args[i].toDouble()
            Float::class.javaPrimitiveType -> args[i].toFloat()
            Boolean::class.javaPrimitiveType -> args[i] != 0
            Short::class.javaPrimitiveType -> args[i].toShort()
            Byte::class.javaPrimitiveType -> args[i].toByte()
            else -> args[i]
        }
    }
}
