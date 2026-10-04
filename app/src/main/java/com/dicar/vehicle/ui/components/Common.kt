package com.dicar.vehicle.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.util.Format

/**
 * 数据卡片外壳：白卡 + 细描边 + 不投影，标题是小字浅色（强调色留给数据本身，
 * 不浪费在标题上）。[title] 传 null 则不画标题行，用于底栏这类只有内容的卡片。
 */
@Composable
fun DataCard(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** 大号主读数，如车速、SOC。value 为 N/A 时自动变灰。 */
@Composable
fun BigMetric(value: String, unit: String, label: String, modifier: Modifier = Modifier, size: TextUnit = 44.sp) {
    val na = value == Format.NA
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.displayMedium,
                fontSize = size,
                color = if (na) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            if (!na && unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 标签 + 数值的一行。 */
@Composable
fun MetricRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = when {
                value == Format.NA -> MaterialTheme.colorScheme.outline
                valueColor != Color.Unspecified -> valueColor
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** 两列并排的 MetricRow，节省纵向空间。 */
@Composable
fun MetricPair(
    leftLabel: String, leftValue: String,
    rightLabel: String, rightValue: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MetricRow(leftLabel, leftValue, Modifier.weight(1f))
        MetricRow(rightLabel, rightValue, Modifier.weight(1f))
    }
}

/** 百分比条（油门、刹车、SOC）。value 为 null 时显示空条 + N/A。 */
@Composable
fun PercentBar(label: String, value: Float?, color: Color = MaterialTheme.colorScheme.primary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MetricRow(label, Format.num(value, unit = "%"))
        LinearProgressIndicator(
            progress = { ((value ?: 0f) / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}

/** 加减调节器（温度、风量）。pending = 指令已下发，正在等车机回读确认。 */
@Composable
fun Stepper(
    label: String,
    value: String,
    pending: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onMinus, enabled = enabled, colors = stepperColors(), modifier = Modifier.size(56.dp, 48.dp), contentPadding = PaddingValues(0.dp)) {
            Text("−", fontSize = 22.sp)
        }
        Row(
            Modifier.width(110.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (value == Format.NA) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            if (pending) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        FilledTonalButton(onClick = onPlus, enabled = enabled, colors = stepperColors(), modifier = Modifier.size(56.dp, 48.dp), contentPadding = PaddingValues(0.dp)) {
            Text("+", fontSize = 22.sp)
        }
    }
}

/**
 * 加减按钮用中性底色 + 强调色字。
 * Material 默认的 filledTonal 走 secondaryContainer，在这套配色里是绿色——
 * 绿色被电量占用了，按钮再用绿会让人以为和电量有关。
 */
@Composable
private fun stepperColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.primary,
)
