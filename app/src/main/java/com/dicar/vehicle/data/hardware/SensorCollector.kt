package com.dicar.vehicle.data.hardware

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size

/** 传感器。逐个列出完整参数。 */
fun collectSensors(context: Context): HwSection {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val sensors = sm?.getSensorList(Sensor.TYPE_ALL).orEmpty()

    return section(HwCategory.SENSOR) {
        block {
            item("数量", "${sensors.size} 个")
            item("唤醒型", "${sensors.count { it.isWakeUpSensor }} 个")
            item("厂商", HwFormat.list(sensors.map { it.vendor }.distinct()))
        }

        sensors.sortedBy { it.type }.forEach { s ->
            block("${sensorTypeName(s.type)}（${s.name}）") {
                item("厂商", s.vendor)
                item("类型编号", "${s.type}" + (s.stringType?.let { " · $it" } ?: ""))
                item("版本", s.version.toString())
                item("量程", "${s.maximumRange}")
                item("分辨率", "${s.resolution}")
                item("功耗", "${s.power} mA")
                // minDelay 是最小采样周期（微秒），倒过来才是最高频率，后者更好理解
                item(
                    "最高频率",
                    when {
                        s.minDelay > 0 -> "%.0f Hz（最小间隔 %d µs）".format(1_000_000f / s.minDelay, s.minDelay)
                        s.minDelay == 0 -> "仅在变化时上报"
                        else -> "单次触发"
                    },
                )
                if (s.maxDelay > 0) item("最大间隔", "${s.maxDelay} µs")
                item("上报模式", reportingMode(s.reportingMode))
                flag("唤醒型", s.isWakeUpSensor)
                if (s.fifoMaxEventCount > 0) {
                    item("批处理缓冲", "${s.fifoReservedEventCount} / ${s.fifoMaxEventCount} 条")
                }
                item("传感器 ID", s.id.toString())
                if (s.highestDirectReportRateLevel > 0) {
                    item("直通上报等级", s.highestDirectReportRateLevel.toString())
                }
            }
        }
    }
}

/** 摄像头。车机上通常是倒车影像和 360 环视。 */
fun collectCameras(context: Context): HwSection {
    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    // 只读参数，不开相机，所以不需要 CAMERA 权限
    val ids = runCatching { cm?.cameraIdList }.getOrNull().orEmpty()

    return section(HwCategory.CAMERA) {
        block {
            item("数量", "${ids.size} 个")
            itemIfPresent("ID 列表", ids.joinToString("、"))
        }

        ids.forEach { id ->
            val c = runCatching { cm?.getCameraCharacteristics(id) }.getOrNull() ?: return@forEach
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            block("摄像头 $id（${facing(c.get(CameraCharacteristics.LENS_FACING))}）") {
                item("最大分辨率", map?.maxJpegSize()?.let { "${it.width} × ${it.height}（${megapixels(it)}）" })
                item("像素阵列", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    ?.let { "${it.width} × ${it.height}" })
                item("有效区域", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    ?.let { "${it.width()} × ${it.height()}" })
                item("感光元件尺寸", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    ?.let { "%.2f × %.2f mm".format(it.width, it.height) })
                item("焦距", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.joinToString("、") { "%.2f mm".format(it) })
                item("光圈", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.joinToString("、") { "f/%.1f".format(it) })
                item("最大数码变焦", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?.let { "%.1f×".format(it) })
                item("ISO 范围", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?.let { "${it.lower} ~ ${it.upper}" })
                item("曝光时间", c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    ?.let { "${it.lower / 1000} µs ~ ${it.upper / 1_000_000} ms" })
                flag("闪光灯", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE), yes = "有", no = "无")
                item("安装角度", c.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let { "$it°" })
                item("硬件等级", hardwareLevel(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
                item("对焦模式数", c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.size?.toString())
                item("支持格式", map?.outputFormats?.joinToString("、") { imageFormat(it) })
                item("视频规格", map?.highSpeedVideoSizes()?.takeIf { it.isNotEmpty() }
                    ?.joinToString("、") { "${it.width}×${it.height}" })
                itemIfPresent("逻辑多摄", runCatching { c.physicalCameraIds }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.joinToString("、"))
            }
        }
    }
}

private fun StreamConfigurationMap.maxJpegSize(): Size? =
    runCatching { getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } }.getOrNull()

