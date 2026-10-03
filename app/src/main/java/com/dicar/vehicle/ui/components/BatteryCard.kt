package com.dicar.vehicle.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.util.Format

/** 电池与能量卡片：F09-F17。 */
@Composable
fun BatteryCard(state: VehicleState, modifier: Modifier = Modifier) {
    DataCard(title = "电池能量", modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            BigMetric(Format.num(state.soc, 1), "%", "SOC")
            BigMetric(Format.num(state.remainRangeElec), "km", "电续航", size = 32.sp)
            BigMetric(Format.num(state.remainRangeFuel), "km", "油续航", size = 32.sp)
        }
        PercentBar("剩余电量", state.soc, MaterialTheme.colorScheme.secondary)

        MetricPair(
            "电池最高温", Format.num(state.batteryTempMax, 1, "℃"),
            "电池最低温", Format.num(state.batteryTempMin, 1, "℃"),
        )
        MetricPair(
            "电池平均温", Format.num(state.batteryTempAvg, 1, "℃"),
            "总电压", Format.num(state.batteryVoltage, 1, "V"),
        )
        MetricPair(
            "单体最高", Format.num(state.cellVoltageMax, 3, "V"),
            "单体最低", Format.num(state.cellVoltageMin, 3, "V"),
        )
        MetricPair(
            "单体压差", Format.num(state.cellVoltageDiffMv, unit = "mV"),
            "12V 电压", Format.num(state.voltage12V, 1, "V"),
        )
        MetricPair(
            "电池电流", Format.num(state.batteryCurrent, 1, "A"),
            "电池功率", Format.num(state.batteryPower, 1, "kW"),
        )
        HorizontalDivider()
        MetricPair(
            "充电枪", when (state.chargeGunConnected) {
                null -> Format.NA
                true -> "已连接"
                false -> "未连接"
            },
            "充电状态", Format.text(state.chargeStatus),
        )
        MetricPair(
            "瞬时电耗", Format.num(state.instantElecConsumption, 1, "kWh/百公里"),
            "瞬时油耗", Format.num(state.instantFuelConsumption, 1, "L/百公里"),
        )
        MetricPair(
            "行程电耗", Format.num(state.tripElecConsumption, 1, "kWh/百公里"),
            "行程油耗", Format.num(state.tripFuelConsumption, 1, "L/百公里"),
        )
        MetricRow("油量", Format.num(state.fuelPercent, unit = "%"))
    }
}
