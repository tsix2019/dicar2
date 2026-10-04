package com.dicar.vehicle.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.components.Car3dView
import com.dicar.vehicle.ui.components.CarDiagram
import com.dicar.vehicle.ui.components.ChartCanvas
import com.dicar.vehicle.ui.components.ChartLegend
import com.dicar.vehicle.ui.components.ChartSeries
import com.dicar.vehicle.ui.components.DataCard
import com.dicar.vehicle.ui.components.DialGauge
import com.dicar.vehicle.ui.components.HeroNumber
import com.dicar.vehicle.ui.components.LabeledBar
import com.dicar.vehicle.ui.components.SignedBar
import com.dicar.vehicle.ui.components.SignedBarScale
import com.dicar.vehicle.ui.components.StatusPill
import com.dicar.vehicle.util.Format

/**
 * 孪生主页。横屏三栏：左转速大表盘、中车速 + 俯视车况图、右功率与电量油量；
 * 底部一条趋势曲线 + 状态胶囊。竖屏改为上下堆叠，内容不删减。
 *
 * 中间一栏刻意不套卡片——车速和车辆图是这一页的主角，直接落在背景上，
 * 两侧的白卡片把视线收拢到中间。
 */
@Composable
fun TwinPane(
    state: VehicleState,
    history: List<MainViewModel.HistorySample>,
    modifier: Modifier = Modifier,
    use3d: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth > 720.dp
        if (wide) {
            Column(
                Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RpmCard(state, Modifier.weight(1f).fillMaxHeight())
                    SpeedAndCar(state, use3d, Modifier.weight(1.25f).fillMaxHeight())
                    PowerCardPane(state, Modifier.weight(1f).fillMaxHeight())
                }
                TrendStrip(state, history)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 窄屏给车辆图一个固定高度：按宽高比算的话，600dp 宽的屏上车会撑到
                // 700dp 高，一屏只剩一台车
                SpeedAndCar(state, use3d, Modifier.fillMaxWidth().height(380.dp))
                RpmCard(state, Modifier.fillMaxWidth().height(260.dp))
                PowerCardPane(state, Modifier.fillMaxWidth())
                TrendStrip(state, history)
            }
        }
    }
}

