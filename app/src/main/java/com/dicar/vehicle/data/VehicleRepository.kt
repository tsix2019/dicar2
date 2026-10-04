package com.dicar.vehicle.data

import android.os.SystemClock
import android.util.Log
import com.dicar.vehicle.data.model.CommandResult
import com.dicar.vehicle.data.model.DataSourceType
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.withDerivedValues
import com.dicar.vehicle.data.source.VehicleDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 统一数据入口：
 * 1. 按设置选择数据源（自动模式下 BYDAuto 优先，失败降级迪加）；
 * 2. 定时轮询，产出 [state]；
 * 3. 控制指令：乐观更新 → 防抖下发 → 轮询回读确认 → 失败通过 [events] 提示。
 *
 * 所有内部状态只在 [dispatcher]（单线程）上访问，因此不需要加锁。
 */
class VehicleRepository(
    private val settings: SettingsStore,
    sources: List<VehicleDataSource>,
) {
    private val sources = sources.associateBy { it.type }

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    /** 正在等待下发/回读的指令 key，UI 用来显示“同步中”。 */
    private val _pendingKeys = MutableStateFlow<Set<String>>(emptySet())
    val pendingKeys: StateFlow<Set<String>> = _pendingKeys.asStateFlow()

    /** 一次性提示（控制失败、暂不支持等），UI 用 Snackbar 展示。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // ------------------------------------------------------------------
    // 轮询生命周期：前台服务和界面各持有一份引用，全部释放后才停止采集
    // ------------------------------------------------------------------

    private var clients = 0
    private var pollJob: Job? = null

    @Synchronized
    fun acquire() {
        clients++
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
    }

    @Synchronized
    fun release() {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients == 0) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val start = SystemClock.elapsedRealtime()
            pollOnce()
            val elapsed = SystemClock.elapsedRealtime() - start
            delay((settings.refreshIntervalMs.value - elapsed).coerceAtLeast(MIN_GAP_MS))
        }
    }

    private suspend fun pollOnce() {
        val source = resolveSource()
        val now = System.currentTimeMillis()
        val raw = if (source == null) {
            VehicleState(error = noSourceMessage(), timestamp = now)
        } else {
            try {
                source.read().withDerivedValues().withLatchedSignals().copy(source = source.type, timestamp = now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "read failed on ${source.type}", e)
                invalidateSource()
                VehicleState(source = source.type, error = "读取失败：${e.message ?: e.javaClass.simpleName}", timestamp = now)
            }
        }
        _state.value = reconcilePending(raw)
    }

    // ------------------------------------------------------------------
    // 数据源选择
    // ------------------------------------------------------------------

    private var active: VehicleDataSource? = null
    private var resolvedFor: SourceMode? = null
    private var lastProbeAt = 0L

    private suspend fun resolveSource(): VehicleDataSource? {
        val mode = settings.sourceMode.value
        val now = SystemClock.elapsedRealtime()
        if (mode == resolvedFor && (active != null || now - lastProbeAt < REPROBE_INTERVAL_MS)) {
            return active
        }
        if (mode != resolvedFor) {
            active?.release()
        }
        resolvedFor = mode
        lastProbeAt = now
        val previous = active
        active = candidatesFor(mode).firstOrNull { source ->
            try {
                source.isAvailable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "probe failed on ${source.type}", e)
                false
            }
        }
        Log.i(TAG, "mode=$mode -> source=${active?.type}")
        if (active?.type != previous?.type) sticky.clear() // 换数据源后旧的「最后下发值」不再可信
        return active
    }

    private fun candidatesFor(mode: SourceMode): List<VehicleDataSource> = when (mode) {
        SourceMode.AUTO -> listOf(DataSourceType.BYD_AUTO, DataSourceType.DI_PLUS)
        SourceMode.BYD_AUTO -> listOf(DataSourceType.BYD_AUTO)
        SourceMode.DI_PLUS -> listOf(DataSourceType.DI_PLUS)
        SourceMode.MOCK -> listOf(DataSourceType.MOCK)
    }.mapNotNull { sources[it] }

    /** 当前数据源读失败：下一轮立即重新探测（自动模式下可能切到备用数据源）。 */
    private fun invalidateSource() {
        active = null
        lastProbeAt = 0L
    }

    // ------------------------------------------------------------------
    // 闪烁信号的保持：转向灯本身就在闪，1 秒采样会随机采到「灭」的半周期，
    // 界面就会乱跳。亮过之后保持一小段时间，看起来才像真的在打灯。
    // ------------------------------------------------------------------

    private class SignalLatch(private val holdMs: Long) {
        private var lastTrueAt = 0L

        fun apply(raw: Boolean?): Boolean? {
            val now = SystemClock.elapsedRealtime()
            if (raw == true) {
                lastTrueAt = now
                return true
            }
            if (now - lastTrueAt < holdMs) return true
            return raw
        }
    }

    private val turnLeftLatch = SignalLatch(TURN_SIGNAL_HOLD_MS)
    private val turnRightLatch = SignalLatch(TURN_SIGNAL_HOLD_MS)

    private fun VehicleState.withLatchedSignals() = copy(
        turnLeft = turnLeftLatch.apply(turnLeft),
        turnRight = turnRightLatch.apply(turnRight),
    )

    private fun noSourceMessage(): String = when (settings.sourceMode.value) {
        SourceMode.AUTO -> "未检测到 BYDAuto 接口，迪加（127.0.0.1:8988）也无响应"
        SourceMode.BYD_AUTO -> "当前系统不支持 BYDAuto 接口"
        SourceMode.DI_PLUS -> "迪加（127.0.0.1:8988）无响应，请确认迪加已运行"
        SourceMode.MOCK -> "模拟数据源不可用"
    }

    // ------------------------------------------------------------------
    // 控制：乐观更新 + 防抖 + 回读确认
    // ------------------------------------------------------------------

    private class Pending(val command: VehicleCommand) {
        var sent = false
        var deadline = Long.MAX_VALUE
    }

    private val pending = LinkedHashMap<String, Pending>()
    private val sticky = LinkedHashMap<String, VehicleCommand>()
    private val debounceJobs = HashMap<String, Job>()

    fun send(command: VehicleCommand) = send { command }

    /**
     * 基于「当前状态（含尚未确认的乐观值）」生成指令再下发。
     * [build] 在仓库线程上按点击顺序执行：即使该线程正阻塞在一次 HTTP 读取中，
     * 连点「温度+」也会依次看到上一次的乐观值，不会丢步。返回 null 表示无需下发。
     */
    fun send(build: (VehicleState) -> VehicleCommand?) {
        scope.launch { build(_state.value)?.let(::enqueue) }
    }

    private fun enqueue(command: VehicleCommand) {
        // 不清 sticky：挂起期间 pending 叠加在 sticky 之后会覆盖它；若新指令失败，仍回退到上一次成功下发的值
        pending[command.key] = Pending(command)
        _state.update { command.applyTo(it) }
        publishPendingKeys()
        debounceJobs.remove(command.key)?.cancel()
        debounceJobs[command.key] = scope.launch {
            delay(DEBOUNCE_MS)
            dispatch(command)
        }
    }

    private suspend fun dispatch(command: VehicleCommand) {
        val source = resolveSource()
        val result = if (source == null) {
            CommandResult.Unsupported("无可用数据源")
        } else {
            try {
                source.execute(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "execute failed: $command", e)
                CommandResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        Log.i(TAG, "execute $command -> $result")

        // 下发期间被同 key 的新指令替换了：结果交给新指令处理
        val entry = pending[command.key]?.takeIf { it.command == command } ?: return

        when (result) {
            CommandResult.Sent -> {
                if (command.expected == null) {
                    finish(command.key, "已下发：${command.label}")
                } else {
                    entry.sent = true
                    entry.deadline = SystemClock.elapsedRealtime() + CONFIRM_TIMEOUT_MS
                    delay(READBACK_DELAY_MS)
                    pollOnce()
                }
            }
            is CommandResult.Unsupported -> finish(command.key, "暂不支持：${command.label}（${result.reason}）")
            is CommandResult.Failed -> finish(command.key, "控制失败：${command.label}（${result.reason}）")
        }
    }

    /** 用轮询结果确认/淘汰挂起指令；未确认的指令继续把乐观值叠加在真实状态上。 */
    private fun reconcilePending(raw: VehicleState): VehicleState {
        if (pending.isEmpty() && sticky.isEmpty()) return raw
        var merged = raw

        // 无法回读的字段（如迪加读不到座椅档位）：一直显示最后下发的值，直到数据源开始上报该字段。
        // 否则「点按循环档位」这类基于当前值的操作每次都会从 N/A 重新开始。
        sticky.values.removeAll { it.readBack(raw) != null }
        sticky.values.forEach { merged = it.applyTo(merged) }

        val now = SystemClock.elapsedRealtime()
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val command = entry.command
            if (!entry.sent) {
                merged = command.applyTo(merged) // 仍在防抖窗口内
                continue
            }
            val actual = command.readBack(raw)
            when {
                actual == command.expected -> iterator.remove()
                actual == null -> {
                    iterator.remove()
                    sticky[command.key] = command
                    merged = command.applyTo(merged)
                    _events.tryEmit("已下发：${command.label}（该项无法回读确认）")
                }
                now > entry.deadline -> {
                    iterator.remove()
                    _events.tryEmit("未生效：${command.label}（车机状态未变化）")
                }
                else -> merged = command.applyTo(merged)
            }
        }
        publishPendingKeys()
        return merged
    }

    private fun finish(key: String, message: String) {
        pending.remove(key)
        publishPendingKeys()
        _events.tryEmit(message)
    }

    private fun publishPendingKeys() {
        _pendingKeys.value = pending.keys.toSet()
    }

    companion object {
        private const val TAG = "VehicleRepository"
        private const val MIN_GAP_MS = 50L
        private const val REPROBE_INTERVAL_MS = 10_000L
        private const val DEBOUNCE_MS = 350L
        private const val READBACK_DELAY_MS = 400L
        private const val CONFIRM_TIMEOUT_MS = 3_000L
        private const val TURN_SIGNAL_HOLD_MS = 2_500L
    }
}
