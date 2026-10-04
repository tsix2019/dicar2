package com.dicar.vehicle.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dicar.vehicle.data.hardware.HwCategory
import com.dicar.vehicle.data.hardware.HwFormat
import com.dicar.vehicle.data.hardware.HwItem
import com.dicar.vehicle.data.hardware.HwSection
import com.dicar.vehicle.data.hardware.LiveStats
import com.dicar.vehicle.data.hardware.batterySection
import com.dicar.vehicle.data.hardware.filterSections
import com.dicar.vehicle.data.hardware.thermalSection
import com.dicar.vehicle.ui.HardwareExportState
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.components.CoreRow
import com.dicar.vehicle.ui.components.CountBadge
import com.dicar.vehicle.ui.components.DataCard
import com.dicar.vehicle.ui.components.HwRow
import com.dicar.vehicle.ui.components.HwSubtitle
import com.dicar.vehicle.ui.components.ThinBar
import com.dicar.vehicle.ui.components.UsageTile
import com.dicar.vehicle.ui.components.loadColor
import com.dicar.vehicle.ui.components.tempColor

/**
 * 硬件信息页。
 *
 * 分两层：顶部是每秒刷新的实时指标（占用率、频率、温度、内存），
 * 下面是只采一次的静态清单。静态那部分条目成千上万，所以配了搜索和分类过滤。
 */
@Composable
fun HardwareScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val sections by viewModel.hardware.collectAsStateWithLifecycle()
    val loading by viewModel.hardwareLoading.collectAsStateWithLifecycle()
    val export by viewModel.hardwareExport.collectAsStateWithLifecycle()
    // 冷流，离开这一页就自动停止采样，不会在后台空转
    val liveFlow = remember { viewModel.liveStats() }
    val live by liveFlow.collectAsStateWithLifecycle(initialValue = null)

    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<HwCategory?>(null) }

    LaunchedEffect(Unit) { viewModel.loadHardware() }

    // 温度和电池这两张卡直接由实时采样生成，所以每秒重建；其余分类是同一批对象，
    // 内容没变就不会触发重组，整张列表不会跟着每秒重画
    val all = remember(sections, live) {
        if (sections.isEmpty()) emptyList()
        else {
            val derived = live?.let { listOf(thermalSection(it), batterySection(it.battery)) }.orEmpty()
            (sections + derived).sortedBy { it.category.ordinal }
        }
    }
    // 分成两步：关键词过滤的结果同时喂给分类芯片（让芯片上的数字就是「这一类命中几条」）
    // 和列表；分类只在列表这一侧再收一次
    val afterQuery = remember(all, query) { filterSections(all, query) }
    val visible = remember(afterQuery, category) {
        afterQuery.filter { category == null || it.category == category }
    }
    // 选中的分类被搜索结果排除掉时自动松开，否则会停在一个永远空着的页面上
    LaunchedEffect(afterQuery) {
        if (category != null && afterQuery.none { it.category == category }) category = null
    }

    Column(modifier.fillMaxSize()) {
        // 关键读数常驻一条窄带，详细的实时卡片跟着列表一起滚——
        // 车机屏幕只有 1200 高，实时面板钉死会把下面的清单挤得只剩一条缝
        LiveStrip(live)
        Toolbar(
            query = query,
            onQuery = { query = it },
            category = category,
            onCategory = { category = it },
            sections = afterQuery,
            loading = loading,
            onRefresh = { viewModel.loadHardware(force = true) },
            onExport = { viewModel.exportHardware(all) },
        )
        when {
            loading && sections.isEmpty() -> Loading()
            visible.isEmpty() -> Empty(query)
            else -> SectionGrid(
                sections = visible,
                filtering = query.isNotBlank(),
                // 搜索或选了分类时不再插实时面板：它不是搜索结果的一部分
                live = live.takeIf { query.isBlank() && category == null },
            )
        }
    }

    HardwareExportDialog(export, viewModel::setExportRedact, viewModel::dismissHardwareExport)
}

// ---------------------------------------------------------------------------
// 实时面板
// ---------------------------------------------------------------------------

