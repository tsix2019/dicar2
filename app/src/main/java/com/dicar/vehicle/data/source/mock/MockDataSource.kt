package com.dicar.vehicle.data.source.mock

import com.dicar.vehicle.data.model.AcCycleMode
import com.dicar.vehicle.data.model.AcWindMode
import com.dicar.vehicle.data.model.CommandResult
import com.dicar.vehicle.data.model.DataSourceType
import com.dicar.vehicle.data.model.Openings
import com.dicar.vehicle.data.model.QuickAction
import com.dicar.vehicle.data.model.VehicleCommand
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.data.model.Wheels
import com.dicar.vehicle.data.model.Zone
import com.dicar.vehicle.data.source.VehicleDataSource
import kotlin.math.abs
import kotlin.math.sin

/**
 * 模拟数据源：没有车机时在模拟器/手机上调 UI 用。
 * 行驶数据随时间正弦变化；空调状态可被控制指令修改，用来验证「乐观更新 + 回读确认」链路。
 * 故意留几个字段为 null（胎温、PM2.5、海拔），用来检查 N/A 展示。
 */
class MockDataSource : VehicleDataSource {

    override val type = DataSourceType.MOCK

    private val startedAt = System.currentTimeMillis()
    private var ac = VehicleState(
        acOn = true,
        acAuto = false,
        acTempDriver = 22.0f,
        acTempPassenger = 22.5f,
        acFanLevel = 3,
        acWindMode = AcWindMode.FACE,
        acCycle = AcCycleMode.OUTER,
        seatHeatDriver = 0,
        seatHeatPassenger = 0,
        seatVentDriver = 1,
        seatVentPassenger = 0,
    )

    override suspend fun isAvailable() = true

    override suspend fun read(): VehicleState {
        val t = (System.currentTimeMillis() - startedAt) / 1000.0
        val speed = (60 + 40 * sin(t / 8)).toFloat().coerceAtLeast(0f)
        val accel = (35 + 35 * sin(t / 3)).toFloat().coerceIn(0f, 100f)
        val brake = if (accel < 8f) 30f else 0f
        val motorPower = (accel * 1.6f - brake * 0.8f)
        val engineOn = speed > 85f
        val soc = (78.6 - t / 600).toFloat()
        return ac.copy(
            speed = speed,
            engineRpm = if (engineOn) (1400 + speed * 12).toInt() else 0,
            motorRpmFront = (speed * 95).toInt(),
            motorRpmRear = null,
            power = motorPower + if (engineOn) 25f else 0f,
            motorPower = motorPower,
            enginePower = if (engineOn) 25f else 0f,
            accelerator = accel,
            brake = brake,
            gear = "D",
            workMode = if (engineOn) "HEV" else "EV",
            driveMode = "ECO",
            soc = soc,
            batteryTempMax = 31.0f,
            batteryTempMin = 28.0f,
            batteryTempAvg = 29.5f,
            batteryVoltage = 535.2f,
            cellVoltageMax = 3.342f,
            cellVoltageMin = 3.329f,
            batteryCurrent = motorPower * 1000f / 535.2f,
            batteryPower = motorPower,
            chargeGunConnected = false,
            chargeStatus = "未充电",
            remainRangeElec = (soc * 1.2f).toInt(),
            remainRangeFuel = 820,
            fuelPercent = 64f,
            instantElecConsumption = abs(motorPower) / speed.coerceAtLeast(1f) * 100f,
            instantFuelConsumption = if (engineOn) 5.2f else 0f,
            tripElecConsumption = 14.8f,
            tripFuelConsumption = 1.9f,
            voltage12V = 13.8f,
            insideTemp = 24.5f,
            outsideTemp = 31.0f,
            // 车身开合按固定周期轮着来。原来这里全写死 false/0，结果开门动画、
            // 车窗升降这些联动在无车调试时一次都跑不到，等于没被验收过。
            doors = bodyCycle(t),
            windowPercent = windowCycle(t),
            sunroofPercent = 0,
            sunshadePercent = 40,
            tirePressure = Wheels(250f, 252f, 248f, 249f),
            tireTemp = Wheels(),
            steeringAngle = (15 * sin(t / 2)).toFloat(),
            seatbeltDriver = true,
            seatbeltPassenger = false,
            passengerPresent = false, // 副驾没人：界面应显示「无人」而不是「未系」
            windowAntiPinch = true,
            turnLeft = (t.toInt() % 6) < 1,
            turnRight = false,
            totalMileage = 12_345.6f,
            tripMileage = (t / 60).toFloat(),
            slope = 1.5f,
        )
    }

    override suspend fun execute(command: VehicleCommand): CommandResult {
        // 模拟车机下发延迟
        Thread.sleep(150)
        when (command) {
            is VehicleCommand.Quick -> if (command.action == QuickAction.PURIFY) {
                return CommandResult.Unsupported("模拟：本车无净化功能")
            }
            is VehicleCommand.SeatVent -> if (command.seat == Zone.PASSENGER) {
                return CommandResult.Unsupported("模拟：副驾无通风")
            }
            else -> Unit
        }
        ac = command.applyTo(ac)
        return CommandResult.Sent
    }

    /**
     * 车门/两盖的演示循环：每 [BODY_PERIOD] 秒走一轮，依次打开四门、引擎盖、后备箱，
     * 最后全关。目的是让无车调试也能看到孪生图上的开合动画，而不是永远一台闭合的车。
     */
    private fun bodyCycle(t: Double): Openings {
        val phase = ((t % BODY_PERIOD) / BODY_PERIOD * 7).toInt()
        return Openings(
            fl = phase == 1,
            fr = phase == 2,
            rl = phase == 3,
            rr = phase == 3,
            hood = phase == 4,
            trunk = phase == 5,
        )
    }

    /** 车窗演示：左前窗缓慢升降，其余三个各停在一个固定开度。 */
    private fun windowCycle(t: Double): Wheels<Int> {
        val wave = ((1 - kotlin.math.cos(t / 7)) / 2 * 100).toInt().coerceIn(0, 100)
        return Wheels(fl = wave, fr = 0, rl = 35, rr = 0)
    }

    private companion object {
        const val BODY_PERIOD = 28.0
    }
}
