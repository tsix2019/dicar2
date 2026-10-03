package com.dicar.vehicle.data.source.bydauto

import android.content.Context
import com.dicar.vehicle.data.model.AcCycleMode
import com.dicar.vehicle.data.model.CommandResult
import com.dicar.vehicle.data.model.DataSourceType
import com.dicar.vehicle.data.model.Openings
import com.dicar.vehicle.data.model.QuickAction
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.Wheels
import com.dicar.vehicle.data.model.Zone
import com.dicar.vehicle.data.source.VehicleDataSource
import com.dicar.vehicle.data.source.bydauto.BydApiMap.Dev
import com.dicar.vehicle.data.source.bydauto.BydApiMap.Src
import com.dicar.vehicle.data.source.bydauto.BydDevice.CallResult
import com.dicar.vehicle.util.ValueSanitizer
import kotlin.math.roundToInt

/**
 * 主数据源：直接反射调用车机 framework 里的 android.hardware.bydauto.*。
 *
 * 读哪个接口、什么编码，全部在 [BydApiMap] 里配置；这里只负责「按表读 → 换算 → 范围校验」。
 * 实车上某项显示 N/A 时：先跑「设置 → 探测 BYDAuto 接口」，对照报告修改 BydApiMap。
 */
class BydAutoDataSource(context: Context) : VehicleDataSource {

    override val type = DataSourceType.BYD_AUTO

    private val ctx = BydPermissionContext(context.applicationContext)
    private val devices = HashMap<Dev, BydDevice>()
    private val featureIds = BydFeatureIds()

    private fun device(dev: Dev) = devices.getOrPut(dev) { BydDevice(ctx, dev.className) }

    override suspend fun isAvailable(): Boolean =
        CORE_DEVICES.any { device(it).isAvailable }

