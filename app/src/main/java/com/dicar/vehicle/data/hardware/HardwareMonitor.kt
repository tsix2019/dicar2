package com.dicar.vehicle.data.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants

/** 一个核心此刻的状态。 */
data class CoreLive(
    val index: Int,
    val online: Boolean,
    val curKhz: Long?,
    val minKhz: Long?,
    val maxKhz: Long?,
    val governor: String?,
    /** 两次采样之间的占用率；采样间隔内没有变化时为 null。 */
    val load: Float?,
    /** 核心型号，如 `Cortex-A55`。 */
    val model: String?,
)

data class ThermalReading(val zone: String, val type: String, val celsius: Float?)

data class BatteryLive(
    val present: Boolean,
    val levelPercent: Float?,
    val status: String,
    val health: String,
    val plugged: String,
    val technology: String?,
    val voltageMv: Int?,
    val temperature: Float?,
    val currentNowUa: Int?,
    val currentAvgUa: Int?,
    val chargeCounterUah: Int?,
    val energyCounterNwh: Long?,
    val capacityPercent: Int?,
    val cycleCount: Int?,
)

/** 整页硬件数据里会随时间变的那一部分。静态信息由 [HardwareInspector] 只采一次。 */
data class LiveStats(
    val totalLoad: Float?,
    /** /proc/stat 能不能读。读不到时界面要说清是系统限制，而不是让人以为读数坏了。 */
    val systemLoadAvailable: Boolean,
    /** 本应用自己的 CPU 占用。/proc/self/stat 永远读得到，是全局占用读不到时的退路。 */
    val appLoad: Float?,
    val cores: List<CoreLive>,
    val gpuHz: Long?,
    val memTotal: Long?,
    val memAvailable: Long?,
    val memFree: Long?,
    val memCached: Long?,
    val swapTotal: Long?,
    val swapFree: Long?,
    val lowMemory: Boolean,
    val javaHeapUsed: Long?,
    val javaHeapMax: Long?,
    val nativeHeapUsed: Long?,
    val thermal: List<ThermalReading>,
    val thermalStatus: String?,
    val battery: BatteryLive?,
    val uptimeMs: Long,
    val awakeMs: Long,
    /** 本应用打开的文件描述符数。/proc 被 hidepid 挡住后，这是少数还能读的进程级指标。 */
    val openFds: Int?,
) {
    val memUsed: Long? get() = if (memTotal != null && memAvailable != null) memTotal - memAvailable else null
}

/**
 * 动态指标采样器。
 *
 * CPU 占用率必须靠两次 `/proc/stat` 之间的差值算，所以这个类是**有状态**的：
 * 调用方自己按固定节奏反复调 [sample]，第一次返回的占用率全是 null（没有前一帧可比）。
 */
class HardwareMonitor(private val context: Context) {

    private var previous: Map<String, ProcParse.CpuTimes> = emptyMap()
    /** 核心型号不会变，第一次读完就缓存住，之后每秒采样不再碰 /proc/cpuinfo。 */
    private val coreModels: Map<Int, String?> by lazy {
        val text = SysFs.text("/proc/cpuinfo") ?: return@lazy emptyMap()
        ProcParse.cpuCores(text).associate { it.index to SiliconNames.core(it.implementer, it.part) }
    }

    fun sample(): LiveStats {
        val stat = SysFs.text("/proc/stat")
        val now = stat?.let { ProcParse.cpuTimes(it) } ?: emptyMap()
        val before = previous
        previous = now

        val mem = SysFs.text("/proc/meminfo")?.let { ProcParse.memInfo(it) } ?: emptyMap()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val amInfo = am?.let { ActivityManager.MemoryInfo().also { info -> it.getMemoryInfo(info) } }
        val runtime = Runtime.getRuntime()

        return LiveStats(
            totalLoad = ProcParse.load(before["cpu"], now["cpu"]),
            systemLoadAvailable = stat != null,
            appLoad = appLoad(),
            cores = cores(before, now),
            gpuHz = SysFs.gpuHz(),
            // ActivityManager 的数字是系统口径（扣掉了内核保留），meminfo 是全量；
            // 两边都给，优先显示系统口径，和「设置 → 关于手机」里的数对得上
            memTotal = amInfo?.totalMem ?: mem["MemTotal"],
            memAvailable = amInfo?.availMem ?: mem["MemAvailable"],
            memFree = mem["MemFree"],
            memCached = mem["Cached"],
            swapTotal = mem["SwapTotal"],
            swapFree = mem["SwapFree"],
            lowMemory = amInfo?.lowMemory == true,
            javaHeapUsed = runtime.totalMemory() - runtime.freeMemory(),
            javaHeapMax = runtime.maxMemory(),
            nativeHeapUsed = Debug.getNativeHeapAllocatedSize(),
            thermal = thermal(),
            thermalStatus = thermalStatus(),
            battery = battery(),
            uptimeMs = SystemClock.elapsedRealtime(),
            awakeMs = SystemClock.uptimeMillis(),
            openFds = SysFs.children("/proc/self/fd").size.takeIf { it > 0 },
        )
    }

    private var lastAppCpuTicks: Long? = null
    private var lastAppWallMs: Long = 0

