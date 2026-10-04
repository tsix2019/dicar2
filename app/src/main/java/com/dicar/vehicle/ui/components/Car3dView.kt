package com.dicar.vehicle.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.ui.gl.Car3dRenderer
import kotlin.math.hypot

/**
 * 3D 车辆视图。拖动旋转、双指缩放。
 *
 * 默认不开（设置里的开关）：虽然整台车只有一千多个三角形，但 GLSurfaceView 会持续
 * 以屏幕刷新率重绘，在车机那块 GPU 上和导航同时开时仍然是额外负担。关掉就退回
 * 纯 Canvas 的 2D 俯视图，一点 GPU 都不多占。
 */
@Composable
fun Car3dView(state: VehicleState, modifier: Modifier = Modifier) {
    val renderer = remember { Car3dRenderer() }
    val dark = isSystemInDarkTheme()
    val background = MaterialTheme.colorScheme.background
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { mutableHolder<CarGlView>() }

    // 车机切后台时必须 onPause，否则 GL 线程会继续空转
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> view.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.value?.onPause()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { context -> CarGlView(context, renderer).also { view.value = it } },
            modifier = Modifier.fillMaxSize(),
            update = {
                renderer.background = background.toRgba()
                renderer.bodyColor = (if (dark) BODY_DARK else BODY_LIGHT).toRgba()
                renderer.glassColor = (if (dark) GLASS_DARK else GLASS_LIGHT).toRgba()
                renderer.interiorColor = (if (dark) INTERIOR_DARK else INTERIOR_LIGHT).toRgba()
                renderer.wheelColor = (if (dark) WHEEL_DARK else WHEEL_LIGHT).toRgba()
                renderer.shadowAlpha = if (dark) 0.30f else 0.16f

                renderer.doorTargets = floatArrayOf(
                    state.doors.fl.toOpen(), state.doors.fr.toOpen(),
                    state.doors.rl.toOpen(), state.doors.rr.toOpen(),
                )
                renderer.windowTargets = floatArrayOf(
                    state.windowPercent.fl.toFraction(), state.windowPercent.fr.toFraction(),
                    state.windowPercent.rl.toFraction(), state.windowPercent.rr.toFraction(),
                )
                renderer.speedKmh = state.speed ?: 0f
                renderer.turnLeft = state.turnLeft == true
                renderer.turnRight = state.turnRight == true
                renderer.brake = (state.brake ?: 0f) / 100f
            },
        )
        Text(
            "拖动旋转 · 双指缩放",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

private fun Boolean?.toOpen() = if (this == true) 1f else 0f
private fun Int?.toFraction() = ((this ?: 0) / 100f).coerceIn(0f, 1f)

private fun Color.toRgba() = floatArrayOf(red, green, blue, alpha)

/** 极简的可变引用，避免为了存一个 View 引入 mutableStateOf 触发额外重组。 */
private class Holder<T> { var value: T? = null }

private fun <T> mutableHolder() = Holder<T>()

/**
 * 承载渲染器的 GLSurfaceView。手势直接改渲染器上的相机参数，不走 Compose，
 * 免得每帧都触发重组。
 */
@SuppressLint("ViewConstructor")
private class CarGlView(context: Context, private val renderer: Car3dRenderer) : GLSurfaceView(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var pinching = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                pinching = false
                // 外层是可滚动的列（竖屏布局），不拦住父级的话一拖就滚页面
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastSpan = spanOf(event)
                pinching = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    val span = spanOf(event)
                    if (lastSpan > 1f && span > 1f) {
                        renderer.distance = (renderer.distance * lastSpan / span).coerceIn(5.5f, 16f)
                    }
                    lastSpan = span
                } else {
                    renderer.yawDeg -= (event.x - lastX) * 0.42f
                    renderer.pitchDeg = (renderer.pitchDeg + (event.y - lastY) * 0.30f).coerceIn(-5f, 78f)
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一根手指后回到单指旋转，基准点要重新取，否则视角会突跳
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                pinching = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun spanOf(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
}

private val BODY_LIGHT = Color(0xFFF2F5F8)
private val BODY_DARK = Color(0xFF59646F)
private val GLASS_LIGHT = Color(0xFF8995A5)
private val GLASS_DARK = Color(0xFF2F3842)
private val INTERIOR_LIGHT = Color(0xFF4A5560)
private val INTERIOR_DARK = Color(0xFF14181C)
private val WHEEL_LIGHT = Color(0xFF32383F)
private val WHEEL_DARK = Color(0xFF191D23)
