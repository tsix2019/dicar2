package com.dicar.vehicle.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.util.Format

/**
 * 圆弧仪表：缺口朝下的 240° 表盘。
 *
 * [value] 为 null 时画空表盘并显示 N/A；[zeroAt] 用于功率这种有正负的量
 * （0 不在最左端，而在中间某处），负值向左画、正值向右画。
 */
@Composable
fun ArcGauge(
    value: Float?,
    min: Float,
    max: Float,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    negativeColor: Color = MaterialTheme.colorScheme.secondary,
    decimals: Int = 0,
    zeroAt: Float = min,
) {
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val target = value?.coerceIn(min, max) ?: min
    val fraction by animateFloatAsState(
        targetValue = (target - min) / span,
        label = "gauge",
    )
    val zeroFraction = ((zeroAt - min) / span).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val negative = value != null && value < zeroAt

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.095f
                val inset = stroke / 2 + size.minDimension * 0.04f
                val side = size.minDimension - inset * 2
                val topLeft = Offset((size.width - side) / 2, inset)
                val arcSize = Size(side, side)

                // 底盘
                drawArc(
                    color = track, startAngle = START, sweepAngle = SWEEP, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (value == null) return@Canvas

                // 从 0 点画到当前值（功率这类量可能向左画）
                val from = minOf(zeroFraction, fraction)
                val to = maxOf(zeroFraction, fraction)
                val sweep = (to - from) * SWEEP
                if (sweep > 0.5f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to (if (negative) negativeColor else color).copy(alpha = 0.55f),
                            1f to (if (negative) negativeColor else color),
                        ),
                        startAngle = START + from * SWEEP, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Format.num(value, decimals),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                if (value != null) {
                    Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 缺口朝下：从左下 150° 起，顺时针扫 240°。 */
private const val START = 150f
private const val SWEEP = 240f
