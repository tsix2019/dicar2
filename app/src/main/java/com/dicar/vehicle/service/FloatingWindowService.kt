package com.dicar.vehicle.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.dicar.vehicle.MainActivity
import com.dicar.vehicle.appContainer
import com.dicar.vehicle.data.VehicleRepository
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗：在任意界面之上显示车速 / 电量 / 功率 / 转速。
 *
 * 用传统 View 而非 Compose——悬浮窗不在 Activity 里，Compose 需要自行补齐
 * Lifecycle/SavedState/Recomposer 等宿主，得不偿失。
 *
 * 可拖动；点一下打开主界面，点「✕」关闭。数据直接取自 [VehicleRepository]，
 * 并自己持有一份轮询引用，所以主界面关掉也会继续更新。
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: VehicleRepository
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var acquired = false

    private lateinit var speedView: TextView
    private lateinit var detailView: TextView

    override fun onCreate() {
        super.onCreate()
        repository = appContainer.repository
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!canDrawOverlay(this)) {
            stopSelf()
            return
        }

        val view = buildView()
        val params = layoutParams()
        runCatching { windowManager.addView(view, params) }.onFailure {
            stopSelf()
            return
        }
        rootView = view
        attachDrag(view, params)

        repository.acquire()
        acquired = true
        _isRunning.value = true

        scope.launch {
            repository.state.collect(::render)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        rootView?.let { v -> runCatching { windowManager.removeView(v) } }
        rootView = null
        if (acquired) repository.release()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun render(state: VehicleState) {
        speedView.text = Format.num(state.speed)
        val power = state.batteryPower ?: state.power
        detailView.text = buildString {
            append("SOC ").append(Format.num(state.soc, 1, "%"))
            append("   ").append(Format.num(power, 1, "kW"))
            state.displayRpm?.let { append("   ").append(it).append(" rpm") }
        }
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    private fun buildView(): View {
        val pad = dp(10)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(6), pad, dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E6101418"))
                setStroke(dp(1), Color.parseColor("#333DA5FF"))
            }
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        speedView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            text = Format.NA
        }
        val unit = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA6B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            text = "km/h"
        }
        detailView = TextView(this).apply {
            setTextColor(Color.parseColor("#C8D2DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = ""
        }

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(speedView)
            addView(unit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(3); bottomMargin = dp(4) })
        }
        texts.addView(speedRow)
        texts.addView(detailView)

        val close = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA6B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "✕"
            setPadding(dp(10), dp(2), dp(2), dp(2))
            setOnClickListener { stopSelf() }
        }

        container.addView(texts)
        container.addView(close)
        return container
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(80)
    }

    /** 拖动；位移很小的时候当作点击，打开主界面。 */
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - touchX) > dp(6) || abs(event.rawY - touchY) > dp(6)
                    if (!moved) {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).roundToInt()

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
