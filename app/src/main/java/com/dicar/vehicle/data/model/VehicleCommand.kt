package com.dicar.vehicle.data.model

/**
 * 控制指令。
 *
 * 每条指令自带：
 * - [key]：同 key 的指令会被防抖合并（连续点 3 次「温度+」只下发最后一次）；
 * - [applyTo]：乐观更新，下发前先改 UI，避免等车机回读期间界面跳回旧值；
 * - [expected] / [readBack]：回读确认。轮询到的 readBack == expected 即视为生效；
 *   readBack 为 null（该字段读不到）时无法确认，只提示“已下发”。
 */
sealed interface VehicleCommand {
    val key: String
    val label: String
    val expected: Any?
    fun readBack(state: VehicleState): Any?
    fun applyTo(state: VehicleState): VehicleState

    data class AcPower(val on: Boolean) : VehicleCommand {
        override val key = "ac_power"
        override val label = if (on) "打开空调" else "关闭空调"
        override val expected = on
        override fun readBack(state: VehicleState) = state.acOn
        override fun applyTo(state: VehicleState) = state.copy(acOn = on)
    }

    data class AcAuto(val on: Boolean) : VehicleCommand {
        override val key = "ac_auto"
        override val label = if (on) "空调自动" else "退出自动"
        override val expected = on
        override fun readBack(state: VehicleState) = state.acAuto
        override fun applyTo(state: VehicleState) = state.copy(acAuto = on)
    }

    data class AcTemperature(val zone: Zone, val celsius: Float) : VehicleCommand {
        override val key = "ac_temp_$zone"
        override val label = "${zone.label}温度 $celsius℃"
        override val expected = celsius
        override fun readBack(state: VehicleState) = when (zone) {
            Zone.DRIVER -> state.acTempDriver
            Zone.PASSENGER -> state.acTempPassenger
        }
        override fun applyTo(state: VehicleState) = when (zone) {
            Zone.DRIVER -> state.copy(acTempDriver = celsius)
            Zone.PASSENGER -> state.copy(acTempPassenger = celsius)
        }
    }

    data class AcFanLevel(val level: Int) : VehicleCommand {
        override val key = "ac_fan"
        override val label = "风量 $level 档"
        override val expected = level
        override fun readBack(state: VehicleState) = state.acFanLevel
        override fun applyTo(state: VehicleState) = state.copy(acFanLevel = level)
    }

    data class AcWind(val mode: AcWindMode) : VehicleCommand {
        override val key = "ac_wind"
        override val label = "出风 ${mode.label}"
        override val expected = mode
        override fun readBack(state: VehicleState) = state.acWindMode
        override fun applyTo(state: VehicleState) = state.copy(acWindMode = mode)
    }

    data class AcCycle(val mode: AcCycleMode) : VehicleCommand {
        override val key = "ac_cycle"
        override val label = mode.label
        override val expected = mode
        override fun readBack(state: VehicleState) = state.acCycle
        override fun applyTo(state: VehicleState) = state.copy(acCycle = mode)
    }

    data class SeatHeat(val seat: Zone, val level: Int) : VehicleCommand {
        override val key = "seat_heat_$seat"
        override val label = "${seat.label}座椅加热 $level 档"
        override val expected = level
        override fun readBack(state: VehicleState) = when (seat) {
            Zone.DRIVER -> state.seatHeatDriver
            Zone.PASSENGER -> state.seatHeatPassenger
        }
        override fun applyTo(state: VehicleState) = when (seat) {
            Zone.DRIVER -> state.copy(seatHeatDriver = level)
            Zone.PASSENGER -> state.copy(seatHeatPassenger = level)
        }
    }

    data class SeatVent(val seat: Zone, val level: Int) : VehicleCommand {
        override val key = "seat_vent_$seat"
        override val label = "${seat.label}座椅通风 $level 档"
        override val expected = level
        override fun readBack(state: VehicleState) = when (seat) {
            Zone.DRIVER -> state.seatVentDriver
            Zone.PASSENGER -> state.seatVentPassenger
        }
        override fun applyTo(state: VehicleState) = when (seat) {
            Zone.DRIVER -> state.copy(seatVentDriver = level)
            Zone.PASSENGER -> state.copy(seatVentPassenger = level)
        }
    }

    /** 一键类功能（净化 / 速冷 / 前除霜）：没有可回读的状态，下发成功即结束。 */
    data class Quick(val action: QuickAction) : VehicleCommand {
        override val key = "quick_$action"
        override val label = action.label
        override val expected: Any? = null
        override fun readBack(state: VehicleState): Any? = null
        override fun applyTo(state: VehicleState) = state
    }
}

enum class Zone(val label: String) { DRIVER("主驾"), PASSENGER("副驾") }

enum class QuickAction(val label: String) {
    PURIFY("一键净化"),
    QUICK_COOL("快速降温"),
    FRONT_DEFROST("前挡除霜"),
}

/** 数据源执行指令的结果（只代表“下发”结果，是否真正生效由 Repository 回读确认）。 */
sealed interface CommandResult {
    data object Sent : CommandResult
    data class Unsupported(val reason: String) : CommandResult
    data class Failed(val reason: String) : CommandResult
}
