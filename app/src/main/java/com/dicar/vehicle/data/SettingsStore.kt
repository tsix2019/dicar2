package com.dicar.vehicle.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    companion object {
        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 2000L
        const val DEFAULT_INTERVAL_MS = 1000L
        private const val KEY_INTERVAL = "refresh_interval_ms"
        private const val KEY_SOURCE = "source_mode"
    }
}
