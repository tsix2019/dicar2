package com.dicar.vehicle.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 悬浮窗里可以自由开关的内容块。 */
enum class FloatingBlock(val label: String) {
    READOUT("主读数（车速）"),
    GAUGES("仪表盘"),
    CHART("趋势曲线"),
    CAR("车辆孪生图"),
    DETAILS("明细数据"),
}

enum class SourceMode(val label: String) {
    AUTO("自动（BYDAuto → 迪加）"),
    BYD_AUTO("仅 BYDAuto"),
    DI_PLUS("仅迪加"),
    MOCK("模拟数据（无车调试）"),
}

/** 轻量设置存储（SharedPreferences），不引入 DataStore 以控制包体。 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _refreshIntervalMs = MutableStateFlow(
        prefs.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_MS).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    )
    val refreshIntervalMs: StateFlow<Long> = _refreshIntervalMs.asStateFlow()

    private val _sourceMode = MutableStateFlow(
        prefs.getString(KEY_SOURCE, null)
            ?.let { name -> SourceMode.entries.firstOrNull { it.name == name } }
            ?: SourceMode.AUTO
    )
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    fun setRefreshInterval(ms: Long) {
        val value = ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        _refreshIntervalMs.value = value
        prefs.edit { putLong(KEY_INTERVAL, value) }
    }

    fun setSourceMode(mode: SourceMode) {
        _sourceMode.value = mode
        prefs.edit { putString(KEY_SOURCE, mode.name) }
    }

    // ---------------- 孪生图 ----------------

    /**
     * 孪生主页是否用 3D 车辆。默认**关**：3D 会让 GLSurfaceView 持续按屏幕刷新率重绘，
     * 车机 GPU 本来就弱，和导航同时开时不值得。关掉就是纯 Canvas 的 2D 俯视图。
     */
    private val _twin3dEnabled = MutableStateFlow(prefs.getBoolean(KEY_TWIN_3D, false))
    val twin3dEnabled: StateFlow<Boolean> = _twin3dEnabled.asStateFlow()

    fun setTwin3dEnabled(enabled: Boolean) {
        _twin3dEnabled.value = enabled
        prefs.edit { putBoolean(KEY_TWIN_3D, enabled) }
    }

    // ---------------- 悬浮窗 ----------------

    private val _floatingBlocks = MutableStateFlow(
        prefs.getString(KEY_FLOAT_BLOCKS, null)
            ?.split(',')
            ?.mapNotNull { name -> FloatingBlock.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: DEFAULT_FLOATING_BLOCKS
    )
    val floatingBlocks: StateFlow<Set<FloatingBlock>> = _floatingBlocks.asStateFlow()

    private val _floatingAlpha = MutableStateFlow(
        prefs.getFloat(KEY_FLOAT_ALPHA, DEFAULT_FLOATING_ALPHA).coerceIn(MIN_FLOATING_ALPHA, 1f)
    )
    val floatingAlpha: StateFlow<Float> = _floatingAlpha.asStateFlow()

    fun toggleFloatingBlock(block: FloatingBlock) {
        val next = _floatingBlocks.value.toMutableSet().apply {
            if (!add(block)) remove(block)
        }
        _floatingBlocks.value = next
        prefs.edit { putString(KEY_FLOAT_BLOCKS, next.joinToString(",") { it.name }) }
    }

    fun setFloatingAlpha(value: Float) {
        val v = value.coerceIn(MIN_FLOATING_ALPHA, 1f)
        _floatingAlpha.value = v
        prefs.edit { putFloat(KEY_FLOAT_ALPHA, v) }
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 2000L
        const val DEFAULT_INTERVAL_MS = 1000L
        const val MIN_FLOATING_ALPHA = 0.25f
        const val DEFAULT_FLOATING_ALPHA = 0.92f
        val DEFAULT_FLOATING_BLOCKS = setOf(FloatingBlock.READOUT, FloatingBlock.GAUGES)

        private const val KEY_INTERVAL = "refresh_interval_ms"
        private const val KEY_SOURCE = "source_mode"
        private const val KEY_FLOAT_BLOCKS = "floating_blocks"
        private const val KEY_FLOAT_ALPHA = "floating_alpha"
        private const val KEY_TWIN_3D = "twin_3d_enabled"
    }
}
