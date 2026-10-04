package com.dicar.vehicle.data.source.diplus

import com.dicar.vehicle.data.model.AcCycleMode
import com.dicar.vehicle.data.model.AcWindMode
import com.dicar.vehicle.data.model.CommandResult
import com.dicar.vehicle.data.model.DataSourceType
import com.dicar.vehicle.data.model.GlassZone
import com.dicar.vehicle.data.model.Openings
import com.dicar.vehicle.data.model.QuickAction
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.Wheels
import com.dicar.vehicle.data.model.Zone
import com.dicar.vehicle.data.source.VehicleDataSource
import com.dicar.vehicle.util.ValueSanitizer
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * 备用/验证数据源：迪加（DiPlus）本机 HTTP 接口 127.0.0.1:8988。
 *
 * 读：GET /api/getDiPars?text=别名:{中文参数名}|别名:{中文参数名}...
 *     返回 {"success":true,"val":"别名:值|别名:值"}（来源：BYDMate v2.8.1 DiParsClient.kt，已实车使用）
 * 写：GET /api/sendCmd?cmd=迪加<指令>。注意：无论指令是否有效都返回 success，必须靠回读确认。
 *
 * 参数名与编码来自 BYDMate 及其迪加研究文档（D+ 1.3.8）；标 [?] 的为推测，请用迪加 App 核对。
 */