    /**
     * 本应用占的 CPU，按「全部核心加起来算 100%」折算。
     *
     * /proc/self/stat 读自己的进程，任何安卓版本上都不会被 SELinux 挡住——
     * 这是 /proc/stat 不可读时唯一还能给出的占用率。
     */
    private fun appLoad(): Float? {
        val ticks = SysFs.text("/proc/self/stat")?.let { ProcParse.selfCpuTicks(it) }
        val wall = SystemClock.elapsedRealtime()
        val prevTicks = lastAppCpuTicks
        val prevWall = lastAppWallMs
        lastAppCpuTicks = ticks
        lastAppWallMs = wall
        if (ticks == null || prevTicks == null) return null

        val wallSec = (wall - prevWall) / 1000.0
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        if (wallSec <= 0) return null
        val cpuSec = (ticks - prevTicks).toDouble() / clockTicks
        return (cpuSec / wallSec / cores * 100).toFloat().coerceIn(0f, 100f)
    }

    private fun cores(
        before: Map<String, ProcParse.CpuTimes>,
        now: Map<String, ProcParse.CpuTimes>,
    ): List<CoreLive> = SysFs.cpuDirs().map { dir ->
        val index = dir.name.removePrefix("cpu").toIntOrNull() ?: -1
        val freq = "${dir.absolutePath}/cpufreq"
        // cpu0 永远在线，内核干脆不给它 online 节点
        val online = SysFs.int("${dir.absolutePath}/online")?.let { it == 1 } ?: (index == 0)
        CoreLive(
            index = index,
            online = online,
            // 离线核心的 scaling_cur_freq 要么读不到、要么是上次下线前的残值，直接当没有
            curKhz = if (online) {
                SysFs.khz("$freq/scaling_cur_freq") ?: SysFs.khz("$freq/cpuinfo_cur_freq")
            } else null,
            minKhz = SysFs.khz("$freq/cpuinfo_min_freq") ?: SysFs.khz("$freq/scaling_min_freq"),
            maxKhz = SysFs.khz("$freq/cpuinfo_max_freq") ?: SysFs.khz("$freq/scaling_max_freq"),
            governor = SysFs.text("$freq/scaling_governor"),
            load = ProcParse.load(before["cpu$index"], now["cpu$index"]),
            model = coreModels[index],
        )
    }

    private fun thermal(): List<ThermalReading> =
        SysFs.children("/sys/class/thermal") { it.name.startsWith("thermal_zone") }
            .mapNotNull { dir ->
                val raw = SysFs.long("${dir.absolutePath}/temp") ?: return@mapNotNull null
                val type = SysFs.text("${dir.absolutePath}/type") ?: dir.name
                ThermalReading(dir.name, type, normalizeTemp(raw))
            }
            // 明显离谱的读数（未接的传感器常年报 -40 或 0）留着没用，只会淹没真正有用的几路
            .filter { it.celsius != null && it.celsius > -30f && it.celsius < 200f }

    /**
     * thermal zone 的单位没有统一标准：绝大多数是毫摄氏度，少数是十分之一度，
     * 还有直接给摄氏度的。按量级猜，猜错也只是小数点位置不对，不会误导量级。
     */
    private fun normalizeTemp(raw: Long): Float = when {
        raw > 10_000 || raw < -10_000 -> raw / 1000f
        raw > 1_000 || raw < -1_000 -> raw / 100f
        raw > 200 || raw < -200 -> raw / 10f
        else -> raw.toFloat()
    }

    private fun thermalStatus(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return when (runCatching { pm.currentThermalStatus }.getOrNull()) {
            PowerManager.THERMAL_STATUS_NONE -> "正常"
            PowerManager.THERMAL_STATUS_LIGHT -> "轻度发热"
            PowerManager.THERMAL_STATUS_MODERATE -> "中度发热"
            PowerManager.THERMAL_STATUS_SEVERE -> "重度发热"
            PowerManager.THERMAL_STATUS_CRITICAL -> "临界"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急降频"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将关机"
            else -> null
        }
    }

    // ---------------- 电池 ----------------

    private fun battery(): BatteryLive? {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (sticky == null && bm == null) return null

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return BatteryLive(
            // 车机大多没有独立电池，这里经常是 false，界面要如实说明而不是显示 0%
            present = sticky?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) ?: false,
            levelPercent = if (level >= 0 && scale > 0) level * 100f / scale else null,
            status = statusText(sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1),
            health = healthText(sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1),
            plugged = pluggedText(sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1),
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE && it != -1 }?.div(10f),
            currentNowUa = bm?.prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAvgUa = bm?.prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = bm?.prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            energyCounterNwh = bm?.let {
                runCatching { it.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }
                    .getOrNull()?.takeIf { v -> v != Long.MIN_VALUE }
            },
            capacityPercent = bm?.prop(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            cycleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                sticky?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it > 0 }
            } else null,
        )
    }

    /** 不支持的属性安卓会返回 `Integer.MIN_VALUE`（有些厂商返回 0），统一当作没有。 */
    private fun BatteryManager.prop(id: Int): Int? =
        runCatching { getIntProperty(id) }.getOrNull()?.takeIf { it != Int.MIN_VALUE && it != 0 }

    private fun statusText(v: Int) = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        else -> HwItem.UNKNOWN
    }

    private fun healthText(v: Int) = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "已损坏"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
        BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
        else -> HwItem.UNKNOWN
    }

    private fun pluggedText(v: Int) = when (v) {
        0 -> "未连接"
        BatteryManager.BATTERY_PLUGGED_AC -> "电源适配器"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
        else -> HwItem.UNKNOWN
    }

    companion object {
        /** 页面可见时的采样间隔。1 秒和车辆轮询同频，看起来不会一个快一个慢。 */
        const val SAMPLE_INTERVAL_MS = 1_000L

        /** 系统时钟每秒的滴答数，算 CPU 时间用；读不到按 Linux 惯例的 100 算。 */
        val clockTicks: Long by lazy {
            runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrNull()?.takeIf { it > 0 } ?: 100L
        }
    }
}
