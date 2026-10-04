package com.dicar.vehicle.data.model

/**
 * 车辆状态快照。所有字段均可空：null = 接口不支持 / 读取失败 / 返回异常值，UI 统一显示「N/A」。
 *
 * 数据源只负责“尽量填”，不要在这里放默认值（0 和“没读到”在仪表上含义完全不同）。
 */
data class VehicleState(
    // ---------------- 动力与行驶 (F01-F08) ----------------
    val speed: Float? = null,                 // km/h
    val engineRpm: Int? = null,               // rpm
    val motorRpmFront: Int? = null,           // rpm
    val motorRpmRear: Int? = null,            // rpm
    val power: Float? = null,                 // kW，整车总功率（正=驱动，负=回收）
    val motorPower: Float? = null,            // kW
    val enginePower: Float? = null,           // kW
    val accelerator: Float? = null,           // %，0-100
    val brake: Float? = null,                 // %，0-100
    val gear: String? = null,                 // P/R/N/D/S...
    val workMode: String? = null,             // EV / HEV / 强制EV ...
    val driveMode: String? = null,            // ECO / 正常 / 运动 ...

    // ---------------- 电池与能量 (F09-F17) ----------------
    val soc: Float? = null,                   // %
    val batteryTempMax: Float? = null,        // ℃
    val batteryTempMin: Float? = null,        // ℃
    val batteryTempAvg: Float? = null,        // ℃
    val batteryVoltage: Float? = null,        // V，动力电池总电压
    val cellVoltageMax: Float? = null,        // V
    val cellVoltageMin: Float? = null,        // V
    val batteryCurrent: Float? = null,        // A（正=放电，负=充电）
    val batteryPower: Float? = null,          // kW（正=放电，负=充电/回收）
    val chargeGunConnected: Boolean? = null,
    val chargeStatus: String? = null,         // 充电中 / 已完成 / 未充电 ...
    val remainRangeElec: Int? = null,         // km
    val remainRangeFuel: Int? = null,         // km
    val fuelPercent: Float? = null,           // %
    val instantElecConsumption: Float? = null,  // kWh/100km
    val instantFuelConsumption: Float? = null,  // L/100km
    val tripElecConsumption: Float? = null,     // kWh/100km
    val tripFuelConsumption: Float? = null,     // L/100km
    val voltage12V: Float? = null,            // V

    // ---------------- 空调与舒适 (F18-F25) ----------------
    val acOn: Boolean? = null,
    val acAuto: Boolean? = null,
    val acTempDriver: Float? = null,          // ℃，0.5 步进
    val acTempPassenger: Float? = null,       // ℃
    val acFanLevel: Int? = null,              // 0-7
    val acWindMode: AcWindMode? = null,
    val acCycle: AcCycleMode? = null,
    val insideTemp: Float? = null,            // ℃
    val outsideTemp: Float? = null,           // ℃
    val seatHeatDriver: Int? = null,          // 0=关, 1-3 档
    val seatHeatPassenger: Int? = null,
    val seatVentDriver: Int? = null,
    val seatVentPassenger: Int? = null,

    // ---------------- 车身与安全 (F26-F32) ----------------
    val doors: Openings = Openings(),
    val windowPercent: Wheels<Int> = Wheels(),  // 四门车窗开度 %
    val sunroofPercent: Int? = null,
    val sunshadePercent: Int? = null,
    val tirePressure: Wheels<Float> = Wheels(), // kPa
    val tireTemp: Wheels<Float> = Wheels(),     // ℃
    val steeringAngle: Float? = null,           // °，左负右正
    val seatbeltDriver: Boolean? = null,        // true = 已系
    val seatbeltPassenger: Boolean? = null,
    val passengerPresent: Boolean? = null,      // 副驾乘员检测：没人时安全带信号本来就不可信
    val windowAntiPinch: Boolean? = null,       // 车窗防夹
    val turnLeft: Boolean? = null,
    val turnRight: Boolean? = null,
    val radar: Radar = Radar(),

    // ---------------- 其他 (F33-F37) ----------------
    val totalMileage: Float? = null,          // km
    val tripMileage: Float? = null,           // km
    val altitude: Float? = null,              // m
    val slope: Float? = null,                 // °
    val pm25: Int? = null,                    // μg/m³

    // ---------------- 元信息 ----------------
    val acTempStep: Float = 0.5f,             // 数据源支持的温度调节步进（BYDAuto 接口只收整数 ℃）
    val seatMaxLevel: Int = 3,                // 数据源支持的座椅加热/通风最高档（迪加只有 2 档）
    val source: DataSourceType? = null,
    val timestamp: Long = 0L,
    val error: String? = null,                // 整个数据源不可用时的说明
) {
    /** 仪表盘主转速：有发动机转速就用它，否则用电机转速（取绝对值较大的一侧）。 */
    val displayRpm: Int?
        get() = engineRpm ?: listOfNotNull(motorRpmFront, motorRpmRear).maxByOrNull { kotlin.math.abs(it) }

    val displayRpmLabel: String
        get() = if (engineRpm != null) "发动机转速" else "电机转速"

    /** 单体压差（mV），由最高/最低单体电压推算。 */
    val cellVoltageDiffMv: Float?
        get() = if (cellVoltageMax != null && cellVoltageMin != null) {
            (cellVoltageMax - cellVoltageMin) * 1000f
        } else null

    /** F37 能量流：根据功率与转速粗略推断。数据不足时返回 null。 */
    val energyFlow: String?
        get() {
            val engineRunning = (engineRpm ?: 0) > 300
            val motorRpm = maxOf(motorRpmFront ?: 0, motorRpmRear ?: 0)
            val batPower = batteryPower ?: power ?: return when {
                chargeStatus != null && chargeGunConnected == true -> "外部电源 → 电池"
                else -> null
            }
            return when {
                chargeGunConnected == true && batPower < -0.5f -> "外部电源 → 电池"
                batPower < -0.5f && engineRunning -> "发动机 → 电池（发电）"
                batPower < -0.5f -> "车轮 → 电机 → 电池（能量回收）"
                engineRunning && motorRpm > 0 -> "发动机 + 电池 → 车轮"
                engineRunning -> "发动机 → 车轮"
                batPower > 0.5f -> "电池 → 电机 → 车轮"
                else -> "静止 / 无明显能量流"
            }
        }
}

