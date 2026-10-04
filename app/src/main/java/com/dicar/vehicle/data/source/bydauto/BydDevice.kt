package com.dicar.vehicle.data.source.bydauto

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 单个 BYDAuto 类（设备类或 BYDAutoDeviceManager）的反射包装。
 *
 * - 类与实例懒加载，失败后不再重试（同一进程内结果不会变），失败原因保存在 [initError]；
 * - Method 按「方法名 + 参数个数」缓存，查不到记为缺失；
 * - 调用失败只打一次日志，返回 [CallResult.Missing] / [CallResult.Error]，绝不抛给上层。
 *
 * BYDAuto 的 getter/setter 参数几乎都是 int（区域、位置、档位），所以这里统一用 Int 传参，
 * 只匹配形参全是数值/布尔类型的重载（避免误调 get(int[], Class) 这类同名方法）。
 */
class BydDevice(private val context: Context, val className: String) {

    sealed interface CallResult {
        data class Value(val value: Any?) : CallResult
        data object Missing : CallResult
        data class Error(val error: Throwable) : CallResult
    }

    private var loaded = false
    private var instance: Any? = null
    private val methodCache = HashMap<String, Method?>()
    private val reported = HashSet<String>()

    /** getInstance 失败的原因（类不存在时为 null）。 */
    var initError: Throwable? = null
        private set

    val isAvailable: Boolean get() = obtain() != null

    /** 获取单例：优先 getInstance(Context)，其次 getInstance()。 */
    @Synchronized
    fun obtain(): Any? {
        if (loaded) return instance
        loaded = true
        instance = try {
            val cls = Class.forName(className)
            val byContext = cls.methods.firstOrNull {
                it.name == "getInstance" && Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java
            }
            val noArg = cls.methods.firstOrNull {
                it.name == "getInstance" && Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty()
            }
            when {
                byContext != null -> byContext.invoke(null, context)
                noArg != null -> noArg.invoke(null)
                else -> null
            }
        } catch (e: ClassNotFoundException) {
            null // 非比亚迪系统 / 该车型没有这个设备：正常情况，不打堆栈
        } catch (e: Throwable) {
            initError = unwrap(e)
            Log.w(TAG, "getInstance failed: $className", initError)
            null
        }
        if (instance == null) Log.i(TAG, "device unavailable: $className")
        return instance
    }

    fun call(method: String, vararg args: Int): CallResult {
        val target = obtain() ?: return CallResult.Missing
        val m = findMethod(target.javaClass, method, args.size) ?: run {
            logOnce("missing:$method/${args.size}") { "method not found: ${shortName()}.$method(${args.size} args)" }
            return CallResult.Missing
        }
        return try {
            CallResult.Value(m.invoke(target, *convertArgs(m, args)))
        } catch (e: Throwable) {
            val cause = unwrap(e)
            logOnce("error:$method/${args.size}:${cause.javaClass.name}") {
                "call failed: ${shortName()}.$method${args.contentToString()} -> $cause"
            }
            CallResult.Error(cause)
        }
    }

    /** 读类上的 public static 常量（如区域编号），读不到返回 null。 */
    fun constant(name: String): Int? = try {
        (Class.forName(className).getField(name).get(null) as? Number)?.toInt()
    } catch (e: Throwable) {
        null
    }

    private fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? {
        val key = "$name/$argCount"
        if (methodCache.containsKey(key)) return methodCache[key]
        val found = cls.methods
            .filter { m -> m.name == name && m.parameterTypes.size == argCount && m.parameterTypes.all { it in INT_CONVERTIBLE } }
            // 有重载时优先全 int 形参的版本
            .sortedByDescending { m -> m.parameterTypes.count { it == Int::class.javaPrimitiveType } }
            .firstOrNull()
        methodCache[key] = found
        return found
    }

    private fun convertArgs(m: Method, args: IntArray): Array<Any> =
        Array(args.size) { i ->
            when (m.parameterTypes[i]) {
                Long::class.javaPrimitiveType, Long::class.javaObjectType -> args[i].toLong()
                Double::class.javaPrimitiveType, Double::class.javaObjectType -> args[i].toDouble()
                Float::class.javaPrimitiveType, Float::class.javaObjectType -> args[i].toFloat()
                Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> args[i] != 0
                Short::class.javaPrimitiveType, Short::class.javaObjectType -> args[i].toShort()
                Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> args[i].toByte()
                else -> args[i]
            }
        }

    private fun shortName() = className.substringAfterLast('.')

    private inline fun logOnce(key: String, message: () -> String) {
        if (reported.add(key)) Log.w(TAG, message())
    }

    companion object {
        private const val TAG = "BydDevice"

        private val INT_CONVERTIBLE: Set<Class<*>?> = setOf(
            Int::class.javaPrimitiveType, Int::class.javaObjectType,
            Long::class.javaPrimitiveType, Long::class.javaObjectType,
            Double::class.javaPrimitiveType, Double::class.javaObjectType,
            Float::class.javaPrimitiveType, Float::class.javaObjectType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType,
            Short::class.javaPrimitiveType, Short::class.javaObjectType,
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType,
        )

        fun unwrap(e: Throwable): Throwable =
            if (e is InvocationTargetException && e.targetException != null) e.targetException else e
    }
}
