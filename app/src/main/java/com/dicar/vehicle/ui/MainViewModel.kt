package com.dicar.vehicle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dicar.vehicle.appContainer
import com.dicar.vehicle.data.FloatingBlock
import com.dicar.vehicle.data.SourceMode
import com.dicar.vehicle.data.model.AcCycleMode
import com.dicar.vehicle.data.model.AcWindMode
import com.dicar.vehicle.data.model.GlassZone
import com.dicar.vehicle.data.model.QuickAction
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.Zone
import com.dicar.vehicle.data.hardware.HardwareMonitor
import com.dicar.vehicle.data.hardware.HwSection
import com.dicar.vehicle.data.hardware.LiveStats
import com.dicar.vehicle.data.update.UpdateState
import com.dicar.vehicle.ui.components.AcActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Running : ProbeUiState
    data class Done(val filePath: String?, val report: String) : ProbeUiState
}

sealed interface HardwareExportState {
    data object Idle : HardwareExportState
    data class Done(val filePath: String?, val text: String, val redact: Boolean) : HardwareExportState
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

    /** 曲线图用的滚动样本（每次轮询追加一条）。 */
    data class HistorySample(
        val speed: Float?,
        val power: Float?,
        /** 主转速（有发动机用发动机，否则用电机），给只画一条转速线的地方用。 */
        val rpm: Float?,
        val soc: Float?,
        /** 发动机转速：混动车只有发动机介入时才有值。 */
        val engineRpm: Float?,
        /** 电机转速（前后取绝对值大的一侧）。和 [engineRpm] 分开存，
         *  否则混动车上两条曲线会完全重合——[rpm] 本来就等于发动机转速。 */
        val motorRpm: Float?,
    ) {
        companion object {
            /** 主界面和悬浮窗各自维护一份曲线缓冲，采样逻辑只在这里写一遍。 */
            fun of(s: VehicleState) = HistorySample(
                speed = s.speed,
                power = s.batteryPower ?: s.power,
                rpm = s.displayRpm?.toFloat(),
                soc = s.soc,
                engineRpm = s.engineRpm?.toFloat(),
                motorRpm = listOfNotNull(s.motorRpmFront, s.motorRpmRear)
                    .maxByOrNull { kotlin.math.abs(it) }?.toFloat(),
            )
        }
    }

    private val _history = MutableStateFlow<List<HistorySample>>(emptyList())
    val history: StateFlow<List<HistorySample>> = _history.asStateFlow()

    init {
        // 界面也持有一份轮询引用：即使前台服务没起来（权限被拒等），首页照样有数据
        repository.acquire()

        viewModelScope.launch {
            var lastTimestamp = 0L
            state.collect { s ->
                // 只按轮询节奏采样；乐观更新（点按空调等）不该在曲线上造出假点
                if (s.timestamp == lastTimestamp) return@collect
                lastTimestamp = s.timestamp
                _history.update { (it + HistorySample.of(s)).takeLast(HISTORY_SIZE) }
            }
        }

        checkUpdateOnStartIfEnabled()
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

    override fun glass(zone: GlassZone, open: Boolean) = repository.send(VehicleCommand.Glass(zone, open))

    // ---------------- 设置 ----------------

    fun setRefreshInterval(ms: Long) = settings.setRefreshInterval(ms)

    fun setSourceMode(mode: SourceMode) = settings.setSourceMode(mode)

    val twin3dEnabled = settings.twin3dEnabled

    fun setTwin3dEnabled(enabled: Boolean) = settings.setTwin3dEnabled(enabled)

    // ---------------- 更新检查 ----------------

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    val autoCheckUpdate = settings.autoCheckUpdate

    fun setAutoCheckUpdate(enabled: Boolean) {
        settings.setAutoCheckUpdate(enabled)
        // 刚打开就先查一次，否则要等下次启动才有反馈
        if (enabled && _update.value is UpdateState.Idle) checkUpdate()
    }

    fun checkUpdate() {
        if (_update.value == UpdateState.Checking) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = container.updateChecker.check()
        }
    }

    /** 启动时的自动检查。只在设置里显式打开过才会联网。 */
    private fun checkUpdateOnStartIfEnabled() {
        if (settings.autoCheckUpdate.value) checkUpdate()
    }

    val floatingBlocks = settings.floatingBlocks
    val floatingAlpha = settings.floatingAlpha

    fun toggleFloatingBlock(block: FloatingBlock) = settings.toggleFloatingBlock(block)

    fun setFloatingAlpha(value: Float) = settings.setFloatingAlpha(value)

    // ---------------- 硬件信息 ----------------

    private val _hardware = MutableStateFlow<List<HwSection>>(emptyList())
    val hardware: StateFlow<List<HwSection>> = _hardware.asStateFlow()

    private val _hardwareLoading = MutableStateFlow(false)
    val hardwareLoading: StateFlow<Boolean> = _hardwareLoading.asStateFlow()

    private val _hardwareExport = MutableStateFlow<HardwareExportState>(HardwareExportState.Idle)
    val hardwareExport: StateFlow<HardwareExportState> = _hardwareExport.asStateFlow()

    /** 「隐去标识符」开关对应的那一份原始数据，切换开关时要用它重新渲染。 */
    private var lastExported: List<HwSection> = emptyList()

    /**
     * 每秒一帧的实时指标。
     *
     * 故意做成冷流：只有硬件页在前台订阅时才采样，离开页面立刻停。
     * 这些读数要遍历 /sys 下几十个节点，不值得为了没人看的页面一直跑。
     */
    fun liveStats(): Flow<LiveStats> = flow {
        while (true) {
            emit(withContext(Dispatchers.IO) { container.hardwareMonitor.sample() })
            delay(HardwareMonitor.SAMPLE_INTERVAL_MS)
        }
    }

    /** 静态信息只采一次，[force] 为 true 时重采（比如插了 U 盘之后）。 */
    fun loadHardware(force: Boolean = false) {
        if (_hardwareLoading.value) return
        if (!force && _hardware.value.isNotEmpty()) return
        _hardwareLoading.value = true
        viewModelScope.launch {
            _hardware.value = withContext(Dispatchers.IO) { container.hardwareInspector.collectStatic() }
            _hardwareLoading.value = false
        }
    }

    fun exportHardware(sections: List<HwSection>) {
        if (sections.isEmpty()) return
        lastExported = sections
        writeExport(redact = true)
    }

    fun setExportRedact(redact: Boolean) = writeExport(redact)

    private fun writeExport(redact: Boolean) {
        val sections = lastExported.ifEmpty { return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.hardwareInspector.export(sections, redact)
            }
            _hardwareExport.value = HardwareExportState.Done(result.file?.absolutePath, result.text, redact)
        }
    }

    fun dismissHardwareExport() {
        _hardwareExport.value = HardwareExportState.Idle
    }

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
        /** 曲线保留的样本数（1 秒一条，约 3 分钟）。 */
        const val HISTORY_SIZE = 180
    }
}