/**
 * 用已有读数补齐可推算的字段（只补 null，不覆盖数据源给的值）：
 * - 电池功率 = 总电压 × 电流；
 * - 瞬时电耗 = 功率 / 车速 × 100（kW ÷ km/h = kWh/km），车速过低时无意义不计算。
 */
fun VehicleState.withDerivedValues(): VehicleState {
    val batPower = batteryPower
        ?: if (batteryVoltage != null && batteryCurrent != null) batteryVoltage * batteryCurrent / 1000f else null
    val instant = instantElecConsumption ?: run {
        val p = batPower ?: power
        val v = speed
        if (p != null && v != null && v >= 5f) p / v * 100f else null
    }
    return if (batPower == batteryPower && instant == instantElecConsumption) this
    else copy(batteryPower = batPower, instantElecConsumption = instant)
}

/**
 * 泊车雷达各探头的障碍等级（BYDAutoRadarDevice.getRadarProbeState(区域)）。
 *
 * 车机常量：RADAR_OBSTACLE_DISTANCE_MIN=0 / MAX=6（等级，越小越近）、SAFE=14（无障碍）。
 * 等级与实际厘米的对应关系尚未在实车核对，先原样展示。[?]
 */
data class Radar(
    val frontLeft: Int? = null,
    val frontLeftMid: Int? = null,
    val frontRightMid: Int? = null,
    val frontRight: Int? = null,
    val rearLeft: Int? = null,
    val rearMid: Int? = null,
    val rearRight: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val reverseSwitchOn: Boolean? = null,
) {
    /** 车机用 14 表示「安全/无障碍」，只有 0..6 才是真的探到东西。 */
    private fun level(v: Int?): Int? = v?.takeIf { it in OBSTACLE_MIN..OBSTACLE_MAX }

    val front: List<Int?> get() = listOf(level(frontLeft), level(frontLeftMid), level(frontRightMid), level(frontRight))
    val rear: List<Int?> get() = listOf(level(rearLeft), level(rearMid), level(rearRight))
    val sides: List<Int?> get() = listOf(level(left), level(right))

    /** 最近的障碍等级（越小越近）；没有探到返回 null。 */
    val nearest: Int? get() = (front + rear + sides).filterNotNull().minOrNull()

    val hasAnyReading: Boolean
        get() = listOf(frontLeft, frontLeftMid, frontRightMid, frontRight, rearLeft, rearMid, rearRight, left, right)
            .any { it != null }

    companion object {
        const val OBSTACLE_MIN = 0
        const val OBSTACLE_MAX = 6
        const val SAFE = 14
    }
}

/** 四轮/四门通用容器：FL 左前、FR 右前、RL 左后、RR 右后。 */
data class Wheels<T>(
    val fl: T? = null,
    val fr: T? = null,
    val rl: T? = null,
    val rr: T? = null,
) {
    fun toList(): List<T?> = listOf(fl, fr, rl, rr)
}

/** 四门两盖开闭状态：true = 打开。 */
data class Openings(
    val fl: Boolean? = null,
    val fr: Boolean? = null,
    val rl: Boolean? = null,
    val rr: Boolean? = null,
    val hood: Boolean? = null,
    val trunk: Boolean? = null,
)

enum class AcWindMode(val label: String) {
    FACE("吹面"),
    FACE_FEET("面+脚"),
    FEET("吹脚"),
    FEET_DEFROST("脚+除霜"),
    DEFROST("除霜"),
}

enum class AcCycleMode(val label: String) {
    INNER("内循环"),
    OUTER("外循环"),
}

enum class DataSourceType(val label: String) {
    BYD_AUTO("BYDAuto"),
    DI_PLUS("迪加"),
    MOCK("模拟"),
}
