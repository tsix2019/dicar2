package com.dicar.vehicle.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Checkbox
import androidx.compose.ui.platform.LocalContext
import com.dicar.vehicle.data.FloatingBlock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.data.SettingsStore
import com.dicar.vehicle.data.SourceMode
import com.dicar.vehicle.ui.ProbeUiState
import kotlin.math.roundToLong

/** 设置：数据源切换（F42 降级）、刷新频率（F40）、BYDAuto 接口探测。 */
@Composable
fun SettingsDialog(
    intervalMs: Long,
    sourceMode: SourceMode,
    probe: ProbeUiState,
    floatingBlocks: Set<FloatingBlock>,
    floatingAlpha: Float,
    onIntervalChange: (Long) -> Unit,
    onSourceModeChange: (SourceMode) -> Unit,
    onToggleFloatingBlock: (FloatingBlock) -> Unit,
    onFloatingAlphaChange: (Float) -> Unit,
    onRunProbe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var sliderValue by remember(intervalMs) { mutableFloatStateOf(intervalMs.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("设置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("数据源", style = MaterialTheme.typography.titleSmall)
                SourceMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == sourceMode, onClick = { onSourceModeChange(mode) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == sourceMode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(mode.label)
                    }
                }

                HorizontalDivider()
                Text("刷新间隔：${sliderValue.roundToLong()} ms", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = (it / 100f).roundToLong() * 100f },
                    onValueChangeFinished = { onIntervalChange(sliderValue.roundToLong()) },
                    valueRange = SettingsStore.MIN_INTERVAL_MS.toFloat()..SettingsStore.MAX_INTERVAL_MS.toFloat(),
                    steps = ((SettingsStore.MAX_INTERVAL_MS - SettingsStore.MIN_INTERVAL_MS) / 100 - 1).toInt(),
                )

                HorizontalDivider()
                Text("悬浮窗", style = MaterialTheme.typography.titleSmall)
                Text(
                    "勾选要显示的内容；透明度越低越不挡视线。改动立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FloatingBlock.entries.forEach { block ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = block in floatingBlocks, onClick = { onToggleFloatingBlock(block) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = block in floatingBlocks, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(block.label)
                    }
                }
                var alpha by remember(floatingAlpha) { mutableFloatStateOf(floatingAlpha) }
                Text("不透明度：${(alpha * 100).roundToLong()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    onValueChangeFinished = { onFloatingAlphaChange(alpha) },
                    valueRange = SettingsStore.MIN_FLOATING_ALPHA..1f,
                )

                HorizontalDivider()
                Text("关于", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("开源地址：$PROJECT_URL") }

                HorizontalDivider()
                Text("开发工具", style = MaterialTheme.typography.titleSmall)
                Text(
                    "枚举车机上所有 BYDAuto*Device 的方法与常量，并调用无参 getter 记录当前值，" +
                        "结果保存为文本文件，可 adb pull 后对照填充 BydApiMap。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onRunProbe, enabled = probe != ProbeUiState.Running) {
                    if (probe == ProbeUiState.Running) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("探测 BYDAuto 接口")
                }
            }
        },
    )
}

@Composable
fun ProbeResultDialog(probe: ProbeUiState, onDismiss: () -> Unit) {
    if (probe !is ProbeUiState.Done) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("BYDAuto 接口探测结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    probe.filePath?.let { "已保存：$it" } ?: "保存文件失败，仅显示在下方",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        // 弹窗只显示前一部分，完整内容看文件
                        probe.report.take(20_000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
    )
}

/** 项目开源地址，设置页里可以直接点开。 */
const val PROJECT_URL = "https://github.com/tsix2019/dicar2"
