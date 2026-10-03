package com.dicar.vehicle.data.source.bydauto

import com.dicar.vehicle.data.model.AcWindMode

/**
 * BYDAuto 接口映射表 —— 实车探测后主要改这一个文件（需求 2.6「可配置参数映射表」）。
 *
 * 每个字段按顺序尝试多个来源，第一个返回有效值的生效：
 * - [Src.Getter]：设备类上的命名方法，如 BYDAutoSpeedDevice.getCurrentSpeed()；
 * - [Src.Fid]：通用 get(dev, fid)。fid 用 BYDAutoFeatureIds 里的「嵌套类.字段名」在车机上反射解析，
 *   因为同一功能在不同平台的 fid 数值不同，按名字解析比写死数字可靠。
 *
 * 来源标记（务必保留，方便判断可信度）：
 *   [D3]  wheregoes/byd-apps 在 DiLink 3（Dolphin / Song Pro GS）实车验证
 *   [D5]  AndyShaman/BYDMate 在 DiLink 5（Leopard 3）实车验证的 fid 符号/编码
 *   [X]   第三方反射代码中出现，未见实车验证
 *   [?]   推测，需用「设置 → 探测 BYDAuto 接口」核对
 * 没有任何一条在 DiLink 4.0 上验证过。
 */
object BydApiMap {

    /** autoservice 设备类型 id 与对应 Java 类（BYDMate FidPushWire.kt，由固件反编译生成）。 */
    enum class Dev(val id: Int, val className: String) {
        AC(1000, "android.hardware.bydauto.ac.BYDAutoAcDevice"),
        BODYWORK(1001, "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"),
        LIGHT(1004, "android.hardware.bydauto.light.BYDAutoLightDevice"),
        POWER(1005, "android.hardware.bydauto.power.BYDAutoPowerDevice"),
        ENERGY(1006, "android.hardware.bydauto.energy.BYDAutoEnergyDevice"),
        INSTRUMENT(1007, "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"),
        CHARGING(1009, "android.hardware.bydauto.charging.BYDAutoChargingDevice"),
        GEARBOX(1011, "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"),
        ENGINE(1012, "android.hardware.bydauto.engine.BYDAutoEngineDevice"),
        SPEED(1013, "android.hardware.bydauto.speed.BYDAutoSpeedDevice"),
        STATISTIC(1014, "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"),
        TYRE(1016, "android.hardware.bydauto.tyre.BYDAutoTyreDevice"),
        MOTOR(1020, "android.hardware.bydauto.motor.BYDAutoMotorDevice"),
        SETTING(1023, "android.hardware.bydauto.setting.BYDAutoSettingDevice"),
        OTA(1032, "android.hardware.bydauto.ota.BYDAutoOtaDevice"),
        DOOR_LOCK(1041, "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice"),
        SAFETY_BELT(1042, "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice"),
        SENSOR(1043, "android.hardware.bydauto.sensor.BYDAutoSensorDevice"),
    }

    sealed interface Src {
        data class Getter(val dev: Dev, val method: String, val args: List<Int> = emptyList()) : Src
        data class Fid(val dev: Dev, val symbol: String, val isFloat: Boolean = false) : Src
    }

    private fun getter(dev: Dev, method: String, vararg args: Int) = Src.Getter(dev, method, args.toList())
    private fun fid(dev: Dev, symbol: String, isFloat: Boolean = false) = Src.Fid(dev, symbol, isFloat)

    const val FEATURE_IDS_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds"

    /** 探测工具的已知类列表（dex 扫描失败时兜底）。 */
    val KNOWN_DEVICE_CLASSES: List<String> =
        Dev.entries.map { it.className } + listOf(
            FEATURE_IDS_CLASS,
            "android.hardware.bydauto.BYDAutoConstants",
            "android.hardware.bydauto.AbsBYDAutoDevice",
        )

    // =====================================================================
    // 读：动力与行驶
    // =====================================================================

    val SPEED = listOf(getter(Dev.SPEED, "getCurrentSpeed") /*[X] double km/h*/, fid(Dev.SPEED, "Speed.SPEED_AUTO_SPEED", true) /*[D5]*/)
    val ENGINE_RPM = listOf(getter(Dev.ENGINE, "getEngineSpeed") /*[X]*/)
    val MOTOR_RPM_FRONT = listOf(fid(Dev.ENGINE, "Engine.ENGINE_FRONT_MOTOR_SPEED") /*[D5]*/)
    val MOTOR_RPM_REAR = listOf(fid(Dev.ENGINE, "Engine.ENGINE_REAR_MOTOR_SPEED") /*[D5]*/)