/** 左栏：转速大表盘。发动机没介入时自动改显电机转速，量程和红线一起换。 */
@Composable
private fun RpmCard(state: VehicleState, modifier: Modifier = Modifier) {
    val isEngine = state.engineRpm != null
    DataCard(title = state.displayRpmLabel, modifier = modifier) {
        DialGauge(
            value = state.displayRpm?.toFloat(),
            max = if (isEngine) ENGINE_MAX_RPM else MOTOR_MAX_RPM,
            unit = "rpm",
            redlineFrom = if (isEngine) ENGINE_REDLINE_RPM else null,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Text(
            text = state.energyFlow ?: Format.text(state.workMode),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** 中栏：大号车速 + 车况图，不套卡片。[use3d] 决定用 3D 车模还是 2D 俯视图。 */
@Composable
private fun SpeedAndCar(state: VehicleState, use3d: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HeroNumber(state.speed, "km/h", size = 64.sp)
        Spacer(Modifier.height(4.dp))
        val carModifier = Modifier.fillMaxWidth().weight(1f)
        if (use3d) Car3dView(state, carModifier) else CarDiagram(state, carModifier)
    }
}

/** 右栏：驱动/回收功率 + 电量油量 + 续航。 */
@Composable
private fun PowerCardPane(state: VehicleState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val power = state.batteryPower ?: state.power
    DataCard(title = "驱动功率", modifier = modifier) {
        HeroNumber(power, "kW", size = 40.sp, signed = true)
        SignedBar(power, maxPositive = MAX_DRIVE_KW, maxNegative = MAX_REGEN_KW)
        SignedBarScale("回收", "0", "驱动")
        Spacer(Modifier.height(2.dp))
        LabeledBar("电量", state.soc, scheme.secondary)
        LabeledBar("油量", state.fuelPercent, scheme.tertiary)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            val style = MaterialTheme.typography.labelLarge
            Text(
                "纯电 ${Format.num(state.remainRangeElec, unit = "km")}",
                style = style,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "综合 ${Format.num(combinedRange(state), unit = "km")}",
                style = style,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 综合续航 = 纯电 + 燃油。任一侧读不到就不硬凑，返回 null 显示 N/A。 */
private fun combinedRange(state: VehicleState): Int? {
    val e = state.remainRangeElec
    val f = state.remainRangeFuel
    return when {
        e != null && f != null -> e + f
        e != null && state.fuelPercent == null -> e   // 纯电车没有油箱，综合就等于纯电
        else -> null
    }
}

/** 底栏：图例竖排在左、曲线占中间、状态胶囊靠右。 */
@Composable
private fun TrendStrip(state: VehicleState, history: List<MainViewModel.HistorySample>) {
    val scheme = MaterialTheme.colorScheme
    // 发动机和电机各画各的，有哪个画哪个。不要再画一条「主转速」——
    // 有发动机时主转速就等于发动机转速，两条线会完全重合，等于白画一条。
    val series = buildList {
        add(ChartSeries("功率", "kW", scheme.secondary, history.map { it.power }, baseline = 0f))
        if (history.any { it.engineRpm != null }) {
            add(ChartSeries("发动机", "rpm", scheme.error, history.map { it.engineRpm }, baseline = 0f))
        }
        if (history.any { it.motorRpm != null }) {
            add(ChartSeries("电机", "rpm", scheme.primary, history.map { it.motorRpm }, baseline = 0f))
        }
        // 两个转速都读不到时，退回主转速，至少别让图里只剩一条功率线
        if (size == 1 && history.any { it.rpm != null }) {
            add(ChartSeries("转速", "rpm", scheme.primary, history.map { it.rpm }, baseline = 0f))
        }
    }
    DataCard(title = null) {
        Row(
            Modifier.fillMaxWidth().height(62.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartLegend(series, vertical = true)
            ChartCanvas(series, Modifier.weight(1f).fillMaxHeight())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(acSummary(state))
                val tyre = tyreSummary(state)
                StatusPill(tyre.first, alert = tyre.second)
                val door = doorSummary(state)
                StatusPill(door.first, alert = door.second)
            }
        }
    }
}

private fun acSummary(state: VehicleState): String = when (state.acOn) {
    null -> "空调 ${Format.NA}"
    false -> "空调 关"
    true -> buildString {
        append("空调 ")
        append(Format.num(state.acTempDriver, 1, "℃"))
        if (state.acAuto == true) append(" 自动")
    }
}

/** 返回 文案 to 是否告警。四轮都读不到就显示 N/A，不假装正常。 */
private fun tyreSummary(state: VehicleState): Pair<String, Boolean> {
    val values = state.tirePressure.toList().filterNotNull()
    if (values.isEmpty()) return "胎压 ${Format.NA}" to false
    val bad = values.any { it < TYRE_MIN_KPA || it > TYRE_MAX_KPA }
    return if (bad) "胎压异常" to true else "胎压正常" to false
}

private fun doorSummary(state: VehicleState): Pair<String, Boolean> {
    val d = state.doors
    val all = listOf(d.fl, d.fr, d.rl, d.rr, d.hood, d.trunk)
    if (all.all { it == null }) return "车门 ${Format.NA}" to false
    return if (all.any { it == true }) "有门未关" to true else "车门已关" to false
}

private const val ENGINE_MAX_RPM = 6000f
private const val ENGINE_REDLINE_RPM = 5000f
private const val MOTOR_MAX_RPM = 12000f
private const val MAX_DRIVE_KW = 200f
private const val MAX_REGEN_KW = 100f
private const val TYRE_MIN_KPA = 190f
private const val TYRE_MAX_KPA = 300f
