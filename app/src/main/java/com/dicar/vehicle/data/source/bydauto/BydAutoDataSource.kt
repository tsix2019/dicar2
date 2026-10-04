package com.dicar.vehicle.data.source.bydauto

import android.content.Context
import android.util.Log
import com.dicar.vehicle.data.model.AcCycleMode
import com.dicar.vehicle.data.model.CommandResult
import com.dicar.vehicle.data.model.DataSourceType
import com.dicar.vehicle.data.model.Openings
import com.dicar.vehicle.data.model.Radar
import com.dicar.vehicle.data.model.QuickAction
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.Wheels
import com.dicar.vehicle.data.model.Zone
import com.dicar.vehicle.data.source.VehicleDataSource
import com.dicar.vehicle.data.source.bydauto.BydApiMap.Dev
import com.dicar.vehicle.data.source.bydauto.BydApiMap.Src
import com.dicar.vehicle.data.source.bydauto.BydDevice.CallResult
import com.dicar.vehicle.data.source.bydauto.adb.AdbTransport
import com.dicar.vehicle.util.ValueSanitizer
import kotlin.math.roundToInt

/**
 * 主数据源：反射调用车机 framework 的 android.hardware.bydauto.*。
 *
 * 两条通道（见 [BydAccess]）自动择一：
 * - [AdbBydAccess]：车机调试口开着时走辅助进程（调试身份），多数 DiLink 只有这条能真正读到数据；
 * - [InProcessBydAccess]：App 自身进程直连（权限允许时）。
 *
 * 读哪个接口、什么编码全部在 [BydApiMap] 配置；这里只做「按表读 → 换算 → 范围校验」。
 * 实车上某项 N/A 时：跑「设置 → 探测 BYDAuto 接口」，对照报告改 BydApiMap。
 */
class BydAutoDataSource(context: Context, transport: AdbTransport) : VehicleDataSource {

    override val type = DataSourceType.BYD_AUTO

    private val featureIds = BydFeatureIds()
    private val adb = AdbBydAccess(transport)
    private val inProcess = InProcessBydAccess(context)

    private var active: BydAccess? = null
    private lateinit var current: BydAccess // 本次 read/execute 使用的通道

    private fun pickAccess(): BydAccess? = when {
        adb.probeAvailable() -> adb
        inProcess.probeAvailable() -> inProcess
        else -> null
    }

    override suspend fun isAvailable(): Boolean {
        active = pickAccess()
        return active != null
    }

    /**
     * 当前可用的读写通道，供「探测 BYDAuto 接口」复用——探测工具必须走同一条通道，
     * 否则在进程内调用会全部 permission deny，报告里看不到任何真实读数。
     */
    fun acquireAccess(): BydAccess? = runCatching { acquire() }.getOrNull()