private fun StreamConfigurationMap.highSpeedVideoSizes(): List<Size> =
    runCatching { highSpeedVideoSizes?.toList() }.getOrNull().orEmpty()

private fun megapixels(size: Size): String =
    "%.1f MP".format(size.width.toLong() * size.height / 1_000_000f)

private fun facing(v: Int?): String = when (v) {
    CameraCharacteristics.LENS_FACING_FRONT -> "前置"
    CameraCharacteristics.LENS_FACING_BACK -> "后置"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
    else -> HwItem.UNKNOWN
}

private fun hardwareLevel(v: Int?): String = when (v) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY（仅兼容旧接口）"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> HwItem.UNKNOWN
}

private fun imageFormat(v: Int): String = when (v) {
    ImageFormat.JPEG -> "JPEG"
    ImageFormat.YUV_420_888 -> "YUV420"
    ImageFormat.YUV_422_888 -> "YUV422"
    ImageFormat.RAW_SENSOR -> "RAW"
    ImageFormat.RAW10 -> "RAW10"
    ImageFormat.RAW12 -> "RAW12"
    ImageFormat.PRIVATE -> "PRIVATE"
    ImageFormat.NV21 -> "NV21"
    ImageFormat.DEPTH16 -> "DEPTH16"
    ImageFormat.HEIC -> "HEIC"
    else -> "0x%x".format(v)
}

private fun reportingMode(mode: Int): String = when (mode) {
    Sensor.REPORTING_MODE_CONTINUOUS -> "连续"
    Sensor.REPORTING_MODE_ON_CHANGE -> "变化时"
    Sensor.REPORTING_MODE_ONE_SHOT -> "单次"
    Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "特殊触发"
    else -> HwItem.UNKNOWN
}

/** 常量名比数字好认，但安卓没有现成的反查，只能自己列。 */
private fun sensorTypeName(type: Int): String = when (type) {
    Sensor.TYPE_ACCELEROMETER -> "加速度计"
    Sensor.TYPE_MAGNETIC_FIELD -> "磁力计"
    Sensor.TYPE_GYROSCOPE -> "陀螺仪"
    Sensor.TYPE_LIGHT -> "光线感应"
    Sensor.TYPE_PRESSURE -> "气压计"
    Sensor.TYPE_PROXIMITY -> "距离感应"
    Sensor.TYPE_GRAVITY -> "重力"
    Sensor.TYPE_LINEAR_ACCELERATION -> "线性加速度"
    Sensor.TYPE_ROTATION_VECTOR -> "旋转矢量"
    Sensor.TYPE_RELATIVE_HUMIDITY -> "湿度"
    Sensor.TYPE_AMBIENT_TEMPERATURE -> "环境温度"
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "磁力计（未校准）"
    Sensor.TYPE_GAME_ROTATION_VECTOR -> "游戏旋转矢量"
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "陀螺仪（未校准）"
    Sensor.TYPE_SIGNIFICANT_MOTION -> "显著运动"
    Sensor.TYPE_STEP_DETECTOR -> "计步检测"
    Sensor.TYPE_STEP_COUNTER -> "计步器"
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "地磁旋转矢量"
    Sensor.TYPE_HEART_RATE -> "心率"
    Sensor.TYPE_POSE_6DOF -> "6 自由度位姿"
    Sensor.TYPE_STATIONARY_DETECT -> "静止检测"
    Sensor.TYPE_MOTION_DETECT -> "运动检测"
    Sensor.TYPE_HEART_BEAT -> "心跳"
    Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> "离身检测"
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "加速度计（未校准）"
    Sensor.TYPE_HINGE_ANGLE -> "铰链角度"
    else -> "传感器"
}
