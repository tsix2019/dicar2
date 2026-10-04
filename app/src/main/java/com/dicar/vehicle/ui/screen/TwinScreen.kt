package com.dicar.vehicle.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.components.ArcGauge
import com.dicar.vehicle.ui.components.CarDiagram
import com.dicar.vehicle.ui.components.ChartSeries
import com.dicar.vehicle.ui.components.DataCard
import com.dicar.vehicle.ui.components.HistoryChart
import com.dicar.vehicle.ui.components.MetricPair
import com.dicar.vehicle.ui.components.MetricRow
import com.dicar.vehicle.util.Format

/**
 * 孪生主页：左边车辆俯视图（门窗/轮胎/雷达/转向灯实时联动），
 * 右边四块仪表 + 趋势曲线 + 关键读数。窄屏时自动改为上下排布。
 */
@Composable
fun TwinPane(
    state: VehicleState,
    history: List<MainViewModel.HistorySample>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth > 720.dp
        if (wide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CarPanel(state, Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier
                        .weight(1.25f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GaugePanel(state, singleRow = true)
                    TrendPanel(state, history)
                    KeyReadouts(state)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GaugePanel(state, singleRow = false)
                CarPanel(state, Modifier.fillMaxWidth().aspectRatio(0.8f))
                TrendPanel(state, history)
                KeyReadouts(state)
            }
        }
    }
}

@Composable
private fun CarPanel(state: VehicleState, modifier: Modifier = Modifier) {
    DataCard(title = "车辆状态", modifier = modifier) {
        CarDiagram(state, Modifier.fillMaxWidth().weight(1f, fill = true))
        MetricPair(
            "方向盘转角", Format.num(state.steeringAngle, 1, "°"),
            "天窗", Format.num(state.sunroofPercent, unit = "%"),
        )
        MetricRow(
            "泊车雷达",
            when {
                state.radar.reverseSwitchOn == null && !state.radar.hasAnyReading -> Format.NA
                state.radar.nearest != null -> "障碍 ${state.radar.nearest} 档"
                else -> "无障碍"
            },
        )
    }
}

/** [singleRow] = 宽屏四块并排，窄屏 2×2。 */
@Composable
private fun GaugePanel(state: VehicleState, singleRow: Boolean) {
    val scheme = MaterialTheme.colorScheme

    @Composable
    fun speed(m: Modifier) = ArcGauge(
        value = state.speed, min = 0f, max = 200f,
        label = "车速", unit = "km/h", modifier = m,
    )

    @Composable
    fun rpm(m: Modifier) = ArcGauge(
        value = state.displayRpm?.toFloat(), min = 0f, max = 12000f,
        label = state.displayRpmLabel, unit = "rpm", modifier = m, color = scheme.tertiary,
    )

    // 功率有正负：0 放在中间，回收时向左画成另一种颜色
    @Composable
    fun power(m: Modifier) = ArcGauge(
        value = state.batteryPower ?: state.power, min = -100f, max = 200f, zeroAt = 0f,
        label = "功率", unit = "kW", decimals = 1, modifier = m,
    )

    @Composable
    fun soc(m: Modifier) = ArcGauge(
        value = state.soc, min = 0f, max = 100f,
        label = "电量", unit = "%", decimals = 1, modifier = m, color = scheme.secondary,
    )

    DataCard(title = "实时仪表") {
        if (singleRow) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speed(Modifier.weight(1f)); rpm(Modifier.weight(1f))
                power(Modifier.weight(1f)); soc(Modifier.weight(1f))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                speed(Modifier.weight(1f)); rpm(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                power(Modifier.weight(1f)); soc(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrendPanel(state: VehicleState, history: List<MainViewModel.HistorySample>) {
    DataCard(title = "趋势（近 3 分钟）") {
        HistoryChart(
            series = listOf(
                ChartSeries("车速", "km/h", MaterialTheme.colorScheme.primary, history.map { it.speed }),
                ChartSeries("功率", "kW", MaterialTheme.colorScheme.tertiary, history.map { it.power }, baseline = 0f),
                ChartSeries("电量", "%", MaterialTheme.colorScheme.secondary, history.map { it.soc }),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        MetricRow("能量流", state.energyFlow ?: Format.NA)
    }
}

@Composable
private fun KeyReadouts(state: VehicleState) {
    DataCard(title = "关键读数") {
        MetricPair(
            "电续航", Format.num(state.remainRangeElec, unit = "km"),
            "油续航", Format.num(state.remainRangeFuel, unit = "km"),
        )
        MetricPair(
            "车内温度", Format.num(state.insideTemp, 1, "℃"),
            "车外温度", Format.num(state.outsideTemp, 1, "℃"),
        )
        MetricPair(
            "工作模式", Format.text(state.workMode),
            "驾驶模式", Format.text(state.driveMode),
        )
        MetricPair(
            "总里程", Format.num(state.totalMileage, unit = "km"),
            "12V 电压", Format.num(state.voltage12V, 1, "V"),
        )
    }
}
