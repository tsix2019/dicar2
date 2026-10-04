package com.dicar.vehicle.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.data.FloatingBlock
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.components.ArcGauge
import com.dicar.vehicle.ui.components.CarDiagram
import com.dicar.vehicle.ui.components.ChartSeries
import com.dicar.vehicle.ui.components.HistoryChart
import com.dicar.vehicle.ui.components.MetricRow
import com.dicar.vehicle.util.Format

/**
 * 悬浮窗内容。显示哪些块、透明度多少都来自设置，所以这里只负责按配置渲染。
 * 顶部那条是拖动把手，拖它移动窗口，点「✕」关闭，点标题回主界面。
 */
@Composable
fun FloatingContent(
    state: VehicleState,
    history: List<MainViewModel.HistorySample>,
    blocks: Set<FloatingBlock>,
    alpha: Float,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val width = if (FloatingBlock.CHART in blocks || FloatingBlock.CAR in blocks) 320.dp else 240.dp
    Surface(
        modifier = Modifier.width(width),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DiCar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { onOpenApp() } },
                )
                Spacer(Modifier.weight(1f))
                Text("⋮⋮", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "✕",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .pointerInput(Unit) { detectTapGestures { onClose() } },
                )
            }

            if (FloatingBlock.READOUT in blocks) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Format.num(state.speed),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.speed == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        " km/h",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        Format.text(state.gear),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (FloatingBlock.GAUGES in blocks) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ArcGauge(
                        value = state.displayRpm?.toFloat(), min = 0f, max = 12000f,
                        label = "转速", unit = "rpm", modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    ArcGauge(
                        value = state.batteryPower ?: state.power, min = -100f, max = 200f, zeroAt = 0f,
                        label = "功率", unit = "kW", decimals = 1, modifier = Modifier.weight(1f),
                    )
                    ArcGauge(
                        value = state.soc, min = 0f, max = 100f,
                        label = "电量", unit = "%", decimals = 0, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            if (FloatingBlock.CHART in blocks) {
                HistoryChart(
                    series = buildList {
                        add(ChartSeries("车速", "km/h", MaterialTheme.colorScheme.primary, history.map { it.speed }))
                        add(ChartSeries("功率", "kW", MaterialTheme.colorScheme.tertiary, history.map { it.power }, baseline = 0f))
                        if (history.any { it.engineRpm != null }) {
                            add(ChartSeries("发动机", "rpm", MaterialTheme.colorScheme.error, history.map { it.engineRpm }, baseline = 0f))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 70.dp,
                )
            }

            if (FloatingBlock.CAR in blocks) {
                CarDiagram(
                    state,
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }

            if (FloatingBlock.DETAILS in blocks) {
                MetricRow("电量", Format.num(state.soc, 1, "%"))
                MetricRow("续航", Format.num(state.remainRangeElec, unit = "km"))
                MetricRow("车内 / 车外", "${Format.num(state.insideTemp, 1)} / ${Format.num(state.outsideTemp, 1, "℃")}")
                MetricRow("电池温度", Format.num(state.batteryTempMax, 1, "℃"))
            }
        }
    }
}
