package com.dicar.vehicle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.util.Format

/** 动力与行驶卡片：F01-F08 + F37 能量流。 */
@Composable
fun PowerCard(state: VehicleState, modifier: Modifier = Modifier) {
    DataCard(title = "动力行驶", modifier = modifier, trailing = { GearBadge(state.gear) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            BigMetric(Format.num(state.speed), "km/h", "车速")
            BigMetric(Format.num(state.power, 1), "kW", "总功率")
        }
        MetricPair(
            "发动机转速", Format.num(state.engineRpm, unit = "rpm"),
            "发动机功率", Format.num(state.enginePower, 1, "kW"),
        )
        MetricPair(
            "前电机转速", Format.num(state.motorRpmFront, unit = "rpm"),
            "后电机转速", Format.num(state.motorRpmRear, unit = "rpm"),
        )
        MetricRow("电机功率", Format.num(state.motorPower, 1, "kW"))
        PercentBar("油门开度", state.accelerator, MaterialTheme.colorScheme.secondary)
        PercentBar("刹车深度", state.brake, MaterialTheme.colorScheme.error)
        HorizontalDivider()
        MetricPair(
            "工作模式", Format.text(state.workMode),
            "驾驶模式", Format.text(state.driveMode),
        )
        MetricRow("能量流", state.energyFlow ?: Format.NA)
    }
}

@Composable
private fun GearBadge(gear: String?) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            gear ?: "-",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