    /** 建立/校验通道并记为当前通道；不可用时抛出带原因的异常。 */
    private fun acquire(): BydAccess {
        val a = active ?: pickAccess()?.also { active = it }
            ?: throw IllegalStateException("未检测到车机调试口（127.0.0.1:5555）；请开启无线调试")
        a.ensureReady()
        current = a
        return a
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    // 两遍构建：第一遍把映射表里用到的来源全部登记下来，一次批量取回，第二遍再用缓存填值。
    // 这样一轮轮询只有一次跨进程往返，而不是每个字段一次。
    private var collecting = false
    private val collected = LinkedHashSet<Src>()
    private var cache: Map<Src, CallResult> = emptyMap()

    override suspend fun read(): VehicleState {
        acquire()

        collecting = true
        collected.clear()
        cache = emptyMap()
        buildState() // 丢弃结果，只为收集
        collecting = false

        cache = fetchAll(collected.toList())
        return buildState()
    }

    private fun fetchAll(sources: List<Src>): Map<Src, CallResult> {
        val result = HashMap<Src, CallResult>(sources.size)
        val keys = ArrayList<Src>(sources.size)
        val requests = ArrayList<RawReq>(sources.size)
        for (src in sources) when (src) {
            is Src.Getter -> {
                keys += src
                requests += RawReq.Getter(src.dev.className, src.method, src.args.toIntArray())
            }
            is Src.Fid -> {
                val fid = featureIds.resolve(src.symbol)
                if (fid == null) result[src] = CallResult.Missing // 本车固件没这个符号，不必上线问
                else {
                    keys += src
                    requests += RawReq.Fid(src.dev.id, fid, src.isFloat)
                }
            }
        }
        val replies = current.readBatch(requests)
        keys.forEachIndexed { i, key -> result[key] = replies.getOrElse(i) { CallResult.Missing } }
        return result
    }

    private fun buildState(): VehicleState {
        val m = BydApiMap
        val tempOffset = m.BATTERY_TEMP_OFFSET.toDouble()
        val turnSignal = int(m.TURN_SIGNAL_MASK, 0, m.TURN_SIGNAL_MAX)

        return VehicleState(
            // 动力
            speed = float(m.SPEED, 0.0, 300.0),
            // 上限用车机自己的 ENGINE_SPEED_MAX：EV 行驶时该信号是 8191(0x1FFF) 无效标记，必须挡掉
            engineRpm = int(m.ENGINE_RPM, 0, m.ENGINE_RPM_MAX),
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
            batteryTempMax = float(m.BATTERY_TEMP_MAX, -40.0, 100.0, offset = tempOffset),
            batteryTempMin = float(m.BATTERY_TEMP_MIN, -40.0, 100.0, offset = tempOffset),
            batteryVoltage = float(m.BATTERY_VOLTAGE, 50.0, 1_000.0),
            cellVoltageMax = float(m.CELL_VOLTAGE_MAX, 1.0, 5.0, scale = 0.001),
            cellVoltageMin = float(m.CELL_VOLTAGE_MIN, 1.0, 5.0, scale = 0.001),
            batteryCurrent = float(m.BATTERY_CURRENT, -1_000.0, 1_000.0),
            chargeGunConnected = int(m.CHARGE_GUN, 1, 5)?.let { it >= 2 },
            chargeStatus = int(m.CHARGE_STATE, 0, 255)?.let { m.CHARGE_STATE_LABELS[it] ?: "状态 $it" }
                ?: int(m.CHARGE_GUN, 1, 5)?.let { m.CHARGE_GUN_LABELS[it] },
            remainRangeElec = int(m.RANGE_ELEC, 0, 2_000),
            remainRangeFuel = int(m.RANGE_FUEL, 0, 2_000),
            fuelPercent = float(m.FUEL_PERCENT, 0.0, 100.0),
            instantElecConsumption = float(m.INSTANT_ELEC, 0.0, 100.0),
            instantFuelConsumption = float(m.INSTANT_FUEL, 0.0, 100.0),
            tripElecConsumption = float(m.TRIP_ELEC, 0.0, 100.0),
            tripFuelConsumption = float(m.TRIP_FUEL, 0.0, 100.0),
            voltage12V = float(m.VOLTAGE_12V, 6.0, 20.0),

            // 空调
            acOn = int(m.AC_ON, 0, 1)?.let { it == 1 },
            acAuto = int(m.AC_CONTROL_MODE, 0, 1)?.let { it == 0 },
            acTempDriver = float(m.AC_TEMP_DRIVER, 10.0, 40.0),
            acTempPassenger = float(m.AC_TEMP_PASSENGER, 10.0, 40.0),
            acFanLevel = int(m.AC_FAN_LEVEL, 0, 7),
            acWindMode = int(m.AC_WIND_MODE, 0, 15)?.let { code -> m.AC_WIND_MODE_CODES.entries.firstOrNull { it.value == code }?.key },
            acCycle = when (int(m.AC_CYCLE, 0, 1)) {
                m.AC_CYCLE_INNER -> AcCycleMode.INNER
                m.AC_CYCLE_OUTER -> AcCycleMode.OUTER
                else -> null
            },
            insideTemp = float(m.INSIDE_TEMP, -50.0, 80.0),
            outsideTemp = float(m.OUTSIDE_TEMP, -50.0, 80.0),
            seatHeatDriver = seatLevel(m.SEAT_HEAT_DRIVER),
            seatHeatPassenger = seatLevel(m.SEAT_HEAT_PASSENGER),
            seatVentDriver = seatLevel(m.SEAT_VENT_DRIVER),
            seatVentPassenger = seatLevel(m.SEAT_VENT_PASSENGER),

            // 车身
            doors = Openings(
                fl = bool(m.door(m.DOOR_FL)), fr = bool(m.door(m.DOOR_FR)),
                rl = bool(m.door(m.DOOR_RL)), rr = bool(m.door(m.DOOR_RR)),
                hood = bool(m.door(m.DOOR_HOOD)), trunk = bool(m.door(m.DOOR_TRUNK)),
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
            passengerPresent = bool(m.PASSENGER_PRESENT),
            windowAntiPinch = int(m.WINDOW_ANTI_PINCH, 0, 10)?.let { it != m.WINDOW_NO_ANTI_PINCH },
            turnLeft = turnSignal?.let { it and m.TURN_LEFT_BIT != 0 },
            turnRight = turnSignal?.let { it and m.TURN_RIGHT_BIT != 0 },
            radar = Radar(
                frontLeft = radarLevel(m.radar(m.RADAR_LEFT_FRONT)),
                frontLeftMid = radarLevel(m.radar(m.RADAR_FRONT_LEFT_MID)),
                frontRightMid = radarLevel(m.radar(m.RADAR_FRONT_RIGHT_MID)),
                frontRight = radarLevel(m.radar(m.RADAR_RIGHT_FRONT)),
                rearLeft = radarLevel(m.radar(m.RADAR_LEFT_REAR)),
                rearMid = radarLevel(m.radar(m.RADAR_MIDDLE_REAR)),
                rearRight = radarLevel(m.radar(m.RADAR_RIGHT_REAR)),
                left = radarLevel(m.radar(m.RADAR_LEFT)),
                right = radarLevel(m.radar(m.RADAR_RIGHT)),
                reverseSwitchOn = bool(m.RADAR_REVERSE_SWITCH),
            ),

            // 其他
            totalMileage = float(m.TOTAL_MILEAGE_KM, 0.0, 2_000_000.0)
                ?: float(m.TOTAL_MILEAGE_DECI_KM, 0.0, 2_000_000.0, scale = 0.1),
            pm25 = int(m.PM25, 0, 3_000),
            slope = float(m.SLOPE, -60.0, 60.0),

            acTempStep = m.AC_TEMP_STEP,
            seatMaxLevel = m.SEAT_MAX_LEVEL,
        )
    }

    // 依次尝试来源，第一个「非错误码且换算后在范围内」的值生效
    private fun value(sources: List<Src>, min: Double, max: Double, scale: Double = 1.0, offset: Double = 0.0): Double? {
        for (src in sources) {
            val converted = ValueSanitizer.toDouble(rawValue(src))?.let { it * scale + offset } ?: continue
            if (converted in min..max) return converted
        }
        return null
    }

    private fun float(sources: List<Src>, min: Double, max: Double, scale: Double = 1.0, offset: Double = 0.0) =
        value(sources, min, max, scale, offset)?.toFloat()

    private fun int(sources: List<Src>, min: Int, max: Int) =
        value(sources, min.toDouble(), max.toDouble())?.toInt()

    private fun bool(sources: List<Src>) = int(sources, 0, 1)?.let { it == 1 }

    /** 座椅状态 1 关 / 2 低 / 3 高 → App 档位 0/1/2。 */
    private fun seatLevel(sources: List<Src>) =
        int(sources, 0, 3)?.let { (it - 1).coerceIn(0, BydApiMap.SEAT_MAX_LEVEL) }

    /** 雷达障碍等级：0..6 为实测到的距离档，14 表示安全；原样保留，由 [Radar] 判定。 */
    private fun radarLevel(sources: List<Src>) = int(sources, Radar.OBSTACLE_MIN, Radar.SAFE)

    private fun rawValue(src: Src): Any? {
        if (collecting) {
            collected += src // 收集阶段一律返回 null，好让每个候选来源都被登记
            return null
        }
        val raw = (cache[src] as? CallResult.Value)?.value ?: return null
        return if (src is Src.Getter && src.index != null && raw is IntArray) raw.getOrNull(src.index) else raw
    }

    // ------------------------------------------------------------------
    // 控制
    // ------------------------------------------------------------------

    override suspend fun execute(command: VehicleCommand): CommandResult {
        acquire()
        val m = BydApiMap
        return when (command) {
            is VehicleCommand.AcPower -> namedThenFid(
                Dev.AC, if (command.on) m.AC_START else m.AC_STOP, intArrayOf(m.AC_POWER_ARG),
                m.AC_POWER_SET_FID, null, if (command.on) 1 else 0,
            )

            is VehicleCommand.AcAuto -> {
                val mode = if (command.on) 0 else 1 // 0 自动 / 1 手动
                namedThenFid(Dev.AC, m.AC_SET_CONTROL_MODE, intArrayOf(mode, m.AC_SOURCE), m.AC_CONTROL_MODE_SET_FID, null, mode)
            }

            is VehicleCommand.AcTemperature -> {
                val zone = if (command.zone == Zone.DRIVER) m.AC_ZONE_DRIVER else m.AC_ZONE_PASSENGER
                val temp = command.celsius.roundToInt()
                val fidSym = if (command.zone == Zone.DRIVER) m.AC_TEMP_DRIVER_SET_FID else m.AC_TEMP_PASSENGER_SET_FID
                namedThenFid(Dev.AC, m.AC_SET_TEMP, intArrayOf(zone, temp, m.AC_SOURCE, m.AC_TEMP_UNIT_CELSIUS), fidSym, null, temp)
            }

            is VehicleCommand.AcFanLevel ->
                toCommandResult(writeFidSym(Dev.AC, m.AC_WIND_LEVEL_SET_FID, m.AC_WIND_LEVEL_SET_FALLBACK, command.level), "风量")

            is VehicleCommand.AcWind -> {
                val code = m.AC_WIND_MODE_CODES[command.mode]
                    ?: return CommandResult.Unsupported("未配置 ${command.mode.label} 的编码")
                namedThenFid(Dev.AC, m.AC_SET_WIND_MODE, intArrayOf(code, m.AC_SOURCE), m.AC_WIND_MODE_SET_FID, null, code)
            }

            is VehicleCommand.AcCycle -> {
                val code = if (command.mode == AcCycleMode.INNER) m.AC_CYCLE_INNER else m.AC_CYCLE_OUTER
                namedThenFid(Dev.AC, m.AC_SET_CYCLE, intArrayOf(code, m.AC_SOURCE), m.AC_CYCLE_SET_FID, null, code)
            }

            is VehicleCommand.SeatHeat -> seat(command.seat, command.level, m.SEAT_SET_HEAT,
                if (command.seat == Zone.DRIVER) m.SEAT_HEAT_DRIVER_SET_FID else m.SEAT_HEAT_PASSENGER_SET_FID)

            is VehicleCommand.SeatVent -> seat(command.seat, command.level, m.SEAT_SET_VENT,
                if (command.seat == Zone.DRIVER) m.SEAT_VENT_DRIVER_SET_FID else m.SEAT_VENT_PASSENGER_SET_FID)

            is VehicleCommand.Glass -> {
                val symbol = m.GLASS_SET_FIDS[command.zone]
                    ?: return CommandResult.Unsupported("未配置 ${command.zone.label} 的写入口")
                val percent = if (command.open) m.GLASS_OPEN_PERCENT else m.GLASS_CLOSE_PERCENT
                toCommandResult(writeFidSym(Dev.BODYWORK, symbol, null, percent), command.zone.label)
            }

            is VehicleCommand.Quick -> when (command.action) {
                QuickAction.FRONT_DEFROST -> namedThenFid(
                    Dev.AC, m.AC_SET_DEFROST, intArrayOf(1, 1, m.AC_SOURCE), m.AC_DEFROST_FRONT_SET_FID, null, 1)
                QuickAction.QUICK_COOL -> toCommandResult(writeFidSym(Dev.AC, m.AC_MAX_COOLING_SET_FID, null, 1), "快速降温")
                QuickAction.PURIFY -> toCommandResult(writeFidSym(Dev.AC, m.AC_QUICK_CLEAN_SET_FID, null, 1), "一键净化")
            }
        }
    }

    /** 座椅：档位 n → 状态 n+1（1 关 / 2 低 / 3 高）。先试命名接口，缺失则写 FID。 */
    private fun seat(zone: Zone, level: Int, method: String, fidSymbol: String): CommandResult {
        val area = if (zone == Zone.DRIVER) BydApiMap.SEAT_DRIVER else BydApiMap.SEAT_PASSENGER
        val state = level + 1
        return namedThenFid(Dev.SETTING, method, intArrayOf(area, state), fidSymbol, null, state)
    }

    private fun namedThenFid(dev: Dev, method: String, args: IntArray, fidSymbol: String, fallbackFid: Int?, fidValue: Int): CommandResult {
        val r = current.callSetter(dev.className, method, args)
        if (r is CallResult.Missing) return toCommandResult(writeFidSym(dev, fidSymbol, fallbackFid, fidValue), fidSymbol)
        return toCommandResult(r, method)
    }

    private fun writeFidSym(dev: Dev, symbol: String, fallbackFid: Int?, value: Int): CallResult {
        val fid = featureIds.resolve(symbol) ?: fallbackFid ?: return CallResult.Missing
        return current.writeFid(dev.id, fid, value)
    }

    private fun toCommandResult(result: CallResult, name: String): CommandResult = when (result) {
        CallResult.Missing -> CommandResult.Unsupported("接口不存在：$name")
        is CallResult.Error -> CommandResult.Failed(
            if (result.error is SecurityException) "无权限（请用无线调试授权）" else (result.error.message ?: result.error.toString())
        )
        is CallResult.Value -> {
            val code = (result.value as? Number)?.toInt()
            if (code != null && ValueSanitizer.isErrorCode(code)) CommandResult.Failed("返回码 $code") else CommandResult.Sent
        }
    }

    override fun release() {
        adb.close()
        Log.i(TAG, "released")
    }

    private companion object {
        const val TAG = "BydAutoDataSource"
    }
}
