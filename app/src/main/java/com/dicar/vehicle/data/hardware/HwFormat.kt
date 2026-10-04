package com.dicar.vehicle.data.hardware

import java.util.Locale
import kotlin.math.abs

/**
 * 硬件读数的格式化。全是纯函数，便于单测。
 *
 * 容量一律按 1024 进位并写作 KB/MB/GB——这是所有同类工具（CPU-Z、AIDA64、DevCheck）
 * 的惯例，换成严格的 KiB/MiB 反而会让人以为和别的软件读出来的数不一样。
 */
object HwFormat {

    private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun bytes(value: Long?): String {
        if (value == null || value < 0) return HwItem.UNKNOWN
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var unit = 0
        while (v >= 1024 && unit < BYTE_UNITS.lastIndex) {
            v /= 1024
            unit++
        }
        // 大于 100 的时候小数位没意义（「7.63 GB」有用，「763.42 MB」不如「763 MB」清爽）
        val decimals = if (v >= 100) 0 else 2
        return "${fixed(v, decimals)} ${BYTE_UNITS[unit]}"
    }

    /** sysfs 里的 cpufreq 都是 kHz。 */
    fun kHz(value: Long?): String = when {
        value == null || value <= 0 -> HwItem.UNKNOWN
        value >= 1_000_000 -> "${fixed(value / 1_000_000.0, 2)} GHz"
        value >= 1_000 -> "${fixed(value / 1_000.0, 0)} MHz"
        else -> "$value kHz"
    }

    fun hz(value: Long?): String = if (value == null || value <= 0) HwItem.UNKNOWN else kHz(value / 1000)

    /** 网络速率，安卓报的是 kbps。 */
    fun kbps(value: Int?): String = when {
        value == null || value <= 0 -> HwItem.UNKNOWN
        value >= 1_000_000 -> "${fixed(value / 1_000_000.0, 1)} Gbps"
        value >= 1_000 -> "${fixed(value / 1_000.0, 0)} Mbps"
        else -> "$value kbps"
    }

    fun percent(value: Float?, decimals: Int = 0): String =
        if (value == null) HwItem.UNKNOWN else "${fixed(value.toDouble(), decimals)}%"

    /** 摄氏度。毫摄氏度（sysfs thermal 常用）请先自己除 1000。 */
    fun celsius(value: Float?, decimals: Int = 1): String =
        if (value == null) HwItem.UNKNOWN else "${fixed(value.toDouble(), decimals)} ℃"

    /** 开机时长这类长间隔，精确到分钟就够。小于一分钟才显示秒。 */
    fun duration(millis: Long?): String {
        if (millis == null || millis < 0) return HwItem.UNKNOWN
        val totalSec = millis / 1000
        val days = totalSec / 86_400
        val hours = (totalSec % 86_400) / 3_600
        val minutes = (totalSec % 3_600) / 60
        return buildString {
            if (days > 0) append("$days 天 ")
            if (days > 0 || hours > 0) append("$hours 小时 ")
            if (days > 0 || hours > 0 || minutes > 0) append("$minutes 分") else append("${totalSec} 秒")
        }.trim()
    }

    /** 屏幕物理尺寸等，保留一位小数。 */
    fun inches(value: Float?): String =
        if (value == null || value <= 0) HwItem.UNKNOWN else "${fixed(value.toDouble(), 2)} 英寸"

    /**
     * 标称容量：内核报的 MemTotal 永远小于包装盒上的数字（内核自身和保留区被扣掉了）。
     *
     * 不能简单地往上取 2 的幂——3 GB / 6 GB / 12 GB 的机器非常多，那样会把 3 GB 报成 4 GB。
     * 改成在常见档位里找第一个装得下的；超出最大档位就不猜了。
     */
    fun nominalRam(total: Long?): String? {
        if (total == null || total <= 0) return null
        val gb = COMMON_RAM_GB.firstOrNull { it.toLong() shl 30 >= total } ?: return null
        return bytes(gb.toLong() shl 30)
    }

    private val COMMON_RAM_GB = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)

    /** 用于「3.8 / 8.0 GB（47%）」这种「已用 / 总量」的行。 */
    fun usage(used: Long?, total: Long?): String {
        if (used == null || total == null || total <= 0) return HwItem.UNKNOWN
        val pct = (used.toDouble() / total * 100).toFloat()
        return "${bytes(used)} / ${bytes(total)}（${percent(pct)}）"
    }

    fun list(values: Collection<String>): String =
        values.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("、") ?: HwItem.UNKNOWN

    /** 电流：安卓报的是微安，正数充电、负数放电（部分厂商反着来，所以带符号原样显示）。 */
    fun microAmp(value: Int?): String = when {
        value == null || value == Int.MIN_VALUE -> HwItem.UNKNOWN
        abs(value) >= 1000 -> "${fixed(value / 1000.0, 0)} mA"
        else -> "$value µA"
    }

    private fun fixed(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)
}
