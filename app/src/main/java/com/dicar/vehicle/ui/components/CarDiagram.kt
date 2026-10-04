package com.dicar.vehicle.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.dicar.vehicle.data.model.Radar
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.util.Format

/**
 * 车辆俯视孪生图：车头朝上。
 *
 * 画面上的每个元素都绑定实时状态——车门开/关、车窗与天窗开度、四轮胎压胎温、
 * 转向灯、以及前后泊车雷达的障碍等级。数据缺失的部件画成灰色轮廓。
 */
@Composable
fun CarDiagram(state: VehicleState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val body = scheme.surfaceContainerHigh
    val outline = scheme.outline
    val ok = scheme.primary
    val warn = scheme.error
    val glass = scheme.primary.copy(alpha = 0.30f)
    val onSurface = scheme.onSurface
    val muted = scheme.onSurfaceVariant
    val signal = scheme.tertiary

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.30f
        val bodyH = h * 0.68f
        val left = (w - bodyW) / 2f
        val top = h * 0.16f
        val right = left + bodyW
        val bottom = top + bodyH
        val corner = bodyW * 0.22f

        val text = Paint().apply {
            isAntiAlias = true
            textSize = h * 0.035f
            textAlign = Paint.Align.CENTER
        }

        // ---- 车身 ----
        drawRoundRect(
            color = body,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        )
        drawRoundRect(
            color = outline.copy(alpha = 0.6f),
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(width = h * 0.004f),
        )

        // 前后风挡
        drawRoundRect(
            color = glass,
            topLeft = Offset(left + bodyW * 0.14f, top + bodyH * 0.13f),
            size = Size(bodyW * 0.72f, bodyH * 0.11f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * 0.4f, corner * 0.4f),
        )
        drawRoundRect(
            color = glass,
            topLeft = Offset(left + bodyW * 0.14f, top + bodyH * 0.74f),
            size = Size(bodyW * 0.72f, bodyH * 0.10f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * 0.4f, corner * 0.4f),
        )

        // ---- 天窗（按开度填充）----
        val roofRect = Rect(
            left + bodyW * 0.22f, top + bodyH * 0.29f,
            right - bodyW * 0.22f, top + bodyH * 0.42f,
        )
        drawRect(color = outline.copy(alpha = 0.5f), topLeft = roofRect.topLeft, size = roofRect.size, style = Stroke(h * 0.003f))
        state.sunroofPercent?.let { pct ->
            if (pct > 0) drawRect(
                color = ok.copy(alpha = 0.55f),
                topLeft = roofRect.topLeft,
                size = Size(roofRect.width, roofRect.height * (pct / 100f)),
            )
        }

        // ---- 四门 + 车窗开度 ----
        val doorH = bodyH * 0.19f
        val doorW = bodyW * 0.085f
        fun door(isLeft: Boolean, isFront: Boolean, open: Boolean?, windowPct: Int?) {
            val x = if (isLeft) left - doorW * 0.35f else right - doorW * 0.65f
            val y = top + bodyH * (if (isFront) 0.30f else 0.53f)
            val color = when (open) {
                true -> warn
                false -> outline
                null -> outline.copy(alpha = 0.35f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(doorW, doorH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(doorW * 0.4f, doorW * 0.4f),
            )
            // 车窗开度：从上往下填
            windowPct?.takeIf { it > 0 }?.let { pct ->
                drawRoundRect(
                    color = ok,
                    topLeft = Offset(x, y),
                    size = Size(doorW, doorH * (pct / 100f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(doorW * 0.4f, doorW * 0.4f),
                )
            }
        }
        door(isLeft = true, isFront = true, open = state.doors.fl, windowPct = state.windowPercent.fl)
        door(isLeft = false, isFront = true, open = state.doors.fr, windowPct = state.windowPercent.fr)
        door(isLeft = true, isFront = false, open = state.doors.rl, windowPct = state.windowPercent.rl)
        door(isLeft = false, isFront = false, open = state.doors.rr, windowPct = state.windowPercent.rr)

        // ---- 引擎盖 / 后备箱 ----
        fun lid(isHood: Boolean, open: Boolean?) {
            val y = if (isHood) top + bodyH * 0.035f else bottom - bodyH * 0.075f
            drawRoundRect(
                color = when (open) {
                    true -> warn
                    false -> outline.copy(alpha = 0.7f)
                    null -> outline.copy(alpha = 0.3f)
                },
                topLeft = Offset(left + bodyW * 0.22f, y),
                size = Size(bodyW * 0.56f, bodyH * 0.04f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyH * 0.02f, bodyH * 0.02f),
            )
        }
        lid(isHood = true, open = state.doors.hood)
        lid(isHood = false, open = state.doors.trunk)

        // ---- 四轮 + 胎压胎温 ----
        val tyreW = bodyW * 0.15f
        val tyreH = bodyH * 0.15f
        fun tyre(isLeft: Boolean, isFront: Boolean, kpa: Float?, temp: Float?) {
            val x = if (isLeft) left - tyreW * 0.72f else right - tyreW * 0.28f
            val y = top + bodyH * (if (isFront) 0.11f else 0.70f)
            val color = when {
                kpa == null -> outline.copy(alpha = 0.35f)
                kpa < 190f || kpa > 300f -> warn
                else -> onSurface.copy(alpha = 0.75f)
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(tyreW, tyreH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(tyreW * 0.35f, tyreW * 0.35f),
            )
            val labelX = if (isLeft) x - tyreW * 0.25f else x + tyreW * 1.25f
            text.textAlign = if (isLeft) Paint.Align.RIGHT else Paint.Align.LEFT
            drawIntoCanvas { canvas ->
                text.color = (if (kpa == null) outline else onSurface).toArgb()
                canvas.nativeCanvas.drawText(Format.num(kpa, unit = "kPa"), labelX, y + tyreH * 0.45f, text)
                text.color = muted.toArgb()
                canvas.nativeCanvas.drawText(Format.num(temp, unit = "℃"), labelX, y + tyreH * 1.1f, text)
            }
        }
        tyre(isLeft = true, isFront = true, kpa = state.tirePressure.fl, temp = state.tireTemp.fl)
        tyre(isLeft = false, isFront = true, kpa = state.tirePressure.fr, temp = state.tireTemp.fr)
        tyre(isLeft = true, isFront = false, kpa = state.tirePressure.rl, temp = state.tireTemp.rl)
        tyre(isLeft = false, isFront = false, kpa = state.tirePressure.rr, temp = state.tireTemp.rr)

        // ---- 转向灯 ----
        fun turnArrow(isLeft: Boolean, on: Boolean?) {
            if (on != true) return
            val cx = if (isLeft) left - bodyW * 0.30f else right + bodyW * 0.30f
            val cy = top + bodyH * 0.02f
            val s = bodyW * 0.13f
            val path = Path().apply {
                if (isLeft) {
                    moveTo(cx - s, cy); lineTo(cx + s * 0.4f, cy - s * 0.8f); lineTo(cx + s * 0.4f, cy + s * 0.8f)
                } else {
                    moveTo(cx + s, cy); lineTo(cx - s * 0.4f, cy - s * 0.8f); lineTo(cx - s * 0.4f, cy + s * 0.8f)
                }
                close()
            }
            drawPath(path, signal)
        }
        turnArrow(isLeft = true, on = state.turnLeft)
        turnArrow(isLeft = false, on = state.turnRight)

        // ---- 泊车雷达 ----
        drawRadar(state.radar.front, Offset(left + bodyW / 2, top), upward = true, bodyW = bodyW, bodyH = bodyH, warn = warn, ok = ok, idle = outline)
        drawRadar(state.radar.rear, Offset(left + bodyW / 2, bottom), upward = false, bodyW = bodyW, bodyH = bodyH, warn = warn, ok = ok, idle = outline)

        // ---- 车身中央：档位 ----
        text.textAlign = Paint.Align.CENTER
        drawIntoCanvas { canvas ->
            text.color = onSurface.toArgb()
            text.textSize = h * 0.075f
            text.isFakeBoldText = true
            canvas.nativeCanvas.drawText(
                state.gear ?: Format.NA,
                left + bodyW / 2, top + bodyH * 0.63f, text,
            )
        }
    }
}

/**
 * 画车头/车尾的雷达弧。等级越小表示障碍越近，点亮的弧越多、颜色越偏警示色。
 * [levels] 是该侧各探头的等级（null 表示该探头没探到东西）。
 */
private fun DrawScope.drawRadar(
    levels: List<Int?>,
    origin: Offset,
    upward: Boolean,
    bodyW: Float,
    bodyH: Float,
    warn: Color,
    ok: Color,
    idle: Color,
) {
    val nearest = levels.filterNotNull().minOrNull()
    val arcs = 3
    val gap = bodyH * 0.035f
    for (i in 0 until arcs) {
        // 第 0 条最靠近车身。障碍越近（等级越小），点亮的弧越多
        val lit = nearest != null && nearest <= (i + 1) * (Radar.OBSTACLE_MAX / arcs.toFloat())
        val color = when {
            !lit -> idle.copy(alpha = 0.22f)
            i == 0 -> warn
            i == 1 -> warn.copy(alpha = 0.7f)
            else -> ok
        }
        val r = bodyW * 0.30f + gap * (i + 1)
        val topLeft = Offset(origin.x - r, origin.y - r)
        drawArc(
            color = color,
            startAngle = if (upward) 200f else 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(r * 2, r * 2),
            style = Stroke(width = bodyH * 0.012f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
