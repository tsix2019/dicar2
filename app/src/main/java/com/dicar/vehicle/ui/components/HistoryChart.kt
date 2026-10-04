package com.dicar.vehicle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dicar.vehicle.util.Format

/** 一条曲线的数据与样式。 */
data class ChartSeries(
    val name: String,
    val unit: String,
    val color: Color,
    val points: List<Float?>,
    /** 固定下界；null 表示按数据自适应。功率这类有正负的量固定 0 基线更好读。 */
    val baseline: Float? = null,
)

/**
 * 多条曲线共用一张图：每条曲线各自归一化到自己的量程，
 * 因为车速(km/h)和功率(kW)量纲不同，放一起只看趋势。
 */
@Composable
fun HistoryChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(s.color, CircleShape))
                    val last = s.points.lastOrNull { it != null }
                    Text(
                        "${s.name} ${Format.num(last, if (s.unit == "kW") 1 else 0, s.unit)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            // 横向网格
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = dash)
            }

            series.forEach { s ->
                val pts = s.points
                if (pts.count { it != null } < 2) return@forEach
                val values = pts.filterNotNull()
                var lo = s.baseline ?: values.min()
                var hi = values.max()
                if (s.baseline != null) {
                    lo = minOf(s.baseline, values.min())
                    hi = maxOf(s.baseline, values.max())
                }
                if (hi - lo < 1e-3f) hi = lo + 1f

                val dx = size.width / (pts.size - 1).coerceAtLeast(1)
                val path = Path()
                var started = false
                pts.forEachIndexed { i, v ->
                    if (v == null) {
                        started = false // 断点：数据缺失处断开，不要连成直线骗人
                        return@forEachIndexed
                    }
                    val x = dx * i
                    val y = size.height * (1f - (v - lo) / (hi - lo))
                    if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
                }
                drawPath(path, s.color, style = Stroke(width = 2.5f))
            }
        }
    }
}
