package com.dicar.vehicle.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.util.Format
import kotlin.math.abs

/**
 * 大号主读数：数值大字细体，单位小字贴在右下角。
 * [signed] = true 时正值带 `+`（驱动功率要看得出正负）。
 */
@Composable
fun HeroNumber(
    value: Float?,
    unit: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    size: TextUnit = 46.sp,
    signed: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val na = value == null
    val text = when {
        na -> Format.NA
        signed && value > 0f -> "+" + Format.num(value, decimals)
        else -> Format.num(value, decimals)
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text,
            style = MaterialTheme.typography.displayMedium,
            fontSize = size,
            color = when {
                na -> MaterialTheme.colorScheme.outline
                color != Color.Unspecified -> color
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(
                unit,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = size.value.dp * 0.14f),
            )
        }
    }
}

/**
 * 驱动 / 回收双向条：0 在正中，正值（驱动）向右画、负值（回收）向左画。
 * 两边量程可以不一样——回收功率通常远小于驱动功率，各自归一化才看得出变化。
 */
@Composable
fun SignedBar(
    value: Float?,
    maxPositive: Float,
    maxNegative: Float,
    modifier: Modifier = Modifier,
    positiveColor: Color = MaterialTheme.colorScheme.primary,
    negativeColor: Color = MaterialTheme.colorScheme.secondary,
    height: Dp = 10.dp,
) {
    val fraction by animateFloatAsState(
        targetValue = when {
            value == null -> 0f
            value >= 0f -> (value / maxPositive).coerceIn(0f, 1f)
            else -> -(abs(value) / maxNegative).coerceIn(0f, 1f)
        },
        label = "signed-bar",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val tick = MaterialTheme.colorScheme.outline

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        val centerX = size.width / 2f
        val barW = centerX * abs(fraction)
        if (barW > 0.5f) {
            drawRoundRect(
                color = if (fraction >= 0f) positiveColor else negativeColor,
                topLeft = Offset(if (fraction >= 0f) centerX else centerX - barW, 0f),
                size = Size(barW, size.height),
                cornerRadius = r,
            )
        }
        // 0 刻度：始终可见，否则看不出条是从哪儿长出来的
        drawRect(
            color = tick.copy(alpha = 0.6f),
            topLeft = Offset(centerX - size.height * 0.06f, 0f),
            size = Size(size.height * 0.12f, size.height),
        )
    }
}

/** [SignedBar] 下面的三个刻度标签。 */
@Composable
fun SignedBarScale(negativeLabel: String, zeroLabel: String, positiveLabel: String) {
    Row(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(negativeLabel, style = style, color = color, modifier = Modifier.weight(1f))
        Text(zeroLabel, style = style, color = color)
        Text(
            positiveLabel,
            style = style,
            color = color,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * 「标签 —— 数值 + 进度条」三件套。电量用绿、油量用橙，颜色在哪一页都一致。
 * [value] 为 null 时条是空的、数值显示 N/A，不画成 0%（0% 和读不到完全是两回事）。
 */
@Composable
fun LabeledBar(
    label: String,
    value: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String = "%",
    decimals: Int = 0,
) {
    val fraction by animateFloatAsState(
        targetValue = ((value ?: 0f) / 100f).coerceIn(0f, 1f),
        label = "labeled-bar",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                // 百分号紧贴数字（「68%」而不是「68 %」），其它单位才留空格
                if (unit == "%") Format.num(value, decimals).let { if (value == null) it else "$it%" }
                else Format.num(value, decimals, unit),
                style = MaterialTheme.typography.headlineSmall,
                color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, size = size, cornerRadius = r)
            if (value != null && fraction > 0.001f) {
                drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
        }
    }
}

/** 底栏的状态胶囊：描边圆角，不填色，只有告警时才上色。 */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, alert: Boolean = false) {
    val color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** 挡位徽标：读不到时是一个灰色的占位方块，不留空。 */
@Composable
fun GearBadge(gear: String?, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val known = !gear.isNullOrBlank()
    Box(
        modifier
            .size(size)
            .background(
                if (known) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(size * 0.26f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (known) gear!! else "–",
            fontSize = (size.value * 0.47f).sp,
            fontWeight = FontWeight.Bold,
            color = if (known) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 顶栏的状态点 + 文字，比如「● 发动机运行中」。 */
@Composable
fun StatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