/** 常驻窄带：只放最该一眼看到的几个数，一行放完。 */
@Composable
private fun LiveStrip(live: LiveStats?) {
    val blocked = live != null && !live.systemLoadAvailable
    val load = if (blocked) live?.appLoad else live?.totalLoad
    val memPercent = live?.let { s ->
        val used = s.memUsed ?: return@let null
        val total = s.memTotal?.takeIf { it > 0 } ?: return@let null
        used.toFloat() / total * 100f
    }
    val hottest = live?.thermal?.mapNotNull { it.celsius }?.maxOrNull()

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveChip(if (blocked) "CPU（本应用）" else "CPU", HwFormat.percent(load), loadColor(load))
        LiveChip("内存", HwFormat.percent(memPercent), MaterialTheme.colorScheme.secondary)
        LiveChip("内存用量", HwFormat.usage(live?.memUsed, live?.memTotal))
        LiveChip("GPU", HwFormat.hz(live?.gpuHz))
        LiveChip("最高温", HwFormat.celsius(hottest), tempColor(hottest))
        LiveChip("开机", HwFormat.duration(live?.uptimeMs))
    }
}

@Composable
private fun LiveChip(label: String, value: String, color: androidx.compose.ui.graphics.Color? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                value == HwItem.UNKNOWN -> MaterialTheme.colorScheme.outline
                color != null -> color
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun LivePanel(live: LiveStats?) {
    BoxWithConstraints(Modifier.padding(bottom = 4.dp)) {
        val wide = maxWidth > 900.dp
        val cores = live?.cores.orEmpty()
        val hottest = live?.thermal?.mapNotNull { it.celsius }?.maxOrNull()

        // /proc/stat 从安卓 11 起不再对普通应用开放，全局占用率读不到是常态而不是故障。
        // 这时退而显示本应用自己的占用（/proc/self/stat 永远读得到），并说明原因
        val blocked = live != null && !live.systemLoadAvailable

        @Composable
        fun cpuTile(modifier: Modifier) = DataCard("处理器实时", modifier) {
            UsageTile(
                label = if (blocked) "本应用占用" else "总占用",
                headline = HwFormat.percent(if (blocked) live?.appLoad else live?.totalLoad),
                caption = buildString {
                    append("${cores.count { it.online }} / ${cores.size} 核在线")
                    if (blocked) append(" · 系统禁止应用读取 /proc/stat，无法统计全局占用")
                },
                fraction = (if (blocked) live?.appLoad else live?.totalLoad)?.div(100f),
            )
            if (cores.isNotEmpty()) {
                HorizontalDivider()
                // 核心多的时候列表会很长，但这正是看大小核调度的地方，不折叠
                cores.forEach { CoreRow(it) }
            }
        }

        @Composable
        fun memTile(modifier: Modifier) = DataCard("内存实时", modifier) {
            UsageTile(
                label = "内存占用",
                headline = HwFormat.percent(
                    live?.let { s ->
                        val used = s.memUsed ?: return@let null
                        val total = s.memTotal ?: return@let null
                        used.toFloat() / total * 100f
                    }
                ),
                caption = HwFormat.usage(live?.memUsed, live?.memTotal),
                fraction = live?.let { s ->
                    val used = s.memUsed ?: return@let null
                    val total = s.memTotal?.takeIf { it > 0 } ?: return@let null
                    used.toFloat() / total
                },
                color = MaterialTheme.colorScheme.secondary,
            )
            HwRow("可用", HwFormat.bytes(live?.memAvailable))
            HwRow("页缓存", HwFormat.bytes(live?.memCached))
            HwRow("Swap 已用", HwFormat.bytes(
                live?.let { s ->
                    val t = s.swapTotal ?: return@let null
                    val f = s.swapFree ?: return@let null
                    t - f
                }
            ))
            HwRow("本应用堆", HwFormat.usage(live?.javaHeapUsed, live?.javaHeapMax))
            HwRow("本应用原生内存", HwFormat.bytes(live?.nativeHeapUsed))
            HwRow("本应用文件句柄", live?.openFds?.toString() ?: HwItem.UNKNOWN)
            if (live?.lowMemory == true) {
                HwRow("警告", "系统报告内存紧张")
            }
        }

        @Composable
        fun miscTile(modifier: Modifier) = DataCard("图形 · 温度 · 电源", modifier) {
            HwRow("GPU 频率", HwFormat.hz(live?.gpuHz))
            HwRow("热状态", live?.thermalStatus ?: HwItem.UNKNOWN)
            UsageTile(
                label = "最高温度",
                headline = HwFormat.celsius(hottest),
                caption = live?.thermal?.maxByOrNull { it.celsius ?: -999f }?.type ?: "无温度传感器可读",
                // 以 100℃ 为满格：车机 SoC 到这个温度肯定已经在保护降频了
                fraction = hottest?.div(100f),
                color = tempColor(hottest),
            )
            HorizontalDivider()
            val battery = live?.battery
            if (battery?.present == true) {
                HwRow("电量", HwFormat.percent(battery.levelPercent))
                HwRow("状态", battery.status)
                HwRow("电压", battery.voltageMv?.let { "$it mV" } ?: HwItem.UNKNOWN)
                HwRow("电池温度", HwFormat.celsius(battery.temperature))
            } else {
                // 车机常态：直接吃整车 12V 电，没有自己的电池
                HwRow("电池", "无独立电池（由车辆供电）")
                HwRow("供电", battery?.plugged ?: HwItem.UNKNOWN)
            }
            HorizontalDivider()
            HwRow("开机时长", HwFormat.duration(live?.uptimeMs))
            HwRow("深度睡眠", HwFormat.duration(live?.let { it.uptimeMs - it.awakeMs }))
        }

        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cpuTile(Modifier.weight(1.3f))
                memTile(Modifier.weight(1f))
                miscTile(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cpuTile(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    memTile(Modifier.weight(1f))
                    miscTile(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工具条
// ---------------------------------------------------------------------------

@Composable
private fun Toolbar(
    query: String,
    onQuery: (String) -> Unit,
    category: HwCategory?,
    onCategory: (HwCategory?) -> Unit,
    sections: List<HwSection>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                label = { Text("搜索（型号、属性名、数值都能搜）") },
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("清除") }
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onRefresh, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("重新采集")
            }
            OutlinedButton(onClick = onExport, enabled = sections.isNotEmpty()) { Text("导出") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val total = sections.sumOf { it.itemCount }
            CategoryChip("全部（$total）", category == null) { onCategory(null) }
            sections.forEach { s ->
                CategoryChip(
                    "${s.category.glyph} ${s.category.label}（${s.itemCount}）",
                    category == s.category,
                ) { onCategory(if (category == s.category) null else s.category) }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        // 和导航栏一样，选中态用强调蓝；绿色在这套配色里专表电量
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

// ---------------------------------------------------------------------------
// 清单
// ---------------------------------------------------------------------------

@Composable
private fun SectionGrid(sections: List<HwSection>, filtering: Boolean, live: LiveStats?) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 400.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (live != null) {
            item(key = "live", span = StaggeredGridItemSpan.FullLine) { LivePanel(live) }
        }
        items(sections, key = { it.category.name }) { s -> SectionCard(s, filtering) }
    }
}

@Composable
private fun SectionCard(section: HwSection, filtering: Boolean) {
    DataCard(
        title = "${section.category.glyph} ${section.category.label}",
        trailing = { CountBadge(section.itemCount) },
    ) {
        section.blocks.forEachIndexed { index, block ->
            if (index > 0) HorizontalDivider()
            block.subtitle?.let { HwSubtitle(it) }
            // 搜索时逐条都要看到；不搜索时长列表（系统属性有上千条）先截断，
            // 免得一张卡片把整个列表撑爆
            val cap = if (filtering) Int.MAX_VALUE else MAX_ROWS
            block.items.take(cap).forEach { HwRow(it.label, it.value) }
            if (block.items.size > cap) {
                HwRow("…", "还有 ${block.items.size - cap} 条，用上方搜索或导出查看")
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("正在枚举硬件…", style = MaterialTheme.typography.bodyMedium)
            Text(
                "首次采集要枚举全部编解码器和系统属性，车机上可能要几秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Empty(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (query.isBlank()) "没有采集到硬件信息" else "没有匹配「$query」的条目",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

@Composable
private fun HardwareExportDialog(
    state: HardwareExportState,
    onRedactChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is HardwareExportState.Done) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("硬件信息已导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    state.filePath?.let { "已保存：$it" } ?: "写文件失败，只能在下方查看",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("隐去设备标识", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "抹掉序列号、MAC、IP、Android ID 这类能定位到这台车机的内容。" +
                                "默认开启——导出文件通常是要发给别人看的。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = state.redact, onCheckedChange = onRedactChange)
                }
                HorizontalDivider()
                SelectionContainer {
                    Text(
                        state.text.take(MAX_PREVIEW_CHARS),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
    )
}

/** 不搜索时单段最多显示多少行。系统属性那一段常有上千条。 */
private const val MAX_ROWS = 40

/** 弹窗里的预览上限，完整内容在文件里。 */
private const val MAX_PREVIEW_CHARS = 20_000