    /** 整车驱动功率 kW，负值=回收/充电。注意迪加里同一信号叫「发动机功率」，实为整车功率。 */
    val POWER = listOf(fid(Dev.ENGINE, "Engine.ENGINE_POWER") /*[D5]*/, getter(Dev.ENGINE, "getEnginePower") /*[X]*/)
    val ACCELERATOR = listOf(getter(Dev.SPEED, "getAccelerateDeepness") /*[X] 0-100*/, fid(Dev.SPEED, "Speed.SPEED_ACCELERATOR_S") /*[D5]*/)
    val BRAKE = listOf(getter(Dev.SPEED, "getBrakeDeepness") /*[X] 0-100*/, fid(Dev.SPEED, "Speed.SPEED_BRAKE_S") /*[D5]*/)

    val GEAR = listOf(getter(Dev.GEARBOX, "getGearboxAutoModeType") /*[X]*/, fid(Dev.GEARBOX, "Gearbox.GEARBOX_AUTO_MODE_TYPE") /*[D5]*/)
    val GEAR_LABELS = mapOf(1 to "P", 2 to "R", 3 to "N", 4 to "D", 5 to "M", 6 to "S") // [D5]+[X]

    val WORK_MODE = listOf(fid(Dev.ENERGY, "Energy.ENERGY_MODE_INSTRUMENT") /*[D5]*/, getter(Dev.ENERGY, "getEnergyMode") /*[X]*/)
    val WORK_MODE_LABELS = mapOf(0 to "停止", 1 to "EV", 2 to "强制EV", 3 to "HEV") // [?] 按迪加「整车工作模式」编码推测

    val DRIVE_MODE = listOf(fid(Dev.ENERGY, "Energy.ENERGY_OPERATION_MODE") /*[D5]*/, getter(Dev.ENERGY, "getOperationMode") /*[X]*/)
    val DRIVE_MODE_LABELS = mapOf(1 to "ECO", 2 to "运动") // [?] 按迪加「整车运行模式」编码推测

    // =====================================================================
    // 读：电池与能量
    // =====================================================================

    val SOC = listOf(getter(Dev.STATISTIC, "getElecPercentageValue") /*[D3] double %*/, fid(Dev.STATISTIC, "Statistic.STATISTIC_ELEC_PERCENTAGE", true) /*[D5]*/)

    /** 原始值 - 40 = ℃ [D5] */
    val BATTERY_TEMP_MAX = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP"))
    val BATTERY_TEMP_MIN = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_LOWEST_BATTERY_TEMP"))
    const val BATTERY_TEMP_OFFSET = -40

    /** 单体电压，单位 mV [D5] */
    val CELL_VOLTAGE_MAX = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE"))
    val CELL_VOLTAGE_MIN = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE"))

    /** 动力电池总电压 V / 电流 A（负=充电）[D5] */
    val BATTERY_VOLTAGE = listOf(fid(Dev.CHARGING, "Charging.CHARGING_CHARGE_BATTERY_VOLT"))
    val BATTERY_CURRENT = listOf(fid(Dev.CHARGING, "Charging.CHARGING_CHARGE_CURRENT", true))

    /** 充电枪：1 无枪、2 交流、3 直流 [D5] */
    val CHARGE_GUN = listOf(fid(Dev.CHARGING, "Charging.CHARGING_GUN_CONNECT_STATE"))
    val CHARGE_GUN_LABELS = mapOf(1 to "未插枪", 2 to "交流枪", 3 to "直流枪")

    /** BMS 充电状态 [D5] */
    val CHARGE_STATE = listOf(fid(Dev.CHARGING, "Charging.CHARGING_BATTERRY_DEVICE_STATE"))
    val CHARGE_STATE_LABELS = mapOf(1 to "充电中", 2 to "充电完成", 13 to "充电暂停")

    val RANGE_ELEC = listOf(getter(Dev.STATISTIC, "getElecDrivingRangeValue") /*[X] km*/)
    val RANGE_FUEL = listOf(getter(Dev.STATISTIC, "getFuelDrivingRangeValue") /*[?]*/)
    val FUEL_PERCENT = listOf(getter(Dev.STATISTIC, "getFuelPercentageValue") /*[?]*/)

    /** 12V 蓄电池电压。注意 dev 是 1001 而符号在 Ota 名下 [D5] */
    val VOLTAGE_12V = listOf(fid(Dev.BODYWORK, "Ota.OTA_BATTERY_POWER_VOLTAGE", true))

    // TODO 行程电耗/油耗（F16）：暂无可靠来源。候选：Statistic.STATISTIC_TOTAL_ELEC_CON_PHM（这是累计平均，不是本次行程）

    // =====================================================================
    // 读：空调与座椅
    // =====================================================================

    /** 0 关 / 1 开 [D3] */
    val AC_ON = listOf(getter(Dev.AC, "getAcStartState"), fid(Dev.AC, "Ac.AC_POWER_STATE") /*[D5]*/)

