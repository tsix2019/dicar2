package com.dicar.vehicle.data.source.bydauto

import android.util.Log

/**
 * 把 BydApiMap 里的 FID 符号（如 "Statistic.STATISTIC_ELEC_PERCENTAGE"）解析成本车固件的 fid 数值。
 *
 * android.hardware.bydauto.BYDAutoFeatureIds 下按设备分了嵌套类（Ac / Statistic / Speed ...），
 * 根类上通常还有同名的平铺副本；两处都找一遍。同一符号在不同平台的数值不同，所以必须运行时解析。
 */
class BydFeatureIds {

    private val cache = HashMap<String, Int?>()

    @Synchronized
    fun resolve(symbol: String): Int? {
        if (cache.containsKey(symbol)) return cache[symbol]
        val value = lookup(symbol)
        cache[symbol] = value
        if (value == null) Log.i(TAG, "fid symbol not found: $symbol")
        return value
    }

    private fun lookup(symbol: String): Int? {
        val nested = symbol.substringBefore('.', missingDelimiterValue = "")
        val field = symbol.substringAfter('.')
        if (nested.isNotEmpty()) {
            staticInt("${BydApiMap.FEATURE_IDS_CLASS}\$$nested", field)?.let { return it }
        }
        return staticInt(BydApiMap.FEATURE_IDS_CLASS, field)
    }

    private fun staticInt(className: String, field: String): Int? = try {
        (Class.forName(className).getField(field).get(null) as? Number)?.toInt()
    } catch (e: Throwable) {
        null
    }

    private companion object {
        const val TAG = "BydFeatureIds"
    }
}
