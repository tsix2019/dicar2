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

        HorizontalDivider()
        MetricRow("方向盘转角", Format.num(state.steeringAngle, 1, "°"))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MetricRow(
                "主驾安全带", seatbelt(state.seatbeltDriver), Modifier.weight(1f),
                valueColor = if (state.seatbeltDriver == false) warn else Color.Unspecified,
            )
            MetricRow(
                "副驾安全带", seatbelt(state.seatbeltPassenger), Modifier.weight(1f),
                valueColor = if (state.seatbeltPassenger == false) warn else Color.Unspecified,
            )
        }
        MetricPair(
            "左转向灯", Format.onOff(state.turnLeft),
            "右转向灯", Format.onOff(state.turnRight),
        )
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

private fun seatbelt(fastened: Boolean?) = when (fastened) {
    null -> Format.NA
    true -> "已系"
    false -> "未系"
}