    /** 0 自动 / 1 手动 [D3] */
    val AC_CONTROL_MODE = listOf(getter(Dev.AC, "getAcControlMode"), fid(Dev.AC, "Ac.AC_CTRL_MODE") /*[D5]*/)

    /** getTemprature(区域)，拼写错误是原接口如此。区域：1 主驾、2 副驾、4 车外；直接是 ℃ [D3] */
    const val AC_ZONE_DRIVER = 1
    const val AC_ZONE_PASSENGER = 2
    const val AC_ZONE_OUTSIDE = 4
    val AC_TEMP_DRIVER = listOf(getter(Dev.AC, "getTemprature", AC_ZONE_DRIVER), fid(Dev.AC, "Ac.AC_TEMP_MAIN"))
    val AC_TEMP_PASSENGER = listOf(getter(Dev.AC, "getTemprature", AC_ZONE_PASSENGER), fid(Dev.AC, "Ac.AC_TEMP_DEPUTY") /*[?]*/)
    val OUTSIDE_TEMP = listOf(
        getter(Dev.AC, "getTemprature", AC_ZONE_OUTSIDE),
        getter(Dev.INSTRUMENT, "getOutCarTemperature"),
        fid(Dev.AC, "Ac.AC_TEMP_OUT") /*[D5]*/,
    )
    val INSIDE_TEMP = listOf(fid(Dev.AC, "Ac.AC_TEMP_INSIDE") /*[D5]*/)

    /** 0-7 [D3] */
    val AC_FAN_LEVEL = listOf(getter(Dev.AC, "getAcWindLevel"), fid(Dev.AC, "Ac.AC_WIND_LEVEL") /*[D5]*/)

    /** 0 内循环 / 1 外循环（命名接口的编码，与 FID 写入的编码相反！）[D3] */
    val AC_CYCLE = listOf(getter(Dev.AC, "getAcCycleMode"))
    const val AC_CYCLE_INNER = 0
    const val AC_CYCLE_OUTER = 1

    /** 出风模式：仅确认 1=吹面、5=吹脚、0=除霜 [D3]，其余为 [?]，以探测出的 AC_WINDMODE_* 常量为准 */
    val AC_WIND_MODE = listOf(getter(Dev.AC, "getAcWindMode"), fid(Dev.AC, "Ac.AC_WIND_MODE") /*[D5]*/)
    val AC_WIND_MODE_CODES = mapOf(
        AcWindMode.DEFROST to 0,       // [D3]
        AcWindMode.FACE to 1,          // [D3]
        AcWindMode.FACE_FEET to 2,     // [?]
        AcWindMode.FEET to 5,          // [D3]
        AcWindMode.FEET_DEFROST to 6,  // [?]
    )

    /** 座椅加热/通风档位：0 关、1..5 档 [D5] */
    val SEAT_HEAT_DRIVER = listOf(fid(Dev.AC, "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL"))
    val SEAT_HEAT_PASSENGER = listOf(fid(Dev.AC, "Ac.AC_PASSENGER_SEAT_HEATING_LEVEL"))
    val SEAT_VENT_DRIVER = listOf(fid(Dev.AC, "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL"))
    val SEAT_VENT_PASSENGER = listOf(fid(Dev.AC, "Ac.AC_PASSENGER_SEAT_VENTILATING_LEVEL"))

    // =====================================================================
    // 读：车身与安全
    // =====================================================================

    /** getDoorState(区域)：0 关 / 1 开 / 255 未定义；区域 1 左前 2 右前 3 左后 4 右后 5 引擎盖 6 后备箱 [D3] */
    const val DOOR_FL = 1
    const val DOOR_FR = 2
    const val DOOR_RL = 3
    const val DOOR_RR = 4
    const val DOOR_HOOD = 5
    const val DOOR_TRUNK = 6
    fun door(area: Int) = listOf(getter(Dev.BODYWORK, "getDoorState", area))

