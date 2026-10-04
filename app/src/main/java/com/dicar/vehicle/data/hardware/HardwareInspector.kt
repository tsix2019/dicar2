package com.dicar.vehicle.data.hardware

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 硬件信息总装。
 *
 * 每个采集器单独 try：某一类读失败（权限、厂商魔改、节点不存在）只应该让那一张卡片
 * 显示错误，不能拖垮整页。这在车机上不是假设——不同固件能读到的东西差别很大。
 */
class HardwareInspector(private val context: Context) {

    data class Export(val file: File?, val text: String)

    /** 静态信息。耗时主要在编解码器枚举和 getprop 上，必须放后台线程。 */
    fun collectStatic(): List<HwSection> = listOf(
        guard(HwCategory.SOC) { collectSoc(context) },
        guard(HwCategory.GPU) { collectGpu(context) },
        guard(HwCategory.MEMORY) { collectMemory(context) },
        guard(HwCategory.STORAGE) { collectStorage(context) },
        guard(HwCategory.DISPLAY) { collectDisplay(context) },
        guard(HwCategory.SENSOR) { collectSensors(context) },
        guard(HwCategory.CAMERA) { collectCameras(context) },
        guard(HwCategory.NETWORK) { collectNetwork(context) },
        guard(HwCategory.AUDIO) { collectAudio(context) },
        guard(HwCategory.CODEC) { collectCodecs() },
        guard(HwCategory.SYSTEM) { collectSystem(context) },
        guard(HwCategory.FEATURE) { collectFeatures(context) },
        guard(HwCategory.PROPS) { collectProps() },
    )

    private inline fun guard(category: HwCategory, body: () -> HwSection): HwSection = try {
        body()
    } catch (e: Throwable) {
        Log.w(TAG, "collect ${category.name} failed", e)
        section(category) {
            block { item("采集失败", "${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}") }
        }
    }

    /**
     * 导出成文本。[redact] 为 true 时抹掉能定位到这台设备的标识（序列号、MAC、IP、
     * Android ID 等）——导出文件是用来发给别人看的，默认不该把这些带出去。
     */
    fun export(sections: List<HwSection>, redact: Boolean): Export {
        val text = render(sections, redact)
        val file = runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "hardware").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "hardware_$stamp.txt").apply { writeText(text) }
        }.getOrElse {
            Log.w(TAG, "write hardware report failed", it)
            null
        }
        return Export(file, text)
    }

    private fun render(sections: List<HwSection>, redact: Boolean) = buildString {
        appendLine("# 车机硬件信息")
        appendLine("时间    : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("设备    : ${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）")
        appendLine("系统    : Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("采集方   : DiCar 车况")
        appendLine(
            if (redact) "标识符   : 已隐去（序列号 / MAC / IP / Android ID 等）"
            else "标识符   : 原样保留 —— 这份文件能定位到这台设备，分享前请自行确认"
        )
        appendLine()
        for (s in sections) {
            appendLine("## ${s.category.label}")
            for (b in s.blocks) {
                b.subtitle?.let { appendLine("  [$it]") }
                for (i in b.items) {
                    val value = if (redact) Redactor.scrub(i.label, i.value) else i.value
                    appendLine("    ${i.label.padEnd(LABEL_WIDTH)} ${value.replace('\n', ' ')}")
                }
            }
            appendLine()
        }
    }

    companion object {
        private const val TAG = "HardwareInspector"
        private const val LABEL_WIDTH = 20
    }
}

/** 电池。车机常常没有独立电池，这时如实说明而不是报一堆 0。 */
fun batterySection(live: BatteryLive?): HwSection = section(HwCategory.BATTERY) {
    if (live == null) {
        block { item("电池", "系统未提供电池信息") }
        return@section
    }
    block {
        flag("存在电池", live.present, yes = "有", no = "无")
        item("电量", HwFormat.percent(live.levelPercent))
        item("状态", live.status)
        item("健康", live.health)
        item("供电", live.plugged)
        item("类型", live.technology)
    }
    block("读数") {
        item("电压", live.voltageMv?.let { "$it mV" })
        item("温度", HwFormat.celsius(live.temperature))
        item("瞬时电流", HwFormat.microAmp(live.currentNowUa))
        item("平均电流", HwFormat.microAmp(live.currentAvgUa))
        // 这个值除以电量百分比就能估出设计容量，是同类工具算「电池健康度」的依据
        item("剩余电荷", live.chargeCounterUah?.let { "${it / 1000} mAh" })
        item("剩余能量", live.energyCounterNwh?.let { "${it / 1_000_000} mWh" })
        item("估算总容量", estimateCapacity(live))
        item("循环次数", live.cycleCount?.toString())
    }
}

/**
 * 由「剩余电荷 ÷ 当前电量百分比」反推总容量。
 * 只是估算：电量百分比本身就是电量计拟合出来的，低电量时误差很大。
 */
private fun estimateCapacity(live: BatteryLive): String? {
    val charge = live.chargeCounterUah ?: return null
    val percent = live.levelPercent?.takeIf { it > 5f } ?: return null
    return "约 ${(charge / 1000 / percent * 100).toInt()} mAh"
}

/** 温度。各路 thermal zone 的当前读数。 */
fun thermalSection(live: LiveStats): HwSection = section(HwCategory.THERMAL) {
    block {
        item("系统热状态", live.thermalStatus)
        item("温区数量", "${live.thermal.size} 路")
        item("最高温度", live.thermal.mapNotNull { it.celsius }.maxOrNull()?.let { HwFormat.celsius(it) })
    }
    if (live.thermal.isEmpty()) {
        block {
            item("提示", "系统不允许应用读取 /sys/class/thermal，这在较新的安卓上很常见")
        }
        return@section
    }
    block("温区（${live.thermal.size}）") {
        // 按温度从高到低，最值得关注的排最上面
        live.thermal.sortedByDescending { it.celsius ?: -999f }.forEach { t ->
            item("${t.type}（${t.zone}）", HwFormat.celsius(t.celsius))
        }
    }
}

/**
 * 导出时抹掉设备标识。
 *
 * 两道：按字段名命中敏感词的整条打码；剩下的再用正则扫一遍 IP 和 MAC——
 * 同一个 MAC 可能藏在某条不起眼的系统属性里，光看字段名会漏。
 */
object Redactor {

    const val MASK = "[已隐去]"

    fun scrub(label: String, value: String): String {
        if (value == HwItem.UNKNOWN || value.isBlank()) return value
        val key = label.lowercase()
        if (SENSITIVE_KEYS.any { it in key }) return MASK
        return value
            .replace(MAC, MASK)
            // 回环和全零地址不是标识，留着反而有助于判断网络状态
            .replace(IPV4) { m -> if (m.value in KEEP_IPS) m.value else MASK }
    }

    private val SENSITIVE_KEYS = listOf(
        "serial", "序列号", "imei", "meid", "iccid", "imsi",
        "mac", "bssid", "ssid", "android_id", "androidid",
        "ip 地址", "ip地址", "uuid", "系统 id", "systemid", "设备 id", "deviceid",
    )

    private val MAC = Regex("""\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""")
    private val IPV4 = Regex("""\b((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)\b""")
    private val KEEP_IPS = setOf("127.0.0.1", "0.0.0.0", "255.255.255.255")
}
