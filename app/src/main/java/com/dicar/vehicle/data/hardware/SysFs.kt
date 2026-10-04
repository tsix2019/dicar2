package com.dicar.vehicle.data.hardware

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 读 `/proc`、`/sys` 和系统属性。
 *
 * 这一层的原则是**绝不抛异常**：安卓每代都在收紧 app 域对 sysfs 的访问，
 * 同一个节点在这台车机上能读、换一台就 `EACCES`。读不到就返回 null，
 * 由上层显示「未知」，不能让一个节点读失败拖垮整张硬件页。
 */
object SysFs {

    fun text(path: String): String? = runCatching {
        val f = File(path)
        if (f.canRead()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    fun long(path: String): Long? = text(path)?.substringBefore('\n')?.trim()?.toLongOrNull()

    fun int(path: String): Int? = long(path)?.toInt()

    /**
     * cpufreq 节点，单位 kHz。
     *
     * 低于 10 MHz 的值一律当读不到：有的内核（模拟器的 dummy 驱动、部分车机的阉割
     * cpufreq）在这些节点里放的是频率表的**序号**而不是真实频率，照单全收会显示出
     * 「1 kHz」这种明显不可能的数。
     */
    fun khz(path: String): Long? = long(path)?.takeIf { it >= MIN_PLAUSIBLE_KHZ }

    /** 10 MHz。再慢的 CPU 也跑不出安卓。 */
    const val MIN_PLAUSIBLE_KHZ = 10_000L

    /** 目录下按名字排序的子项；目录不存在或不可读时返回空表。 */
    fun children(path: String, filter: (File) -> Boolean = { true }): List<File> = runCatching {
        File(path).listFiles()?.filter(filter)?.sortedBy { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    /** `/sys/devices/system/cpu/cpu0`、`cpu1`…… 按编号排序（`cpu10` 要排在 `cpu9` 后面）。 */
    fun cpuDirs(): List<File> =
        children(CPU_ROOT) { it.isDirectory && CPU_DIR.matches(it.name) }
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }

    /**
     * 执行一条命令并收集 stdout。只用于 `getprop` 这类系统自带的只读工具。
     * 超时会杀掉进程——车机上见过 `getprop` 因为某个属性服务卡住而挂住的情况。
     */
    fun exec(vararg command: String, timeoutMs: Long = 2_000): String? = runCatching {
        // 故意**不**把 stderr 并进 stdout：命令失败时（SELinux 挡住、工具不存在）
        // 错误文字会被当成结果显示出去，比如把「Permission denied」当作 SELinux 的状态
        val process = ProcessBuilder(*command).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.close()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroy()
        output.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * `android.os.SystemProperties.get`。这是隐藏 API，安卓 9 起在灰名单上，
     * 但 `get(String)` 至今仍可用；万一哪天被封，反射失败就返回 null。
     */
    fun prop(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 按顺序试一串属性名，返回第一个有值的。不同厂商的键名五花八门。 */
    fun firstProp(vararg keys: String): String? = keys.firstNotNullOfOrNull { prop(it) }

    /** `getprop` 的全量输出，解析成键值对。拿不到就返回空表（上层会退回到白名单）。 */
    fun allProps(): Map<String, String> {
        val raw = exec("getprop") ?: return emptyMap()
        return raw.lineSequence().mapNotNull { line ->
            // 格式固定是 [key]: [value]
            val m = PROP_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val value = m.groupValues[2]
            if (value.isEmpty()) null else m.groupValues[1] to value
        }.toMap()
    }

    /**
     * GPU 当前频率节点。各家路径和单位都不一样，第二项是换算到 Hz 的倍数。
     * 按顺序试，用第一个读得到的。
     */
    val GPU_FREQ_NODES = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk" to 1L,
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq" to 1L,
        "/sys/class/devfreq/gpufreq/cur_freq" to 1L,
        "/sys/kernel/gpu/gpu_clock" to 1_000_000L,
        "/sys/class/misc/mali0/device/clock" to 1_000_000L,
    )

    /** 返回 Hz，读不到返回 null。 */
    fun gpuHz(): Long? = GPU_FREQ_NODES.firstNotNullOfOrNull { (path, scale) ->
        long(path)?.takeIf { it > 0 }?.times(scale)
    }

    /** 实际存在的那个频率节点路径，用于在界面上告诉用户数据是从哪读的。 */
    fun gpuFreqNode(): String? = GPU_FREQ_NODES.firstOrNull { long(it.first) != null }?.first

    private const val CPU_ROOT = "/sys/devices/system/cpu"
    private val CPU_DIR = Regex("cpu\\d+")
    private val PROP_LINE = Regex("""\[([^]]+)]: \[(.*)]""")
}