    /** 车窗开度 % [D5] */
    val WINDOW_FL = listOf(fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT"))
    val WINDOW_FR = listOf(fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_RIGHT_FRONT_PERCENT"))
    val WINDOW_RL = listOf(fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT"))
    val WINDOW_RR = listOf(fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT"))
    val SUNROOF = listOf(fid(Dev.BODYWORK, "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT"))
    val SUNSHADE = listOf(fid(Dev.BODYWORK, "Bodywork.BODYWORK_SUNSHADE_PANEL_PERCENT") /*[?]*/)

    /** 胎压 kPa [D5] */
    val TIRE_PRESSURE_FL = listOf(fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_LEFT_FRONT"))
    val TIRE_PRESSURE_FR = listOf(fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_FRONT"))
    val TIRE_PRESSURE_RL = listOf(fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_LEFT_REAR"))
    val TIRE_PRESSURE_RR = listOf(fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_REAR"))

    /** 胎温 ℃（仅部分车型有）[D5] */
    val TIRE_TEMP_FL = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE"))
    val TIRE_TEMP_FR = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE"))
    val TIRE_TEMP_RL = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE"))
    val TIRE_TEMP_RR = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE"))

    val STEERING_ANGLE: List<Src> = emptyList() // TODO [?] 未找到来源，迪加有「方向盘转角」

    /** 安全带：0 未系 / 1 已系 / 2 无效 [D5] */
    val SEATBELT_DRIVER = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE"))
    val SEATBELT_PASSENGER = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE"))

    /** 转向灯位掩码：1 关、2 左、4 右、6 双闪 [D5] */
    val TURN_SIGNAL = listOf(fid(Dev.LIGHT, "Light.LIGHT_TURN_SIGNAL_LIGHT"))
    const val TURN_LEFT_BIT = 2
    const val TURN_RIGHT_BIT = 4

    // =====================================================================
    // 读：其他
    // =====================================================================

    /** 命名接口直接是 km [D3]；FID 是 0.1 km [D5] */
    val TOTAL_MILEAGE_KM = listOf(getter(Dev.STATISTIC, "getTotalMileageValue"))
    val TOTAL_MILEAGE_DECI_KM = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_TOTAL_MILEAGE"))
    val SLOPE = listOf(getter(Dev.SENSOR, "getSlope") /*[?]*/)

    // =====================================================================
    // 写：控制
    // =====================================================================

    /** start(0) / stop(0) [D3] */
    const val AC_START = "start"
    const val AC_STOP = "stop"
    const val AC_POWER_ARG = 0

    /** setAcTemperature(区域, ℃整数, 来源=1, 单位=1)；来源传 0 会被判无效 [D3]。只支持整数 ℃。 */
    const val AC_SET_TEMP = "setAcTemperature"
    const val AC_SOURCE = 1
    const val AC_TEMP_UNIT_CELSIUS = 1
    const val AC_TEMP_STEP = 1f

    /** setAcControlMode(模式, 来源)：0 自动 / 1 手动 [D3] */
    const val AC_SET_CONTROL_MODE = "setAcControlMode"

    /** setAcWindMode(模式, 来源) [D3] */
    const val AC_SET_WIND_MODE = "setAcWindMode"

    /** setAcCycleMode(模式, 来源)：0 内循环 / 1 外循环 [D3] */
    const val AC_SET_CYCLE = "setAcCycleMode"

    /**
     * 风量：命名接口 setAcWindLevel 在实车上无效 [D3]，改用通用 set(1000, fid, 档位)。
     * fid 优先按符号解析，解析不到用 0x1DE0000C（DiLink 3 两台车 + Leopard 3 数值一致）。
     */
    const val AC_WIND_LEVEL_SET_SYMBOL = "Ac.AC_WIND_LEVEL_SET"
    const val AC_WIND_LEVEL_SET_FALLBACK = 0x1DE0000C

    /**
     * 座椅：无命名接口，只能写 FID [D5]。先写开关（1 开 / 2 关），再写档位（1..5）。
     * 只用车机上按符号解析出的 fid，不写死数值（座椅 fid 跨平台未确认一致）。
     * 注意：Song L / 汉 EV 会对开关写入返回成功但不动作，所以必须依赖回读确认。
     */
    const val SEAT_SWITCH_ON = 1
    const val SEAT_SWITCH_OFF = 2
    val SEAT_HEAT_DRIVER_SET = "Ac.AC_MAIN_DRIVE_SEAT_HEATING_STATUS_SET" to "Ac.AC_MAIN_DRIVE_SEAT_HEATING_LEVEL_SET"
    val SEAT_HEAT_PASSENGER_SET = "Ac.AC_PASSENGER_SEAT_HEATING_STATUS_SET" to "Ac.AC_PASSENGER_SEAT_HEATING_LEVEL_SET"
    val SEAT_VENT_DRIVER_SET = "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_STATUS_SET" to "Ac.AC_MAIN_DRIVE_SEAT_VENTILATING_LEVEL_SET"
    val SEAT_VENT_PASSENGER_SET = "Ac.AC_PASSENGER_SEAT_VENTILATING_STATUS_SET" to "Ac.AC_PASSENGER_SEAT_VENTILATING_LEVEL_SET"

    /** 前挡除霜：FID 写 1 [D5 同类：后除霜 1 开 / 0 关] */
    const val AC_DEFROST_FRONT_SET_SYMBOL = "Ac.AC_DEFROST_FRONT_STATE_SET"

    /** 快速降温：setAcMaxCoolingState(1)，仅见签名未实测 [?] */
    const val AC_SET_MAX_COOLING = "setAcMaxCoolingState"
}