    override suspend fun read(): VehicleState {
        check(CORE_DEVICES.any { device(it).isAvailable }) { "BYDAuto 设备均无法实例化" }
        val m = BydApiMap

        val batteryTempOffset = m.BATTERY_TEMP_OFFSET.toDouble()
        val turnSignal = int(m.TURN_SIGNAL, 0, 7)

        return VehicleState(
            // 动力
            speed = float(m.SPEED, 0.0, 300.0),
            engineRpm = int(m.ENGINE_RPM, 0, 10_000),
            motorRpmFront = int(m.MOTOR_RPM_FRONT, -25_000, 25_000),
            motorRpmRear = int(m.MOTOR_RPM_REAR, -25_000, 25_000),
            power = float(m.POWER, -500.0, 1_000.0),
            accelerator = float(m.ACCELERATOR, 0.0, 100.0),
            brake = float(m.BRAKE, 0.0, 100.0),
            gear = int(m.GEAR, 0, 15)?.let { m.GEAR_LABELS[it] },
            workMode = int(m.WORK_MODE, 0, 15)?.let { m.WORK_MODE_LABELS[it] ?: "模式 $it" },
            driveMode = int(m.DRIVE_MODE, 0, 15)?.let { m.DRIVE_MODE_LABELS[it] ?: "模式 $it" },

            // 电池
            soc = float(m.SOC, 0.0, 100.0),
            batteryTempMax = float(m.BATTERY_TEMP_MAX, -40.0, 100.0, offset = batteryTempOffset),
            batteryTempMin = float(m.BATTERY_TEMP_MIN, -40.0, 100.0, offset = batteryTempOffset),
            batteryVoltage = float(m.BATTERY_VOLTAGE, 50.0, 1_000.0),
            cellVoltageMax = float(m.CELL_VOLTAGE_MAX, 1.0, 5.0, scale = 0.001),
            cellVoltageMin = float(m.CELL_VOLTAGE_MIN, 1.0, 5.0, scale = 0.001),
            batteryCurrent = float(m.BATTERY_CURRENT, -1_000.0, 1_000.0),
            chargeGunConnected = int(m.CHARGE_GUN, 1, 3)?.let { it != 1 },
            chargeStatus = int(m.CHARGE_STATE, 0, 255)?.let { m.CHARGE_STATE_LABELS[it] }
                ?: int(m.CHARGE_GUN, 1, 3)?.let { m.CHARGE_GUN_LABELS[it] },
            remainRangeElec = int(m.RANGE_ELEC, 0, 2_000),
            remainRangeFuel = int(m.RANGE_FUEL, 0, 2_000),
            fuelPercent = float(m.FUEL_PERCENT, 0.0, 100.0),
            voltage12V = float(m.VOLTAGE_12V, 6.0, 20.0),

            // 空调
            acOn = int(m.AC_ON, 0, 1)?.let { it == 1 },
            acAuto = int(m.AC_CONTROL_MODE, 0, 1)?.let { it == 0 },
            acTempDriver = float(m.AC_TEMP_DRIVER, 10.0, 40.0),
            acTempPassenger = float(m.AC_TEMP_PASSENGER, 10.0, 40.0),
            acFanLevel = int(m.AC_FAN_LEVEL, 0, 7),
            acWindMode = int(m.AC_WIND_MODE, 0, 15)?.let { code ->
                m.AC_WIND_MODE_CODES.entries.firstOrNull { it.value == code }?.key
            },
            acCycle = when (int(m.AC_CYCLE, 0, 1)) {
                m.AC_CYCLE_INNER -> AcCycleMode.INNER
                m.AC_CYCLE_OUTER -> AcCycleMode.OUTER
                else -> null
            },
            insideTemp = float(m.INSIDE_TEMP, -50.0, 80.0),
            outsideTemp = float(m.OUTSIDE_TEMP, -50.0, 80.0),
            seatHeatDriver = int(m.SEAT_HEAT_DRIVER, 0, 5),
            seatHeatPassenger = int(m.SEAT_HEAT_PASSENGER, 0, 5),
            seatVentDriver = int(m.SEAT_VENT_DRIVER, 0, 5),
            seatVentPassenger = int(m.SEAT_VENT_PASSENGER, 0, 5),

            // 车身
            doors = Openings(
                fl = bool(m.door(m.DOOR_FL)),
                fr = bool(m.door(m.DOOR_FR)),
                rl = bool(m.door(m.DOOR_RL)),
                rr = bool(m.door(m.DOOR_RR)),
                hood = bool(m.door(m.DOOR_HOOD)),
                trunk = bool(m.door(m.DOOR_TRUNK)),
            ),
            windowPercent = Wheels(
                int(m.WINDOW_FL, 0, 100), int(m.WINDOW_FR, 0, 100),
                int(m.WINDOW_RL, 0, 100), int(m.WINDOW_RR, 0, 100),
            ),
            sunroofPercent = int(m.SUNROOF, 0, 100),
            sunshadePercent = int(m.SUNSHADE, 0, 100),
            tirePressure = Wheels(
                float(m.TIRE_PRESSURE_FL, 0.0, 500.0), float(m.TIRE_PRESSURE_FR, 0.0, 500.0),
                float(m.TIRE_PRESSURE_RL, 0.0, 500.0), float(m.TIRE_PRESSURE_RR, 0.0, 500.0),
            ),
            tireTemp = Wheels(
                float(m.TIRE_TEMP_FL, -40.0, 150.0), float(m.TIRE_TEMP_FR, -40.0, 150.0),
                float(m.TIRE_TEMP_RL, -40.0, 150.0), float(m.TIRE_TEMP_RR, -40.0, 150.0),
            ),
            steeringAngle = float(m.STEERING_ANGLE, -900.0, 900.0),
            seatbeltDriver = bool(m.SEATBELT_DRIVER),
            seatbeltPassenger = bool(m.SEATBELT_PASSENGER),
            turnLeft = turnSignal?.let { it and m.TURN_LEFT_BIT != 0 },
            turnRight = turnSignal?.let { it and m.TURN_RIGHT_BIT != 0 },

            // 其他
            totalMileage = float(m.TOTAL_MILEAGE_KM, 0.0, 2_000_000.0)
                ?: float(m.TOTAL_MILEAGE_DECI_KM, 0.0, 2_000_000.0, scale = 0.1),
            slope = float(m.SLOPE, -60.0, 60.0),

            acTempStep = m.AC_TEMP_STEP,
        )
    }

    // ------------------------------------------------------------------
    // 读取工具：依次尝试来源，第一个「非错误码且换算后在范围内」的值生效
    // ------------------------------------------------------------------

    private fun value(sources: List<Src>, min: Double, max: Double, scale: Double = 1.0, offset: Double = 0.0): Double? {
        for (src in sources) {
            val raw = when (src) {
                is Src.Getter -> device(src.dev).get(src.method, *src.args.toIntArray())
                is Src.Fid -> readFid(src)
            }
            val converted = ValueSanitizer.toDouble(raw)?.let { it * scale + offset } ?: continue
            if (converted in min..max) return converted
        }
        return null
    }

    private fun float(sources: List<Src>, min: Double, max: Double, scale: Double = 1.0, offset: Double = 0.0) =
        value(sources, min, max, scale, offset)?.toFloat()

    private fun int(sources: List<Src>, min: Int, max: Int) =
        value(sources, min.toDouble(), max.toDouble())?.toInt()

    /** 0/1 状态量；255、2 等「未定义/无效」被范围校验挡掉。 */
    private fun bool(sources: List<Src>) = int(sources, 0, 1)?.let { it == 1 }

    private fun readFid(src: Src.Fid): Any? {
        val fid = featureIds.resolve(src.symbol) ?: return null
        val target = device(src.dev).takeIf { it.isAvailable } ?: device(Dev.AC)
        return if (src.isFloat) {
            target.managerGet("getDouble", src.dev.id, fid)
        } else {
            target.get("get", src.dev.id, fid)
        }
    }

