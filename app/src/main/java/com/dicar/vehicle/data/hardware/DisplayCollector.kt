package com.dicar.vehicle.data.hardware

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import kotlin.math.hypot

/**
 * 屏幕。分辨率、刷新率、HDR、触摸能力。
 *
 * 车机常常挂着不止一块屏（中控 + 仪表 + HUD），所以这里把 [DisplayManager]
 * 报出来的每一块都列出来，而不是只看主屏。
 */
@Suppress("DEPRECATION") // getRealMetrics 在 API 31 起被 WindowMetrics 取代，但 minSdk 28 还得靠它
fun collectDisplay(context: Context): HwSection {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val displays = dm?.displays.orEmpty()
    val main = displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } ?: displays.firstOrNull()
    val metrics = DisplayMetrics().also { main?.getRealMetrics(it) }
    val config = context.resources.configuration
    val pm = context.packageManager

    return section(HwCategory.DISPLAY) {
        block {
            item("分辨率", if (metrics.widthPixels > 0) "${metrics.widthPixels} × ${metrics.heightPixels}" else null)
            item("像素密度", metrics.densityDpi.takeIf { it > 0 }?.let { "$it dpi（${densityBucket(it)}）" })
            item("实测密度", if (metrics.xdpi > 0) "x ${metrics.xdpi.toInt()} / y ${metrics.ydpi.toInt()} dpi" else null)
            item("对角线", physicalInches(metrics))
            item("缩放倍率", metrics.density.takeIf { it > 0 }?.let { "${it}×" })
            item("逻辑尺寸", "${config.screenWidthDp} × ${config.screenHeightDp} dp")
            item("最小宽度", "${config.smallestScreenWidthDp} dp")
            item("屏幕档位", layoutSize(config))
        }

        block("刷新与色彩") {
            item("当前刷新率", main?.refreshRate?.let { "%.1f Hz".format(it) })
            val modes = main?.supportedModes.orEmpty()
            item("显示模式", modes.size.takeIf { it > 0 }?.let { "$it 种" })
            modes.sortedByDescending { it.refreshRate }.take(MAX_MODES).forEach { m ->
                item("  模式 ${m.modeId}", "${m.physicalWidth} × ${m.physicalHeight} @ %.1f Hz".format(m.refreshRate))
            }
            item("广色域", config.isScreenWideColorGamut)
            item("HDR", config.isScreenHdr)
            val hdrTypes = main?.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
            itemIfPresent("HDR 格式", hdrTypes.takeIf { it.isNotEmpty() }?.joinToString("、") { hdrName(it) })
            flag(
                "夜间模式",
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
                yes = "开启", no = "关闭",
            )
        }

        block("状态") {
            item("方向", orientation(main?.rotation))
            item("显示状态", displayState(main?.state))
            item(
                "亮度",
                runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrNull()?.let { "$it / 255" },
            )
            item(
                "亮度模式",
                runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
                }.getOrNull()?.let { if (it == 1) "自动" else "手动" },
            )
            // DisplayCutout 本身是 API 28 加的，但从 Display 上直接取要到 API 29
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cutout = main?.cutout
                item("刘海/挖孔", if (cutout == null) "无" else "有（${cutout.boundingRects.size} 处）")
            }
        }

        if (displays.size > 1) {
            block("其他显示设备") {
                displays.filter { it.displayId != main?.displayId }.forEach { d ->
                    val m = DisplayMetrics().also { d.getRealMetrics(it) }
                    item("#${d.displayId} ${d.name}", "${m.widthPixels} × ${m.heightPixels} · ${displayState(d.state)}")
                }
            }
        }

        block("触摸") {
            item("触摸屏", pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            item(
                "多点触控",
                when {
                    pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND) -> "支持（5 点以上）"
                    pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT) -> "支持（2 点独立）"
                    pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) -> "支持（基础）"
                    else -> "不支持"
                },
            )
            item("压感", pm.hasSystemFeature("android.hardware.sensor.stylus"))
            item("输入设备", inputDevices())
        }
    }
}

private fun inputDevices(): String? {
    val ids = runCatching { android.view.InputDevice.getDeviceIds() }.getOrNull() ?: return null
    val names = ids.toList().mapNotNull { android.view.InputDevice.getDevice(it)?.name }
    return names.takeIf { it.isNotEmpty() }?.joinToString("、")
}

/** 对角线英寸。xdpi/ydpi 在部分车机上是厂商乱填的，明显离谱就不显示。 */
private fun physicalInches(m: DisplayMetrics): String? {
    if (m.xdpi <= 1f || m.ydpi <= 1f || m.widthPixels <= 0) return null
    val inches = hypot(m.widthPixels / m.xdpi, m.heightPixels / m.ydpi)
    return if (inches in 1f..60f) HwFormat.inches(inches) else null
}

private fun densityBucket(dpi: Int): String = when {
    dpi <= 120 -> "ldpi"
    dpi <= 160 -> "mdpi"
    dpi <= 213 -> "tvdpi"
    dpi <= 240 -> "hdpi"
    dpi <= 320 -> "xhdpi"
    dpi <= 480 -> "xxhdpi"
    dpi <= 640 -> "xxxhdpi"
    else -> "超高密度"
}

private fun layoutSize(config: Configuration): String =
    when (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
        Configuration.SCREENLAYOUT_SIZE_SMALL -> "小屏"
        Configuration.SCREENLAYOUT_SIZE_NORMAL -> "标准"
        Configuration.SCREENLAYOUT_SIZE_LARGE -> "大屏"
        Configuration.SCREENLAYOUT_SIZE_XLARGE -> "超大屏"
        else -> HwItem.UNKNOWN
    }

private fun orientation(rotation: Int?): String = when (rotation) {
    Surface.ROTATION_0 -> "0°"
    Surface.ROTATION_90 -> "90°"
    Surface.ROTATION_180 -> "180°"
    Surface.ROTATION_270 -> "270°"
    else -> HwItem.UNKNOWN
}

private fun displayState(state: Int?): String = when (state) {
    Display.STATE_ON -> "点亮"
    Display.STATE_OFF -> "熄灭"
    Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "低功耗显示"
    Display.STATE_VR -> "VR"
    Display.STATE_ON_SUSPEND -> "点亮（已挂起）"
    else -> HwItem.UNKNOWN
}

private fun hdrName(type: Int): String = when (type) {
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
    else -> "类型 $type"
}

/** 模式太多时只列最快的几个，完整列表意义不大。 */
private const val MAX_MODES = 8
