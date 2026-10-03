package com.dicar.vehicle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dicar.vehicle.appContainer
import com.dicar.vehicle.data.SourceMode
import com.dicar.vehicle.data.model.AcCycleMode
import com.dicar.vehicle.data.model.AcWindMode
import com.dicar.vehicle.data.model.QuickAction
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.Zone
import com.dicar.vehicle.ui.components.AcActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Running : ProbeUiState
    data class Done(val filePath: String?, val report: String) : ProbeUiState
}

class MainViewModel(app: Application) : AndroidViewModel(app), AcActions {

    private val container = app.appContainer
    private val repository = container.repository
    private val settings = container.settings

    val state = repository.state
    val pendingKeys = repository.pendingKeys
    val events = repository.events
    val refreshIntervalMs = settings.refreshIntervalMs
    val sourceMode = settings.sourceMode

    private val _probe = MutableStateFlow<ProbeUiState>(ProbeUiState.Idle)
    val probe: StateFlow<ProbeUiState> = _probe.asStateFlow()

    init {
        // 界面也持有一份轮询引用：即使前台服务没起来（权限被拒等），首页照样有数据
        repository.acquire()
    }

    override fun onCleared() {
        repository.release()
    }

    // ---------------- 空调 / 座椅控制 ----------------
    // 「加减/切换」类操作的目标值在 Repository 线程上基于最新状态（含乐观值）计算，
    // 连续点击按顺序累加，再由 Repository 防抖合并成一次下发。

    override fun toggleAc() = repository.send { s -> VehicleCommand.AcPower(s.acOn != true) }

    override fun toggleAcAuto() = repository.send { s -> VehicleCommand.AcAuto(s.acAuto != true) }

    override fun adjustTemp(zone: Zone, delta: Float) = repository.send { s ->
        val current = (if (zone == Zone.DRIVER) s.acTempDriver else s.acTempPassenger) ?: DEFAULT_TEMP
        val target = (current + delta).coerceIn(MIN_TEMP, MAX_TEMP)
        if (target != current) VehicleCommand.AcTemperature(zone, target) else null
    }

    override fun adjustFan(delta: Int) = repository.send { s ->
        val current = s.acFanLevel ?: 0
        val target = (current + delta).coerceIn(MIN_FAN, MAX_FAN)
        if (target != current) VehicleCommand.AcFanLevel(target) else null
    }

    override fun setWindMode(mode: AcWindMode) = repository.send(VehicleCommand.AcWind(mode))

    override fun toggleCycle() = repository.send { s ->
        VehicleCommand.AcCycle(if (s.acCycle == AcCycleMode.INNER) AcCycleMode.OUTER else AcCycleMode.INNER)
    }

    /** 座椅加热/通风：每点一次 关→1→…→最高档→关 循环，最高档由数据源决定。 */
    override fun cycleSeatHeat(zone: Zone) = repository.send { s ->
        val current = if (zone == Zone.DRIVER) s.seatHeatDriver else s.seatHeatPassenger
        VehicleCommand.SeatHeat(zone, nextSeatLevel(current, s.seatMaxLevel))
    }

    override fun cycleSeatVent(zone: Zone) = repository.send { s ->
        val current = if (zone == Zone.DRIVER) s.seatVentDriver else s.seatVentPassenger
        VehicleCommand.SeatVent(zone, nextSeatLevel(current, s.seatMaxLevel))
    }

    private fun nextSeatLevel(current: Int?, max: Int): Int {
        val level = current ?: 0
        return if (level >= max) 0 else level + 1
    }

    override fun quick(action: QuickAction) = repository.send(VehicleCommand.Quick(action))

    // ---------------- 设置 ----------------

    fun setRefreshInterval(ms: Long) = settings.setRefreshInterval(ms)

    fun setSourceMode(mode: SourceMode) = settings.setSourceMode(mode)

    fun runProbe() {
        if (_probe.value == ProbeUiState.Running) return
        _probe.value = ProbeUiState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { container.probe.run() }
            _probe.value = ProbeUiState.Done(result.file?.absolutePath, result.report)
        }
    }

    fun dismissProbe() {
        _probe.value = ProbeUiState.Idle
    }

    companion object {
        const val MIN_TEMP = 17f
        const val MAX_TEMP = 33f
        private const val DEFAULT_TEMP = 22f
        const val MIN_FAN = 1
        const val MAX_FAN = 7
    }
}
