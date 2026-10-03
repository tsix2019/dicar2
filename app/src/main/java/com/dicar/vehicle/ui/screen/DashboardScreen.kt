package com.dicar.vehicle.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.components.AcPanel
import com.dicar.vehicle.ui.components.BatteryCard
import com.dicar.vehicle.ui.components.BodyCard
import com.dicar.vehicle.ui.components.MiscCard
import com.dicar.vehicle.ui.components.PowerCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pendingKeys.collectAsStateWithLifecycle()
    val intervalMs by viewModel.refreshIntervalMs.collectAsStateWithLifecycle()
    val sourceMode by viewModel.sourceMode.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TopBar(state, intervalMs, onSettings = { showSettings = true })
            state.error?.let { ErrorBanner(it) }

            // 卡片自适应排布：车机横屏 1920 宽约 3 列，竖屏/手机 1 列
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 400.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "power") { PowerCard(state) }
                item(key = "battery") { BatteryCard(state) }
                item(key = "ac") {
                    AcPanel(state, pending, actions = viewModel, tempStep = state.acTempStep)
                }
                item(key = "body") { BodyCard(state) }
                item(key = "misc") { MiscCard(state) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            intervalMs = intervalMs,
            sourceMode = sourceMode,
            probe = probe,
            onIntervalChange = viewModel::setRefreshInterval,
            onSourceModeChange = viewModel::setSourceMode,
            onRunProbe = viewModel::runProbe,
            onDismiss = { showSettings = false },
        )
    }
    ProbeResultDialog(probe, onDismiss = viewModel::dismissProbe)
}

@Composable
private fun TopBar(state: VehicleState, intervalMs: Long, onSettings: () -> Unit) {
    val time = if (state.timestamp > 0) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(state.timestamp))
    } else "--"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DiCar 车况", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        SourceChip(state)
        Spacer(Modifier.width(12.dp))
        // 时间可被压缩/省略，保证竖屏窄屏时「设置」按钮始终可见
        Text(
            "$time · ${intervalMs}ms",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onSettings) { Text("⚙ 设置", maxLines = 1) }
    }
}

@Composable
private fun SourceChip(state: VehicleState) {
    val ok = state.source != null && state.error == null
    Text(
        text = "数据源：" + (state.source?.label ?: "无"),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .background(
                if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ErrorBanner(message: String) {
    Text(
        text = "暂不支持 / 数据不可用：$message",
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}