    // ------------------------------------------------------------------
    // 控制
    // ------------------------------------------------------------------

    override suspend fun execute(command: VehicleCommand): CommandResult {
        val m = BydApiMap
        return when (command) {
            is VehicleCommand.AcPower ->
                setter(Dev.AC, if (command.on) m.AC_START else m.AC_STOP, m.AC_POWER_ARG)

            is VehicleCommand.AcAuto ->
                setter(Dev.AC, m.AC_SET_CONTROL_MODE, if (command.on) 0 else 1, m.AC_SOURCE)

            is VehicleCommand.AcTemperature -> {
                val zone = if (command.zone == Zone.DRIVER) m.AC_ZONE_DRIVER else m.AC_ZONE_PASSENGER
                setter(Dev.AC, m.AC_SET_TEMP, zone, command.celsius.roundToInt(), m.AC_SOURCE, m.AC_TEMP_UNIT_CELSIUS)
            }

            is VehicleCommand.AcFanLevel ->
                writeFid(Dev.AC, m.AC_WIND_LEVEL_SET_SYMBOL, m.AC_WIND_LEVEL_SET_FALLBACK, command.level)

            is VehicleCommand.AcWind -> {
                val code = m.AC_WIND_MODE_CODES[command.mode]
                    ?: return CommandResult.Unsupported("未配置 ${command.mode.label} 的编码")
                setter(Dev.AC, m.AC_SET_WIND_MODE, code, m.AC_SOURCE)
            }

            is VehicleCommand.AcCycle -> {
                val code = if (command.mode == AcCycleMode.INNER) m.AC_CYCLE_INNER else m.AC_CYCLE_OUTER
                setter(Dev.AC, m.AC_SET_CYCLE, code, m.AC_SOURCE)
            }

            is VehicleCommand.SeatHeat -> seat(
                if (command.seat == Zone.DRIVER) m.SEAT_HEAT_DRIVER_SET else m.SEAT_HEAT_PASSENGER_SET,
                command.level,
            )

            is VehicleCommand.SeatVent -> seat(
                if (command.seat == Zone.DRIVER) m.SEAT_VENT_DRIVER_SET else m.SEAT_VENT_PASSENGER_SET,
                command.level,
            )

            is VehicleCommand.Quick -> when (command.action) {
                QuickAction.FRONT_DEFROST -> writeFid(Dev.AC, m.AC_DEFROST_FRONT_SET_SYMBOL, null, 1)
                QuickAction.QUICK_COOL -> setter(Dev.AC, m.AC_SET_MAX_COOLING, 1)
                QuickAction.PURIFY -> CommandResult.Unsupported("未找到净化接口")
            }
        }
    }

    /** 座椅：档位 0 → 写开关「关」；档位 n → 写开关「开」再写档位。 */
    private fun seat(symbols: Pair<String, String>, level: Int): CommandResult {
        val (switchSymbol, levelSymbol) = symbols
        if (level <= 0) return writeFid(Dev.AC, switchSymbol, null, BydApiMap.SEAT_SWITCH_OFF)
        val switched = writeFid(Dev.AC, switchSymbol, null, BydApiMap.SEAT_SWITCH_ON)
        if (switched != CommandResult.Sent) return switched
        return writeFid(Dev.AC, levelSymbol, null, level)
    }

    private fun setter(dev: Dev, method: String, vararg args: Int): CommandResult =
        toCommandResult(device(dev).call(method, *args), method)

    private fun writeFid(dev: Dev, symbol: String, fallbackFid: Int?, value: Int): CommandResult {
        val fid = featureIds.resolve(symbol) ?: fallbackFid
            ?: return CommandResult.Unsupported("本车固件无 $symbol")
        return toCommandResult(device(dev).call("set", dev.id, fid, value), symbol)
    }

    private fun toCommandResult(result: CallResult, name: String): CommandResult = when (result) {
        CallResult.Missing -> CommandResult.Unsupported("接口不存在：$name")
        is CallResult.Error -> CommandResult.Failed(
            if (result.error is SecurityException) "无权限：${result.error.message}" else result.error.toString()
        )
        is CallResult.Value -> {
            // 返回码：0 / 正数 = 成功；BYDAuto 错误码（-2147482648 失败 / -2147482647 忙 / -2147482646 超时 …）= 失败
            val code = (result.value as? Number)?.toInt()
            if (code != null && ValueSanitizer.isErrorCode(code)) {
                CommandResult.Failed("返回码 $code")
            } else {
                CommandResult.Sent
            }
        }
    }

    private companion object {
        /** 任一可实例化即认为 BYDAuto 可用。 */
        val CORE_DEVICES = listOf(Dev.AC, Dev.STATISTIC, Dev.SPEED, Dev.BODYWORK)
    }
}
