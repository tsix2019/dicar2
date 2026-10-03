package com.dicar.vehicle.util

/**
 * 车机原始值清洗：把各种“无效值”统一转成 null。
 *
 * BYDAuto 接口在功能不支持 / 服务未就绪 / 信号无效时，不抛异常而是返回错误码：
 * - 命名 getter：-2147482645（0x800003EB，无效/不支持）、-2147482648~-2147482646（失败/忙/超时）；
 * - FID 直读：-10011（不支持）、-10013（暂无数据）；
 * - CAN 信号全 1 无效值：255 / 65535 / 0x000FFFFF（由各字段的范围校验过滤）。
 * 直接显示会出现“车速 -2147482645”这种值，所以所有读数都必须过一遍这里。
 */
object ValueSanitizer {

    /** Int.MIN_VALUE 往上这一段都视为 BYDAuto 错误码。 */
    private const val ERROR_CODE_CEILING = Int.MIN_VALUE + 0x10000

    /** FID 直读返回的错误码。 */
    private val FID_ERROR_CODES = setOf(-10011, -10013)

    fun isErrorCode(raw: Int): Boolean = raw <= ERROR_CODE_CEILING || raw in FID_ERROR_CODES

    /** 把反射拿到的任意数值统一成 Double，错误码 / NaN / 非数值 → null。 */
    fun toDouble(raw: Any?): Double? = when (raw) {
        null -> null
        is Int -> if (isErrorCode(raw)) null else raw.toDouble()
        is Long -> if (raw <= ERROR_CODE_CEILING || raw.toInt() in FID_ERROR_CODES) null else raw.toDouble()
        is Short -> raw.toDouble()
        is Byte -> raw.toDouble()
        is Float -> raw.toDouble().takeIf { it.isFinite() && it > ERROR_CODE_CEILING }
        is Double -> raw.takeIf { it.isFinite() && it > ERROR_CODE_CEILING }
        is Boolean -> if (raw) 1.0 else 0.0
        is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > ERROR_CODE_CEILING }
        else -> null
    }

    /** 读数 + 合理范围校验；超出范围（含 0xFFFF 这类无效信号）一律 null。 */
    fun inRange(raw: Any?, min: Double, max: Double): Double? =
        toDouble(raw)?.takeIf { it in min..max }

    fun float(raw: Any?, min: Double, max: Double, scale: Double = 1.0): Float? =
        inRange(raw, min, max)?.let { (it * scale).toFloat() }

    fun int(raw: Any?, min: Int, max: Int): Int? =
        inRange(raw, min.toDouble(), max.toDouble())?.toInt()

    /** 状态量 → Boolean。[trueValues] 之外的合法值视为 false，错误码视为 null。 */
    fun bool(raw: Any?, trueValues: Set<Int> = setOf(1)): Boolean? = when (raw) {
        is Boolean -> raw
        else -> toDouble(raw)?.let { it.toInt() in trueValues }
    }
}
