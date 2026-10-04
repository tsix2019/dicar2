package com.dicar.vehicle.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dicar.vehicle.MainActivity
import com.dicar.vehicle.appContainer
import com.dicar.vehicle.data.SettingsStore
import com.dicar.vehicle.data.VehicleRepository
import com.dicar.vehicle.ui.MainViewModel
import com.dicar.vehicle.ui.screen.FloatingContent
import com.dicar.vehicle.ui.theme.DiCarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮窗：在任意界面之上显示车辆数据。
 *
 * 内容用 Compose 渲染（见 [FloatingContent]），因此可以直接复用主界面的仪表、
 * 曲线和孪生图组件；显示哪些块、透明度多少由设置决定，改动即时生效。
 *
 * 自己持有一份轮询引用，所以主界面关掉后依然更新。
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: VehicleRepository
    private lateinit var settings: SettingsStore
    private lateinit var windowManager: WindowManager

    private var composeView: ComposeView? = null
    private val owner = OverlayViewOwner()
    private val params = windowLayoutParams()
    private var acquired = false

    /** 悬浮窗自己维护一份曲线样本，不依赖界面的 ViewModel 活着。 */
    private val history = MutableStateFlow<List<MainViewModel.HistorySample>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        repository = appContainer.repository
        settings = appContainer.settings
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!canDrawOverlay(this)) {
            stopSelf()
            return
        }

        repository.acquire()
        acquired = true
        collectHistory()

        val view = buildComposeView()
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }
        composeView = view
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isRunning.value = false
        composeView?.let { v ->
            runCatching { windowManager.removeView(v) }
            v.disposeComposition()
        }
        composeView = null
        owner.stop()
        scope.cancel()
        if (acquired) repository.release()
        super.onDestroy()
    }

    private fun collectHistory() {
        scope.launch {
            var lastTimestamp = 0L
            repository.state.collect { s ->
                if (s.timestamp == lastTimestamp) return@collect
                lastTimestamp = s.timestamp
                history.value = (history.value + MainViewModel.HistorySample.of(s))
                    .takeLast(MainViewModel.HISTORY_SIZE)
            }
        }
    }

    private fun buildComposeView(): ComposeView {
        owner.start()
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state by repository.state.collectAsStateWithLifecycle()
                val samples by history.collectAsStateWithLifecycle()
                val blocks by settings.floatingBlocks.collectAsStateWithLifecycle()
                val alpha by settings.floatingAlpha.collectAsStateWithLifecycle()
                DiCarTheme {
                    FloatingContent(
                        state = state,
                        history = samples,
                        blocks = blocks,
                        alpha = alpha,
                        onDrag = ::moveBy,
                        onOpenApp = ::openApp,
                        onClose = { stopSelf() },
                    )
                }
            }
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = composeView ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun windowLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        // 不抢焦点，否则会吞掉车机其他应用的按键
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 48
        y = 160
    }

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        /** 跳系统的「显示在其他应用上层」授权页。 */
        fun overlayPermissionIntent(context: Context) = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
