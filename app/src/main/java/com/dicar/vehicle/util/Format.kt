package com.dicar.vehicle.util

import java.util.Locale

/** UI 统一格式化：null 一律显示 N/A（F42）。 */
object Format {
    const val NA = "N/A"

    fun num(value: Number?, decimals: Int = 0, unit: String = ""): String {
        if (value == null) return NA
        val text = if (decimals == 0) {
            Math.round(value.toDouble()).toString()
        } else {
            String.format(Locale.US, "%.${decimals}f", value.toDouble())
        }
        return if (unit.isEmpty()) text else "$text $unit"
    }

    fun text(value: String?): String = value?.takeIf { it.isNotBlank() } ?: NA

    fun openClose(open: Boolean?): String = when (open) {
        null -> NA
        true -> "开"
        false -> "关"
    }

    fun onOff(on: Boolean?): String = when (on) {
        null -> NA
        true -> "开启"
        false -> "关闭"
    }

    fun level(level: Int?): String = when (level) {
        null -> NA
        0 -> "关"
        else -> "$level 档"
    }
}
