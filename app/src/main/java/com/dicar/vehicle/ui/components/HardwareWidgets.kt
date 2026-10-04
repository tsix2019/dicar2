package com.dicar.vehicle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.data.hardware.CoreLive
import com.dicar.vehicle.data.hardware.HwFormat
import com.dicar.vehicle.data.hardware.HwItem

/**
 * 实时指标瓦片：一个大数 + 一条细进度条 + 一行注脚。
 * [fraction] 为 null 时只画空条——「读不到」不能画成「0%」。
 */
@Composable
fun UsageTile(
    label: String,
    headline: String,
    caption: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            headline,
            style = MaterialTheme.typography.headlineMedium,
            color = if (headline == HwItem.UNKNOWN) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        ThinBar(fraction, color)
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 细进度条。不用 LinearProgressIndicator，它在 0 值时还会画一个圆点。 */
@Composable
fun ThinBar(fraction: Float?, color: Color, height: Int = 6) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        if (fraction != null && fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(height.dp)
                    .background(color)
            )
        }
    }
}

/**
 * 一个 CPU 核心一行：编号、型号、当前频率、占用条。
 * 离线的核心整行变灰并标注，省得以为是读数坏了。
 */
@Composable
fun CoreRow(core: CoreLive, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "CPU${core.index}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (core.online) MaterialTheme.colorScheme.onSurface else dim,
            modifier = Modifier.width(46.dp),
        )
        core.model?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(76.dp),
            )
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            ThinBar(core.load?.div(100f), loadColor(core.load), height = 8)
        }
        Text(
            if (core.online) HwFormat.kHz(core.curKhz) else "离线",
            style = MaterialTheme.typography.labelMedium,
            color = if (core.online) MaterialTheme.colorScheme.onSurface else dim,
            maxLines = 1,
            modifier = Modifier.width(70.dp),
        )
        Text(
            HwFormat.percent(core.load),
            style = MaterialTheme.typography.labelMedium,
            color = dim,
            maxLines = 1,
            modifier = Modifier.width(46.dp),
        )
    }
}

/** 占用越高越醒目：低位保持中性蓝，高位才变橙变红。 */
@Composable
fun loadColor(load: Float?): Color = when {
    load == null -> MaterialTheme.colorScheme.outline
    load >= 90f -> MaterialTheme.colorScheme.error
    load >= 70f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/**
 * 温度配色。阈值按 SoC 结温习惯取：70℃ 以上开始值得注意，85℃ 以上通常已经在降频。
 */
@Composable
fun tempColor(celsius: Float?): Color = when {
    celsius == null -> MaterialTheme.colorScheme.outline
    celsius >= 85f -> MaterialTheme.colorScheme.error
    celsius >= 70f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/** 键值对一行，比 [MetricRow] 更紧凑，用于硬件页这种条目极多的场合。 */
@Composable
fun HwRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (value == HwItem.UNKNOWN) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/** 小标题，比 DataCard 的标题低一级。 */
@Composable
fun HwSubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 卡片右上角的条目计数。 */
@Composable
fun CountBadge(count: Int) {
    Text(
        "$count",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
