package com.dicar.vehicle.data.hardware

/**
 * 硬件信息的统一形状：分类 → 段 → 条目。
 *
 * 采集器只管往这个结构里塞东西，界面（[com.dicar.vehicle.ui.screen.HardwareScreen]）
 * 和导出（[HardwareInspector.export]）各自遍历一遍就行，不用为每类硬件写一遍渲染。
 */
data class HwItem(val label: String, val value: String) {
    val unknown: Boolean get() = value == UNKNOWN

    companion object {
        /** 读不到时的占位。和车辆数据的 `N/A` 区分开：那是「车没给」，这是「系统不让读」。 */
        const val UNKNOWN = "未知"
    }
}

/** 卡片里的一段，[subtitle] 为 null 表示紧接着标题、不另起小标题。 */
data class HwBlock(val subtitle: String?, val items: List<HwItem>)

data class HwSection(val category: HwCategory, val blocks: List<HwBlock>) {
    val itemCount: Int get() = blocks.sumOf { it.items.size }
}

/** 顶部筛选条上的分类。顺序就是卡片的默认排布顺序。 */
enum class HwCategory(val label: String, val glyph: String) {
    SOC("处理器", "▤"),
    GPU("图形", "◈"),
    MEMORY("内存", "▥"),
    STORAGE("存储", "▦"),
    DISPLAY("屏幕", "▭"),
    BATTERY("电源", "▮"),
    THERMAL("温度", "▲"),
    SENSOR("传感器", "◉"),
    CAMERA("摄像头", "◎"),
    NETWORK("网络", "◇"),
    AUDIO("音频", "♪"),
    CODEC("编解码", "▷"),
    SYSTEM("系统", "⬡"),
    FEATURE("系统特性", "✦"),
    PROPS("系统属性", "≡"),
}

// ---------------------------------------------------------------------------
// 采集用的小 DSL
// ---------------------------------------------------------------------------

@DslMarker
private annotation class HwDsl

@HwDsl
class BlockScope internal constructor() {
    private val items = mutableListOf<HwItem>()

    /** 值为 null / 空串时记成「未知」，而不是把这一行藏起来——读不到本身也是信息。 */
    fun item(label: String, value: String?) {
        items += HwItem(label, value?.takeIf { it.isNotBlank() } ?: HwItem.UNKNOWN)
    }

    /** 读不到就整行不出现。用于「存在才有意义」的条目，比如某个具体的 sysfs 节点。 */
    fun itemIfPresent(label: String, value: String?) {
        if (!value.isNullOrBlank()) items += HwItem(label, value)
    }

    /** 能力类的布尔：这台机器支不支持某个东西。 */
    fun item(label: String, value: Boolean?) = item(label, value?.let { if (it) "支持" else "不支持" })

    /**
     * 状态类的布尔。和上面的「支持/不支持」分开，否则会写出
     * 「存在电池 支持」「检测到 su 不支持」这种读不通的行。
     */
    fun flag(label: String, value: Boolean?, yes: String = "是", no: String = "否") =
        item(label, value?.let { if (it) yes else no })

    internal fun build(subtitle: String?) = HwBlock(subtitle, items.toList())

    internal fun isEmpty() = items.isEmpty()
}

@HwDsl
class SectionScope internal constructor() {
    private val blocks = mutableListOf<HwBlock>()

    fun block(subtitle: String? = null, body: BlockScope.() -> Unit) {
        val scope = BlockScope().apply(body)
        if (!scope.isEmpty()) blocks += scope.build(subtitle)
    }

    internal fun build(category: HwCategory) = HwSection(category, blocks.toList())
}

/** 采集器的入口：`section(HwCategory.SOC) { block("核心") { item("型号", …) } }`。 */
fun section(category: HwCategory, body: SectionScope.() -> Unit): HwSection =
    SectionScope().apply(body).build(category)

/**
 * 按关键词过滤。标签、值、小标题、分类名任一命中就留下。
 *
 * 命中小标题时整段保留——搜「温区」就该看到整段温区，而不是只剩标题那一行。
 * 空关键词原样返回；过滤后为空的分类直接丢掉，不留空卡片。
 */
fun filterSections(sections: List<HwSection>, query: String): List<HwSection> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return sections
    return sections.mapNotNull { s ->
        if (s.category.label.lowercase().contains(q)) return@mapNotNull s
        val blocks = s.blocks.mapNotNull { b ->
            if (b.subtitle?.lowercase()?.contains(q) == true) return@mapNotNull b
            b.items
                .filter { it.label.lowercase().contains(q) || it.value.lowercase().contains(q) }
                .takeIf { it.isNotEmpty() }
                ?.let { HwBlock(b.subtitle, it) }
        }
        if (blocks.isEmpty()) null else HwSection(s.category, blocks)
    }
}
