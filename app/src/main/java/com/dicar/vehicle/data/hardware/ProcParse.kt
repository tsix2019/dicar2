package com.dicar.vehicle.data.hardware

/**
 * `/proc` 下几个文本文件的解析。纯函数，不碰文件系统，便于单测。
 *
 * 这些文件的格式几十年没变过，但**能不能读到**在不同安卓版本上差别很大
 * （SELinux 对 app 域的限制逐代收紧），所以每个解析函数都要容忍残缺输入。
 */
object ProcParse {

    // ---------------- /proc/cpuinfo ----------------

    data class CpuIdentity(
        /** `Hardware` 行，通常是平台代号，如 `Qualcomm Technologies, Inc SM6125`。 */
        val hardware: String?,
        /** `CPU implementer`，0x41 = ARM、0x51 = 高通…… */
        val implementer: Int?,
        /** `CPU part`，和 implementer 一起才能定位到具体核心。 */
        val part: Int?,
        val variant: Int?,
        val revision: Int?,
        /** `Features` / `flags` 行拆开后的指令集扩展。 */
        val features: List<String>,
        /** 文件里出现了几个 `processor` 段。 */
        val processorCount: Int,
        /** x86 模拟器上才有的 `model name`。 */
        val modelName: String?,
        val serialPresent: Boolean,
    )

    fun cpuIdentity(text: String): CpuIdentity {
        val fields = mutableMapOf<String, String>()
        var processors = 0
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx < 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            if (key == "processor") {
                processors++
                continue
            }
            // 同名字段（每个核心都会重复一遍）保留第一次出现的值
            fields.putIfAbsent(key, value)
        }
        val featureLine = fields["features"] ?: fields["flags"]
        return CpuIdentity(
            hardware = fields["hardware"],
            implementer = hex(fields["cpu implementer"]),
            part = hex(fields["cpu part"]),
            variant = hex(fields["cpu variant"]),
            revision = fields["cpu revision"]?.toIntOrNull(),
            features = featureLine?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
            processorCount = processors,
            modelName = fields["model name"],
            serialPresent = fields.containsKey("serial"),
        )
    }

    /** 单个 `processor` 段里的核心身份。big.LITTLE 上每段的 part 不一样。 */
    data class CoreId(val index: Int, val implementer: Int?, val part: Int?)

    /**
     * 逐段解析每个核心的 implementer/part。
     * 只有这样才能分出大小核——按最高频率分组不可靠，有些芯片大小核标称频率相同。
     */
    fun cpuCores(text: String): List<CoreId> {
        val result = mutableListOf<CoreId>()
        var index: Int? = null
        var implementer: Int? = null
        var part: Int? = null
        fun flush() {
            index?.let { result += CoreId(it, implementer, part) }
            index = null; implementer = null; part = null
        }
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx < 0) continue
            when (line.substring(0, idx).trim().lowercase()) {
                // processor 行既是新段的开始，也是上一段的结束
                "processor" -> {
                    flush()
                    index = line.substring(idx + 1).trim().toIntOrNull()
                }
                "cpu implementer" -> implementer = hex(line.substring(idx + 1))
                "cpu part" -> part = hex(line.substring(idx + 1))
            }
        }
        flush()
        return result
    }

    private fun hex(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
        return s.toIntOrNull(16)
    }

    // ---------------- /proc/meminfo ----------------

    /** 返回字节数。meminfo 里的单位是 kB，这里统一换算掉，免得各处记错。 */
    fun memInfo(text: String): Map<String, Long> = buildMap {
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx < 0) continue
            val key = line.substring(0, idx).trim()
            val rest = line.substring(idx + 1).trim()
            val number = rest.substringBefore(' ').toLongOrNull() ?: continue
            put(key, if (rest.endsWith("kB")) number * 1024 else number)
        }
    }

    // ---------------- /proc/stat ----------------

    /** 一个 CPU（或全部 CPU 汇总）的累计时间片。 */
    data class CpuTimes(val total: Long, val idle: Long)

    /**
     * 解析出 `cpu`（下标 0 是汇总）和各个 `cpuN`。
     * 注意离线的核心在 /proc/stat 里**直接不出现**，所以返回的是「名字 → 时间片」，
     * 不能按下标当成核心编号。
     */
    fun cpuTimes(text: String): Map<String, CpuTimes> = buildMap {
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) continue
            // user nice system idle iowait irq softirq steal …
            val idle = values[3] + (values.getOrNull(4) ?: 0)
            put(parts[0], CpuTimes(values.sum(), idle))
        }
    }

    /**
     * 两次采样之间的占用率。分母为 0（采样间隔太短、或者核心刚上线）时返回 null，
     * 而不是 0——「这一刻测不出来」和「空闲」是两回事。
     */
    fun load(before: CpuTimes?, after: CpuTimes?): Float? {
        if (before == null || after == null) return null
        val totalDelta = after.total - before.total
        val idleDelta = after.idle - before.idle
        if (totalDelta <= 0) return null
        return ((totalDelta - idleDelta).toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    // ---------------- /proc/self/stat ----------------

    /**
     * 本进程用掉的 CPU 时间（utime + stime，单位是时钟滴答）。
     *
     * 第 2 个字段是进程名，带括号而且**可以包含空格和括号**，所以不能直接按空格切。
     * 必须从最后一个右括号之后开始数，这是解析这个文件最容易踩的坑。
     */
    fun selfCpuTicks(text: String): Long? {
        val tail = text.substringAfterLast(") ", missingDelimiterValue = "")
        if (tail.isEmpty()) return null
        val fields = tail.trim().split(Regex("\\s+"))
        // tail 的第 1 项是 state（整个文件的第 3 个字段），所以 utime(14)/stime(15) 在这里是下标 11/12
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    // ---------------- /proc/mounts ----------------

    data class Mount(val device: String, val point: String, val fsType: String, val options: List<String>)

    fun mounts(text: String): List<Mount> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(' ')
        if (p.size < 4) null else Mount(p[0], p[1], p[2], p[3].split(','))
    }.toList()

    // ---------------- /proc/version ----------------

    /** 内核版本串很长，首页只放前面的版本号部分，完整串单独一行。 */
    fun kernelVersion(text: String): String? =
        Regex("""Linux version (\S+)""").find(text)?.groupValues?.get(1)
}
