package com.dicar.vehicle.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dicar.vehicle.data.model.Radar
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.Wheels
import com.dicar.vehicle.util.Format

/** 车身与安全卡片：F26-F32。 */
@Composable
fun BodyCard(state: VehicleState, modifier: Modifier = Modifier) {
    val warn = MaterialTheme.colorScheme.tertiary
    DataCard(title = "车身安全", modifier = modifier) {
        SectionLabel("胎压 / 胎温")
        WheelGrid(state.tirePressure, state.tireTemp)

        HorizontalDivider()
        SectionLabel("四门两盖")
        val doors = state.doors
        MetricPair("左前门", Format.openClose(doors.fl), "右前门", Format.openClose(doors.fr))
        MetricPair("左后门", Format.openClose(doors.rl), "右后门", Format.openClose(doors.rr))
        MetricPair("引擎盖", Format.openClose(doors.hood), "后备箱", Format.openClose(doors.trunk))

        HorizontalDivider()
        SectionLabel("车窗 / 天窗")
        val w = state.windowPercent
        MetricPair("左前窗", Format.num(w.fl, unit = "%"), "右前窗", Format.num(w.fr, unit = "%"))
        MetricPair("左后窗", Format.num(w.rl, unit = "%"), "右后窗", Format.num(w.rr, unit = "%"))
        MetricPair(
            "天窗", Format.num(state.sunroofPercent, unit = "%"),
            "遮阳帘", Format.num(state.sunshadePercent, unit = "%"),
        )
        MetricRow("车窗防夹", Format.onOff(state.windowAntiPinch))

        HorizontalDivider()
        MetricRow("方向盘转角", Format.num(state.steeringAngle, 1, "°"))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MetricRow(
                "主驾安全带", seatbelt(state.seatbeltDriver), Modifier.weight(1f),
                valueColor = if (state.seatbeltDriver == false) warn else Color.Unspecified,
            )
            // 副驾没人坐时安全带信号本来就不可信，直接显示「无人」
            MetricRow(
                "副驾安全带",
                if (state.passengerPresent == false) "无人" else seatbelt(state.seatbeltPassenger),
                Modifier.weight(1f),
                valueColor = if (state.passengerPresent != false && state.seatbeltPassenger == false) warn else Color.Unspecified,
            )
        }
        MetricPair(
            "左转向灯", Format.onOff(state.turnLeft),
            "右转向灯", Format.onOff(state.turnRight),
        )

        HorizontalDivider()
        SectionLabel("泊车雷达")
        val radar = state.radar
        MetricRow("倒车档", Format.onOff(radar.reverseSwitchOn))
        // 逐探头列出来：等级到实际厘米的对应关系还没在实车核对，先原样展示，
        // 方便对着车位边挪边看哪个探头在变（BydApiMap 里这项标的是 [?]）
        MetricPair("前左", probe(radar.frontLeft), "前左中", probe(radar.frontLeftMid))
        MetricPair("前右中", probe(radar.frontRightMid), "前右", probe(radar.frontRight))
        MetricPair("后左", probe(radar.rearLeft), "后中", probe(radar.rearMid))
        MetricPair("后右", probe(radar.rearRight), "侧左", probe(radar.left))
        MetricRow("侧右", probe(radar.right))
    }
}

/** 其他信息：F33-F35。 */
@Composable
fun MiscCard(state: VehicleState, modifier: Modifier = Modifier) {
    DataCard(title = "其他信息", modifier = modifier) {
        MetricPair(
            "总里程", Format.num(state.totalMileage, unit = "km"),
            "小计里程", Format.num(state.tripMileage, 1, "km"),
        )
        MetricPair(
            "海拔", Format.num(state.altitude, unit = "m"),
            "坡度", Format.num(state.slope, 1, "°"),
        )
        MetricRow("PM2.5", Format.num(state.pm25, unit = "μg/m³"))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WheelGrid(pressure: Wheels<Float>, temp: Wheels<Float>) {
    @Composable
    fun cell(label: String, p: Float?, t: Float?, modifier: Modifier) {
        Column(modifier) {
            MetricRow(label, Format.num(p, unit = "kPa"))
            MetricRow("", Format.num(t, unit = "℃"))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        cell("左前", pressure.fl, temp.fl, Modifier.weight(1f))
        cell("右前", pressure.fr, temp.fr, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        cell("左后", pressure.rl, temp.rl, Modifier.weight(1f))
        cell("右后", pressure.rr, temp.rr, Modifier.weight(1f))
    }
}

/**
 * 单个雷达探头的读数。车机常量里 0..6 是距离档（越小越近），14 表示安全/无障碍，
 * 其余值按读不到处理。档位和实际厘米的对应关系尚未在实车核对。
 */
private fun probe(level: Int?): String = when {
    level == null -> Format.NA
    level == Radar.SAFE -> "无障碍"
    level in Radar.OBSTACLE_MIN..Radar.OBSTACLE_MAX -> "$level 档"
    else -> Format.NA
}

private fun seatbelt(fastened: Boolean?) = when (fastened) {
    null -> Format.NA
    true -> "已系"
    false -> "未系"
}
