package com.dicar.vehicle.data.source.bydauto

import com.dicar.vehicle.data.model.AcWindMode

/**
 * BYDAuto 接口映射表 —— 实车探测后主要改这一个文件（需求 2.6「可配置参数映射表」）。
 *
 * 每个字段按顺序尝试多个来源，第一个返回有效值的生效：
 * - [Src.Getter]：设备类上的命名方法，如 BYDAutoSpeedDevice.getCurrentSpeed()；
 * - [Src.Fid]：通过 BYDAutoDeviceManager.getInt/getDouble(dev, fid) 直读。fid 用 BYDAutoFeatureIds
 *   里的字段名在车机上反射解析，因为同一功能在不同平台的 fid 数值不同（如 SPEED_AUTO_SPEED）。
 *
 * 来源标记（务必保留，方便判断可信度）：
 *   [D4]  本车 DiLink 4.0（Android 10，2023-11 固件）探测报告中确认存在的方法 / 常量 / FID 符号。
 *         注意：报告里所有读数都被系统拒绝（permission deny），所以「存在」已确认，「读数含义」仍待验证
 *   [D3]  wheregoes/byd-apps 在 DiLink 3（海豚 / 宋 Pro）实车验证
 *   [D5]  AndyShaman/BYDMate 在 DiLink 5（豹 3）实车验证的 FID 编码
 *   [?]   推测
 */
object BydApiMap {