class DiPlusDataSource(
    private val baseUrl: String = "http://127.0.0.1:8988",
) : VehicleDataSource {

    override val type = DataSourceType.DI_PLUS

    override suspend fun isAvailable(): Boolean = try {
        query("SOC:{电量百分比}")
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun read(): VehicleState {
        val v = query(TEMPLATE)
        fun d(alias: String, min: Double, max: Double) = ValueSanitizer.inRange(v[alias], min, max)
        fun f(alias: String, min: Double, max: Double, scale: Double = 1.0) = d(alias, min, max)?.let { (it * scale).toFloat() }
        fun i(alias: String, min: Int, max: Int) = d(alias, min.toDouble(), max.toDouble())?.toInt()
        fun b(alias: String) = i(alias, 0, 1)?.let { it == 1 }

        val gun = i("ChargeGun", 1, 5)
        val volt12 = d("V12", 0.1, 20_000.0)?.let { if (it > 100) it / 1000 else it }?.toFloat()

        return VehicleState(
            speed = f("Speed", 0.0, 300.0),
            engineRpm = i("EngRpm", 0, 10_000),
            motorRpmFront = i("MotRpmF", -25_000, 25_000),
            motorRpmRear = i("MotRpmR", -25_000, 25_000),
            power = f("Power", -500.0, 1_000.0),
            accelerator = f("Accel", 0.0, 100.0),
            brake = f("Brake", 0.0, 100.0),
            gear = i("Gear", 0, 15)?.let { GEAR_LABELS[it] },
            workMode = i("WorkMode", 0, 15)?.let { WORK_MODE_LABELS[it] ?: "模式 $it" },
            driveMode = i("DriveMode", 0, 15)?.let { DRIVE_MODE_LABELS[it] ?: "模式 $it" },

            soc = f("SOC", 0.0, 100.0),
            batteryTempMax = f("BatTMax", -40.0, 80.0),
            batteryTempMin = f("BatTMin", -40.0, 80.0),
            batteryTempAvg = f("BatTAvg", -40.0, 80.0),
            cellVoltageMax = f("CellVMax", 0.5, 5.0),
            cellVoltageMin = f("CellVMin", 0.5, 5.0),
            chargeGunConnected = gun?.let { it >= 2 },
            chargeStatus = i("ChgState", 0, 15)?.let { CHARGE_STATE_LABELS[it] }
                ?: gun?.let { CHARGE_GUN_LABELS[it] },
            fuelPercent = f("Fuel", 0.0, 100.0),
            tripElecConsumption = f("ElecPhm", -50.0, 100.0),
            voltage12V = volt12?.takeIf { it in 6f..20f },

            acOn = b("AcOn"),
            acTempDriver = f("AcTempD", 10.0, 40.0),
            acTempPassenger = f("AcTempP", 10.0, 40.0),
            acFanLevel = i("Fan", 0, 7),
            acWindMode = i("AcMode", 1, 7)?.let { WIND_MODE_CODES[it] },
            acCycle = when (i("AcCirc", 0, 1)) {
                0 -> AcCycleMode.OUTER
                1 -> AcCycleMode.INNER
                else -> null
            },
            insideTemp = f("TempIn", -50.0, 80.0),
            outsideTemp = f("TempOut", -50.0, 80.0),

            doors = Openings(
                fl = b("DoorFL"), fr = b("DoorFR"), rl = b("DoorRL"), rr = b("DoorRR"),
                hood = b("Hood"), trunk = b("Trunk"),
            ),
            windowPercent = Wheels(i("WinFL", 0, 100), i("WinFR", 0, 100), i("WinRL", 0, 100), i("WinRR", 0, 100)),
            sunroofPercent = i("Sunroof", 0, 100),
            sunshadePercent = i("Sunshade", 0, 100),
            tirePressure = Wheels(f("TpFL", 0.0, 500.0), f("TpFR", 0.0, 500.0), f("TpRL", 0.0, 500.0), f("TpRR", 0.0, 500.0)),
            steeringAngle = f("Steer", -900.0, 900.0),
            seatbeltDriver = b("BeltFL"),
            seatbeltPassenger = b("BeltFR"),
            turnLeft = b("TurnL"),
            turnRight = b("TurnR"),

            totalMileage = f("Mileage", 0.0, 20_000_000.0, scale = 0.1),
            slope = f("Slope", -60.0, 60.0),

            acTempStep = 1f, // 「设置温度N」只收整数
            seatMaxLevel = 2, // 迪加座椅指令只有 1、2 档
        )
    }

    override suspend fun execute(command: VehicleCommand): CommandResult {
        val cmd: String = when (command) {
            is VehicleCommand.AcPower -> if (command.on) "开空调" /*[?]*/ else "关空调"
            is VehicleCommand.AcAuto -> if (command.on) "自动空调" else return unsupported("退出自动")
            is VehicleCommand.AcTemperature -> {
                if (command.zone == Zone.PASSENGER) return unsupported("副驾温度单独设置")
                "设置温度${command.celsius.roundToInt()}"
            }
            is VehicleCommand.AcFanLevel -> "设置风速${command.level}"
            is VehicleCommand.AcWind -> when (command.mode) {
                AcWindMode.FACE -> "空调吹面"
                AcWindMode.FACE_FEET -> "空调吹面吹脚"
                AcWindMode.FEET -> "空调吹脚"
                AcWindMode.FEET_DEFROST -> "空调吹脚除霜"
                AcWindMode.DEFROST -> "空调除霜"
            }
            is VehicleCommand.AcCycle -> if (command.mode == AcCycleMode.INNER) "内循环" else "外循环"
            is VehicleCommand.SeatHeat -> seatCommand(command.seat, "加热", command.level) ?: return unsupported("3 档（迪加只有 1-2 档）")
            is VehicleCommand.SeatVent -> seatCommand(command.seat, "通风", command.level) ?: return unsupported("3 档（迪加只有 1-2 档）")
            // 迪加的玻璃指令是「主驾打开100」「天窗打开30」这种中文宏
            is VehicleCommand.Glass -> when (command.zone) {
                GlassZone.WINDOW_FL -> "主驾${glassSuffix(command.open)}"
                GlassZone.WINDOW_FR -> "副驾${glassSuffix(command.open)}"
                GlassZone.WINDOW_RL -> "左后${glassSuffix(command.open)}"
                GlassZone.WINDOW_RR -> "右后${glassSuffix(command.open)}"
                GlassZone.SUNROOF -> "天窗${glassSuffix(command.open)}"
                GlassZone.SUNSHADE -> "遮阳帘${glassSuffix(command.open)}"
            }

            is VehicleCommand.Quick -> when (command.action) {
                QuickAction.FRONT_DEFROST -> "吹前挡"
                QuickAction.QUICK_COOL, QuickAction.PURIFY -> return unsupported(command.action.label)
            }
        }
        return try {
            val body = httpGet("$baseUrl/api/sendCmd?cmd=" + URLEncoder.encode(CMD_PREFIX + cmd, "UTF-8"))
            if (JSONObject(body).optBoolean("success")) CommandResult.Sent
            else CommandResult.Failed("迪加返回失败")
        } catch (e: IOException) {
            CommandResult.Failed("迪加无响应：${e.message}")
        }
    }

    private fun seatCommand(seat: Zone, kind: String, level: Int): String? {
        val who = if (seat == Zone.DRIVER) "主驾" else "副驾"
        return when (level) {
            0 -> "${who}座椅${kind}关闭"
            1, 2 -> "${who}座椅${kind}${level}档"
            else -> null
        }
    }

    /** 迪加的开合宏：「打开100」=全开、「打开0」=关。 */
    private fun glassSuffix(open: Boolean) = if (open) "打开100" else "打开0"

    private fun unsupported(what: String) = CommandResult.Unsupported("迪加不支持$what")

    /** 调 getDiPars 并解析出 别名 → 值字符串。连接失败抛 IOException，交给 Repository 降级。 */
    private fun query(template: String): Map<String, String> {
        val body = httpGet("$baseUrl/api/getDiPars?text=" + URLEncoder.encode(template, "UTF-8"))
        val json = JSONObject(body)
        if (!json.optBoolean("success")) throw IOException("迪加 getDiPars 返回失败")
        return parseVal(json.optString("val"))
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val CMD_PREFIX = "迪加"
        private const val CONNECT_TIMEOUT_MS = 500
        private const val READ_TIMEOUT_MS = 1_500

        /** 别名 → 迪加中文参数名。别名只用字母数字，不能含 : 或 |。 */
        val PARAMS: List<Pair<String, String>> = listOf(
            "Speed" to "车速",
            "EngRpm" to "发动机转速",
            "MotRpmF" to "前电机转速",
            "MotRpmR" to "后电机转速",
            "Power" to "发动机功率",          // 实为整车功率，负值=回收（BYDMate 当作驱动功率使用）
            "Accel" to "加速踏板深度",
            "Brake" to "刹车深度",
            "Gear" to "档位",
            "WorkMode" to "整车工作模式",
            "DriveMode" to "整车运行模式",
            "SOC" to "电量百分比",
            "BatTMax" to "最高电池温度",
            "BatTMin" to "最低电池温度",
            "BatTAvg" to "平均电池温度",
            "CellVMax" to "最高电池电压",
            "CellVMin" to "最低电池电压",
            "ChargeGun" to "充电枪插枪状态",
            "ChgState" to "充电状态",
            "Fuel" to "油量百分比",
            "ElecPhm" to "百公里电耗",        // [?] 口径未知，暂作行程电耗显示
            "V12" to "蓄电池电压",
            "AcOn" to "空调状态",
            "AcTempD" to "主驾驶空调温度",
            "AcTempP" to "副驾驶空调温度",     // [?] 未在文档中出现
            "Fan" to "风量档位",
            "AcMode" to "空调出风模式",
            "AcCirc" to "空调循环方式",
            "TempIn" to "车内温度",
            "TempOut" to "车外温度",
            "DoorFL" to "主驾车门",
            "DoorFR" to "副驾车门",
            "DoorRL" to "左后车门",
            "DoorRR" to "右后车门",
            "Hood" to "引擎盖",
            "Trunk" to "后备箱门",
            "WinFL" to "主驾车窗打开百分比",
            "WinFR" to "副驾车窗打开百分比",
            "WinRL" to "左后车窗打开百分比",
            "WinRR" to "右后车窗打开百分比",
            "Sunroof" to "天窗打开百分比",
            "Sunshade" to "遮阳帘打开百分比",
            "TpFL" to "左前轮气压",
            "TpFR" to "右前轮气压",
            "TpRL" to "左后轮气压",
            "TpRR" to "右后轮气压",
            "Steer" to "方向盘转角",
            "BeltFL" to "主驾驶安全带状态",
            "BeltFR" to "副驾安全带",          // [?] 编码按主驾推测
            "TurnL" to "左转向灯",
            "TurnR" to "右转向灯",
            "Mileage" to "里程",               // ÷10 = km
            "Slope" to "坡度",
        )

        val TEMPLATE: String = PARAMS.joinToString("|") { (alias, name) -> "$alias:{$name}" }

        val GEAR_LABELS = mapOf(1 to "P", 2 to "R", 3 to "N", 4 to "D")
        val WORK_MODE_LABELS = mapOf(0 to "停止", 1 to "EV", 2 to "强制EV", 3 to "HEV")
        val DRIVE_MODE_LABELS = mapOf(1 to "ECO", 2 to "运动")
        val CHARGE_GUN_LABELS = mapOf(1 to "未插枪", 2 to "交流枪", 3 to "直流枪", 4 to "转换枪", 5 to "放电枪")
        val CHARGE_STATE_LABELS = mapOf(1 to "就绪", 2 to "充电中", 3 to "充电完成", 4 to "充电终止") // [?] 顺序推测

        /** 空调出风模式 1-7，按迪加指令表顺序推测 [?] */
        val WIND_MODE_CODES = mapOf(
            1 to AcWindMode.FACE,
            2 to AcWindMode.FACE_FEET,
            3 to AcWindMode.FEET,
            4 to AcWindMode.FEET_DEFROST,
            5 to AcWindMode.DEFROST,
        )

        /** "SOC:82|Speed:0|Gear:1" → {SOC=82, Speed=0, Gear=1}。纯函数，便于单测。 */
        fun parseVal(raw: String): Map<String, String> =
            raw.split('|').mapNotNull { pair ->
                val idx = pair.indexOf(':')
                if (idx <= 0) null else pair.substring(0, idx).trim() to pair.substring(idx + 1).trim()
            }.toMap()
    }
}