    /** autoservice 设备类型 id 与对应 Java 类。 */
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
        RADAR(1025, "android.hardware.bydauto.radar.BYDAutoRadarDevice"),
        OTA(1032, "android.hardware.bydauto.ota.BYDAutoOtaDevice"),
        DOOR_LOCK(1041, "android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice"),
        SAFETY_BELT(1042, "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice"),
        SENSOR(1043, "android.hardware.bydauto.sensor.BYDAutoSensorDevice"),
        PM2P5(1008, "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device"),
    }

    sealed interface Src {
        /** [index]：方法返回数组时取第几个元素（如 PM2.5）。 */
        data class Getter(val dev: Dev, val method: String, val args: List<Int> = emptyList(), val index: Int? = null) : Src
        data class Fid(val dev: Dev, val symbol: String, val isFloat: Boolean = false) : Src
    }

    private fun getter(dev: Dev, method: String, vararg args: Int) = Src.Getter(dev, method, args.toList())
    private fun fid(dev: Dev, symbol: String, isFloat: Boolean = false) = Src.Fid(dev, symbol, isFloat)

    const val FEATURE_IDS_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds"

    /** FID 通道：DiLink 4.0 上 AbsBYDAutoDevice 没有 get(dev, fid)，只能走 manager [D4]。 */
    const val DEVICE_MANAGER_CLASS = "android.hardware.bydauto.BYDAutoDeviceManager"

    /** 探测工具的已知类列表（dex 扫描失败时兜底）。 */
    val KNOWN_DEVICE_CLASSES: List<String> =
        Dev.entries.map { it.className } + listOf(
            FEATURE_IDS_CLASS,
            DEVICE_MANAGER_CLASS,
            "android.hardware.bydauto.BYDAutoConstants",
            "android.hardware.bydauto.AbsBYDAutoDevice",
        )

    // =====================================================================
    // 读：动力与行驶
    // =====================================================================

    val SPEED = listOf(getter(Dev.SPEED, "getCurrentSpeed") /*[D4] double km/h*/, fid(Dev.SPEED, "Speed.SPEED_AUTO_SPEED", true) /*[D4]*/)
    /**
     * 发动机转速。车机常量 ENGINE_SPEED_MIN=0 / MAX=8000 [D4]；
     * 超出上限的 8191(0x1FFF) 是 CAN 的「无效」标记（EV 行驶 / 发动机未启动时就是它），必须挡掉。
     */
    val ENGINE_RPM = listOf(getter(Dev.ENGINE, "getEngineSpeed") /*[D4]*/)
    const val ENGINE_RPM_MAX = 8000
    val MOTOR_RPM_FRONT = listOf(fid(Dev.ENGINE, "Engine.ENGINE_FRONT_MOTOR_SPEED") /*[D4]*/)
    val MOTOR_RPM_REAR = listOf(fid(Dev.ENGINE, "Engine.ENGINE_REAR_MOTOR_SPEED") /*[D4]*/)

    /** 整车驱动功率 kW，负值=回收/充电（BYDMate 实测 ENGINE_POWER 为整车功率）。 */
    val POWER = listOf(fid(Dev.ENGINE, "Engine.ENGINE_POWER") /*[D4][D5]*/, getter(Dev.ENGINE, "getEnginePower") /*[D4]*/)
    val ACCELERATOR = listOf(getter(Dev.SPEED, "getAccelerateDeepness") /*[D4] 0-100*/, fid(Dev.SPEED, "Speed.SPEED_ACCELERATOR_S") /*[D4]*/)
    val BRAKE = listOf(getter(Dev.SPEED, "getBrakeDeepness") /*[D4] 0-100*/, fid(Dev.SPEED, "Speed.SPEED_BRAKE_S") /*[D4]*/)

    /** GEARBOX_AUTO_MODE_P=1 R=2 N=3 D=4 M=5 S=6 [D4] */
    val GEAR = listOf(getter(Dev.GEARBOX, "getGearboxAutoModeType"), fid(Dev.GEARBOX, "Gearbox.GEARBOX_AUTO_MODE_TYPE"))
    val GEAR_LABELS = mapOf(1 to "P", 2 to "R", 3 to "N", 4 to "D", 5 to "M", 6 to "S")

    /** ENERGY_MODE_STOP=0 EV=1 FORCE_EV=2 HEV=3 FUEL=4 KEEP=5 [D4] */
    val WORK_MODE = listOf(getter(Dev.ENERGY, "getEnergyMode"), fid(Dev.ENERGY, "Energy.ENERGY_MODE_INSTRUMENT"))
    val WORK_MODE_LABELS = mapOf(0 to "停止", 1 to "EV", 2 to "强制EV", 3 to "HEV", 4 to "燃油", 5 to "保电")

    /** ENERGY_OPERATION_ECONOMY=1 SPORT=2 NORMAL=3 SNOW=4 MUDDY=5 SAND=6 [D4] */
    val DRIVE_MODE = listOf(getter(Dev.ENERGY, "getOperationMode"), fid(Dev.ENERGY, "Energy.ENERGY_OPERATION_MODE"))
    val DRIVE_MODE_LABELS = mapOf(1 to "经济", 2 to "运动", 3 to "普通", 4 to "雪地", 5 to "泥地", 6 to "沙地")

    // =====================================================================
    // 读：电池与能量
    // =====================================================================

    val SOC = listOf(getter(Dev.STATISTIC, "getElecPercentageValue") /*[D4] double %*/, fid(Dev.STATISTIC, "Statistic.STATISTIC_ELEC_PERCENTAGE", true) /*[D4]*/)

    /** 原始值 - 40 = ℃ [D4 符号][D5 编码]；本固件无命名 getter */
    val BATTERY_TEMP_MAX = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP"))
    val BATTERY_TEMP_MIN = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_LOWEST_BATTERY_TEMP"))
    const val BATTERY_TEMP_OFFSET = -40

    /** 单体电压，单位 mV [D4 符号][D5 编码] */
    val CELL_VOLTAGE_MAX = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE"))
    val CELL_VOLTAGE_MIN = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE"))

    /** 动力电池总电压 V / 电流 A（负=充电）[D4 符号][D5 编码] */
    val BATTERY_VOLTAGE = listOf(fid(Dev.CHARGING, "Charging.CHARGING_CHARGE_BATTERY_VOLT"))
    val BATTERY_CURRENT = listOf(fid(Dev.CHARGING, "Charging.CHARGING_CHARGE_CURRENT", true))

    /** CHARGING_GUN_STATE_CONNECTED_NONE=1 AC=2 DC=3 AC_DC=4 VTOL=5 [D4] */
    val CHARGE_GUN = listOf(fid(Dev.CHARGING, "Charging.CHARGING_GUN_CONNECT_STATE"))
    val CHARGE_GUN_LABELS = mapOf(1 to "未插枪", 2 to "交流枪", 3 to "直流枪", 4 to "交直流", 5 to "对外放电")

    /** CHARGING_BATTERY_STATE_READY=0 CHARGING=1 FINISH=2 DISCHARG=3 TERMINATE=4 … PAUSE=13 [D4] */
    val CHARGE_STATE = listOf(fid(Dev.CHARGING, "Charging.CHARGING_BATTERRY_DEVICE_STATE"), getter(Dev.CHARGING, "getBatteryManagementDeviceState"))
    val CHARGE_STATE_LABELS = mapOf(
        0 to "未充电", 1 to "充电中", 2 to "充电完成", 3 to "放电中", 4 to "充电终止",
        9 to "预约充电", 11 to "充电超时", 12 to "放电完成", 13 to "充电暂停",
        15 to "未充电", // 实车停放时返回 15（常量表无定义，视作无充电活动）[D4 实测]
    )

    val RANGE_ELEC = listOf(getter(Dev.STATISTIC, "getElecDrivingRangeValue") /*[D4] km*/)
    val RANGE_FUEL = listOf(getter(Dev.STATISTIC, "getFuelDrivingRangeValue") /*[D4] km*/)
    val FUEL_PERCENT = listOf(getter(Dev.STATISTIC, "getFuelPercentageValue") /*[D4]*/)

    /** 瞬时能耗 [D4]（单位按仪表推测：kWh/100km、L/100km）[?] */
    val INSTANT_ELEC = listOf(getter(Dev.STATISTIC, "getInstantElecConValue"))
    val INSTANT_FUEL = listOf(getter(Dev.STATISTIC, "getInstantFuelConValue"))

    /** 近程百公里能耗 [D4]（Last…PHM，推测为仪表「近 50km」口径）[?] */
    val TRIP_ELEC = listOf(getter(Dev.STATISTIC, "getLastElecConPHMValue"))
    val TRIP_FUEL = listOf(getter(Dev.STATISTIC, "getLastFuelConPHMValue"))

    /** 12V 蓄电池电压。注意 dev 是 1001 而符号在 Ota 名下 [D4 符号][D5] */
    val VOLTAGE_12V = listOf(fid(Dev.BODYWORK, "Ota.OTA_BATTERY_POWER_VOLTAGE", true))

    // =====================================================================
    // 读：空调与座椅
    // =====================================================================

    /** AC_POWER_ON=1 / OFF=0 [D4] */
    val AC_ON = listOf(getter(Dev.AC, "getAcStartState"), fid(Dev.AC, "Ac.AC_POWER_STATE"))

    /** AC_CTRLMODE_AUTO=0 / MANUAL=1 [D4] */
    val AC_CONTROL_MODE = listOf(getter(Dev.AC, "getAcControlMode"), fid(Dev.AC, "Ac.AC_CTRL_MODE"))

    /** getTemprature(区域)（拼写错误是原接口如此）：AC_TEMPERATURE_MAIN=1 DEPUTY=2 OUT=4；直接是 ℃ [D4] */
    const val AC_ZONE_DRIVER = 1
    const val AC_ZONE_PASSENGER = 2
    const val AC_ZONE_OUTSIDE = 4
    val AC_TEMP_DRIVER = listOf(getter(Dev.AC, "getTemprature", AC_ZONE_DRIVER), fid(Dev.AC, "Ac.AC_TEMP_MAIN"))
    val AC_TEMP_PASSENGER = listOf(getter(Dev.AC, "getTemprature", AC_ZONE_PASSENGER), fid(Dev.AC, "Ac.AC_TEMP_DEPUTY"))
    val OUTSIDE_TEMP = listOf(getter(Dev.AC, "getTemprature", AC_ZONE_OUTSIDE), fid(Dev.AC, "Ac.AC_TEMP_OUT"))

    /** 本固件没有 AC_TEMP_INSIDE，也没有车内温度 getter；保留 FID 以兼容其他固件 */
    val INSIDE_TEMP = listOf(fid(Dev.AC, "Ac.AC_TEMP_INSIDE") /*[D5]*/)

    /** AC_WINDLEVEL_0..7 [D4] */
    val AC_FAN_LEVEL = listOf(getter(Dev.AC, "getAcWindLevel"), fid(Dev.AC, "Ac.AC_WIND_LEVEL"))

    /** AC_CYCLEMODE_INLOOP=1（内循环）/ OUTLOOP=0（外循环）[D4]。旧资料（DiLink 3）写的是反的 */
    val AC_CYCLE = listOf(getter(Dev.AC, "getAcCycleMode"), fid(Dev.AC, "Ac.AC_CYCLE_MODE"))
    const val AC_CYCLE_INNER = 1
    const val AC_CYCLE_OUTER = 0

    /** AC_WINDMODE_FACE=1 FACEFOOT=2 FOOT=3 FOOTDEFROST=4 DEFROST=5（另有 FACEFOOTDEFROST=6 FACEDEFROST=7）[D4] */
    val AC_WIND_MODE = listOf(getter(Dev.AC, "getAcWindMode"), fid(Dev.AC, "Ac.AC_WIND_MODE"))
    val AC_WIND_MODE_CODES = mapOf(
        AcWindMode.FACE to 1,
        AcWindMode.FACE_FEET to 2,
        AcWindMode.FEET to 3,
        AcWindMode.FEET_DEFROST to 4,
        AcWindMode.DEFROST to 5,
    )

    /**
     * 座椅加热/通风 [D4]：BYDAutoSettingDevice.getSeatHeatingState(座位) / getSeatVentilatingState(座位)。
     * 座位：DRIVER_SEAT=1 PASSENGER_SEAT=2；状态：OFF=1 LOW=2 HIGH=3 → App 档位 = 状态 - 1（0 关 / 1 低 / 2 高）。
     * FID 兜底：SET_DRIVER_SEAT_HEATING_STATE 等（编码同上 [?]）。
     */
    const val SEAT_DRIVER = 1
    const val SEAT_PASSENGER = 2
    const val SEAT_STATE_OFFSET = -1
    const val SEAT_MAX_LEVEL = 2
    val SEAT_HEAT_DRIVER = listOf(getter(Dev.SETTING, "getSeatHeatingState", SEAT_DRIVER), fid(Dev.SETTING, "Setting.SET_DRIVER_SEAT_HEATING_STATE"))
    val SEAT_HEAT_PASSENGER = listOf(getter(Dev.SETTING, "getSeatHeatingState", SEAT_PASSENGER), fid(Dev.SETTING, "Setting.SET_PASSENGER_SEAT_HEATING_STATE"))
    val SEAT_VENT_DRIVER = listOf(getter(Dev.SETTING, "getSeatVentilatingState", SEAT_DRIVER), fid(Dev.SETTING, "Setting.SET_DRIVER_SEAT_VENTILATING_STATE"))
    val SEAT_VENT_PASSENGER = listOf(getter(Dev.SETTING, "getSeatVentilatingState", SEAT_PASSENGER), fid(Dev.SETTING, "Setting.SET_PASSENGER_SEAT_VENTILATING_STATE"))

    // =====================================================================
    // 读：车身与安全
    // =====================================================================

    /** getDoorState(区域)：0 关 / 1 开 / 255 未定义；BODYWORK_CMD_DOOR_LEFT_FRONT=1 … HOOD=5 LUGGAGE_DOOR=6 [D4] */
    const val DOOR_FL = 1
    const val DOOR_FR = 2
    const val DOOR_RL = 3
    const val DOOR_RR = 4
    const val DOOR_HOOD = 5
    const val DOOR_TRUNK = 6
    fun door(area: Int) = listOf(getter(Dev.BODYWORK, "getDoorState", area))

    /** getWindowOpenPercent(区域) 0-100：BODYWORK_CMD_WINDOW_LEFT_FRONT=1 … RIGHT_REAR=4，MOON_ROOF=5，SUNSHADE_PANEL=6 [D4] */
    val WINDOW_FL = listOf(getter(Dev.BODYWORK, "getWindowOpenPercent", 1), fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT"))
    val WINDOW_FR = listOf(getter(Dev.BODYWORK, "getWindowOpenPercent", 2), fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_RIGHT_FRONT_PERCENT"))
    val WINDOW_RL = listOf(getter(Dev.BODYWORK, "getWindowOpenPercent", 3), fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT"))
    val WINDOW_RR = listOf(getter(Dev.BODYWORK, "getWindowOpenPercent", 4), fid(Dev.BODYWORK, "Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT"))
    val SUNROOF = listOf(getter(Dev.BODYWORK, "getWindowOpenPercent", 5), fid(Dev.BODYWORK, "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT"))
    val SUNSHADE = listOf(getter(Dev.BODYWORK, "getWindowOpenPercent", 6), fid(Dev.BODYWORK, "Bodywork.BODYWORK_SUNSHADE_PANEL_PERCENT"))

    /** getTyrePressureValue(区域) kPa：TYRE_COMMAND_AREA_LEFT_FRONT=1 RIGHT_FRONT=2 LEFT_REAR=3 RIGHT_REAR=4 [D4] */
    val TIRE_PRESSURE_FL = listOf(getter(Dev.TYRE, "getTyrePressureValue", 1), fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_LEFT_FRONT"))
    val TIRE_PRESSURE_FR = listOf(getter(Dev.TYRE, "getTyrePressureValue", 2), fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_FRONT"))
    val TIRE_PRESSURE_RL = listOf(getter(Dev.TYRE, "getTyrePressureValue", 3), fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_LEFT_REAR"))
    val TIRE_PRESSURE_RR = listOf(getter(Dev.TYRE, "getTyrePressureValue", 4), fid(Dev.TYRE, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_REAR"))

    /** 胎温 ℃（仅部分车型有）[D4 符号] */
    val TIRE_TEMP_FL = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE"))
    val TIRE_TEMP_FR = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE"))
    val TIRE_TEMP_RL = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE"))
    val TIRE_TEMP_RR = listOf(fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE"))

    /** getSteeringWheelValue(BODYWORK_CMD_STEERING_WHEEL_ANGEL=1)，范围 ±780° [D4] */
    val STEERING_ANGLE = listOf(getter(Dev.BODYWORK, "getSteeringWheelValue", 1))

    /** getSafetyBeltStatus(区域)：SAFETY_BELT_AREA_MAIN=1 DEPUTY=2；STATE_LOCK=1 已系 / UNLOCK=0 [D4] */
    val SEATBELT_DRIVER = listOf(getter(Dev.SAFETY_BELT, "getSafetyBeltStatus", 1), fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE"))
    val SEATBELT_PASSENGER = listOf(getter(Dev.SAFETY_BELT, "getSafetyBeltStatus", 2), fid(Dev.INSTRUMENT, "Instrument.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE"))

    /**
     * 副驾乘员检测：getPassengerStatus(SAFETY_BELT_PASSENGER_DEPUTY=1)，
     * STATE_NOBODY=0 / SOMEBODY=1 [D4]。没人坐时安全带信号本来就会乱跳，
     * 界面应显示「无人」而不是「未系」。
     */
    val PASSENGER_PRESENT = listOf(getter(Dev.SAFETY_BELT, "getPassengerStatus", 1))

    /** 车窗防夹配置 [D4]（BODYWORK_NO_ANTI_PINCH=1 表示无防夹，其余为各种有防夹的配置） */
    val WINDOW_ANTI_PINCH = listOf(getter(Dev.BODYWORK, "getCarWindowAntiPinchConfig"))
    const val WINDOW_NO_ANTI_PINCH = 1

    /**
     * 转向灯用位掩码读：1 关、2 左、4 右、6 双闪 [D4 符号][D5 编码]。
     *
     * 不要用 getLightStatus(4/5)：灯光设备里 TURN_LIGHT_OFF = 1（而不是 0），
     * 按「1 = 开」解读会导致转向灯常亮——这是实车上报的问题。
     */
    val TURN_SIGNAL_MASK = listOf(fid(Dev.LIGHT, "Light.LIGHT_TURN_SIGNAL_LIGHT"))
    const val TURN_LEFT_BIT = 2
    const val TURN_RIGHT_BIT = 4
    const val TURN_SIGNAL_MAX = 7

    /**
     * 泊车雷达 [D4]：getRadarProbeState(区域) 返回障碍等级（0..6 越小越近，14 表示安全/无障碍）。
     * 区域常量见 RADAR_AREA_*；另有 getAllRadarProbeStates() 一次返回全部，但数组下标与区域的
     * 对应关系未在实车确认，这里逐区域读（批量传输下开销相同）。
     */
    const val RADAR_LEFT_FRONT = 1
    const val RADAR_RIGHT_FRONT = 2
    const val RADAR_LEFT_REAR = 3
    const val RADAR_RIGHT_REAR = 4
    const val RADAR_LEFT = 5
    const val RADAR_RIGHT = 6
    const val RADAR_FRONT_LEFT_MID = 7
    const val RADAR_FRONT_RIGHT_MID = 8
    const val RADAR_MIDDLE_REAR = 9
    fun radar(area: Int) = listOf(getter(Dev.RADAR, "getRadarProbeState", area))
    val RADAR_REVERSE_SWITCH = listOf(getter(Dev.RADAR, "getReverseRadarSwitchState"))


    /**
     * 车窗 / 天窗 / 遮阳帘控制：统一写「目标开度」0..100 [D4 符号][D5 车窗实车验证]。
     * 比写开关指令好的地方是编码明确（0 关、100 全开），不必猜 OPEN/CLOSE 的枚举值。
     */
    val GLASS_SET_FIDS: Map<com.dicar.vehicle.data.model.GlassZone, String> = mapOf(
        com.dicar.vehicle.data.model.GlassZone.WINDOW_FL to "Bodywork.BODYWORK_LF_WINDOW_TARGET_POSITION_SET",
        com.dicar.vehicle.data.model.GlassZone.WINDOW_FR to "Bodywork.BODYWORK_RF_WINDOW_TARGET_POSITION_SET",
        com.dicar.vehicle.data.model.GlassZone.WINDOW_RL to "Bodywork.BODYWORK_LR_WINDOW_TARGET_POSITION_SET",
        com.dicar.vehicle.data.model.GlassZone.WINDOW_RR to "Bodywork.BODYWORK_RR_WINDOW_TARGET_POSITION_SET",
        com.dicar.vehicle.data.model.GlassZone.SUNROOF to "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT_SET",
        com.dicar.vehicle.data.model.GlassZone.SUNSHADE to "Bodywork.BODYWORK_SUNSHADE_PANEL_PERCENT_SET",
    )
    const val GLASS_OPEN_PERCENT = 100
    const val GLASS_CLOSE_PERCENT = 0

    // =====================================================================
    // 读：其他
    // =====================================================================

    /** 命名接口直接是 km [D4]；FID 是 0.1 km [D5] */
    val TOTAL_MILEAGE_KM = listOf(getter(Dev.STATISTIC, "getTotalMileageValue"))
    val TOTAL_MILEAGE_DECI_KM = listOf(fid(Dev.STATISTIC, "Statistic.STATISTIC_TOTAL_MILEAGE"))

    /** getPM2p5Value() 返回 int[]，0-3000 μg/m³；取第 0 个（推测为车内）[D4][?] */
    val PM25 = listOf(Src.Getter(Dev.PM2P5, "getPM2p5Value", index = 0))

    /** 本固件 SensorDevice 只有 getLightIntensity，没有坡度 */
    val SLOPE: List<Src> = emptyList()

    // =====================================================================
    // 写：控制。优先命名接口，接口不存在时写 FID（FID 写入值来自 BYDMate 豹 3 实测）
    // =====================================================================

    /** start(来源) / stop(来源)；AC_CTRL_SOURCE_UI_KEY=0 [D3][D4] */
    const val AC_START = "start"
    const val AC_STOP = "stop"
    const val AC_POWER_ARG = 0
    const val AC_POWER_SET_FID = "Ac.AC_POWER_STATE_SET" // 1 开 / 0 关 [D4 符号][D5]

    /** setAcTemperature(区域, ℃整数, 来源=1, 单位=1)；来源传 0 会被判无效 [D3]。17–33 ℃ [D4] */
    const val AC_SET_TEMP = "setAcTemperature"
    const val AC_SOURCE = 1 // AC_CTRL_SOURCE_VOICE
    const val AC_TEMP_UNIT_CELSIUS = 1 // AC_TEMPERATURE_UNIT_OC
    const val AC_TEMP_STEP = 1f
    const val AC_TEMP_DRIVER_SET_FID = "Ac.AC_TEMP_MAIN_SET"
    const val AC_TEMP_PASSENGER_SET_FID = "Ac.AC_TEMP_DEPUTY_SET"

    /** setAcControlMode(模式, 来源)：0 自动 / 1 手动 [D3][D4] */
    const val AC_SET_CONTROL_MODE = "setAcControlMode"
    const val AC_CONTROL_MODE_SET_FID = "Ac.AC_CTRL_MODE_SET"

    /** setAcWindMode(模式, 来源) [D3][D4] */
    const val AC_SET_WIND_MODE = "setAcWindMode"
    const val AC_WIND_MODE_SET_FID = "Ac.AC_WIND_MODE_SET"

    /** setAcCycleMode(模式, 来源)：1 内循环 / 0 外循环 [D4] */
    const val AC_SET_CYCLE = "setAcCycleMode"
    const val AC_CYCLE_SET_FID = "Ac.AC_CYCLE_MODE_SET"

    /** 风量：命名接口 setAcWindLevel 在 DiLink 3 实车上无效 [D3]，直接写 FID（0x1DE0000C 在 D3/D4/D5 数值一致） */
    const val AC_WIND_LEVEL_SET_FID = "Ac.AC_WIND_LEVEL_SET"
    const val AC_WIND_LEVEL_SET_FALLBACK = 0x1DE0000C

    /** 座椅：SettingDevice.setSeatHeatingState(座位, 状态) / setSeatVentilatingState，状态 = 档位 + 1 [D4] */
    const val SEAT_SET_HEAT = "setSeatHeatingState"
    const val SEAT_SET_VENT = "setSeatVentilatingState"
    const val SEAT_HEAT_DRIVER_SET_FID = "Setting.SET_DRIVER_SEAT_HEATING_STATE_SET"
    const val SEAT_HEAT_PASSENGER_SET_FID = "Setting.SET_PASSENGER_SEAT_HEATING_STATE_SET"
    const val SEAT_VENT_DRIVER_SET_FID = "Setting.SET_DRIVER_SEAT_VENTILATING_STATE_SET"
    const val SEAT_VENT_PASSENGER_SET_FID = "Setting.SET_PASSENGER_SEAT_VENTILATING_STATE_SET"

    /** 前挡除霜：setAcDefrostState(区域, 状态, 来源)，AC_DEFROST_AREA_FRONT=1、STATE_ON=1，三个参数都是 1 与顺序无关 [D4] */
    const val AC_SET_DEFROST = "setAcDefrostState"
    const val AC_DEFROST_FRONT_SET_FID = "Ac.AC_DEFROST_FRONT_STATE_SET"

    /** 快速降温：本固件无命名接口，写 FID AC_MAX_COOLING_STATE_SET = AC_MAX_COOLING_ON(1) [D4 符号] */
    const val AC_MAX_COOLING_SET_FID = "Ac.AC_MAX_COOLING_STATE_SET"

    /** 一键净化：写 FID AC_QUICK_CLEAN_AIR_SET = SET_QUICK_CLEAN_OPEN(1) [D4 符号] */
    const val AC_QUICK_CLEAN_SET_FID = "Ac.AC_QUICK_CLEAN_AIR_SET"
}
