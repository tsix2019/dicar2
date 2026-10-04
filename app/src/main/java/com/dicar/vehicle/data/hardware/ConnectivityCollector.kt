package com.dicar.vehicle.data.hardware

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络。
 *
 * 刻意**不读** Wi-Fi 名称和 BSSID：那两项从安卓 8.1 起要定位权限，为了一个信息展示页
 * 去申请定位很不划算。连接质量相关的（速率、频段、信号）不需要权限，照常显示。
 */
fun collectNetwork(context: Context): HwSection {
    val pm = context.packageManager
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    return section(HwCategory.NETWORK) {
        block("当前连接") {
            val active = runCatching { cm?.activeNetwork }.getOrNull()
            val caps = runCatching { active?.let { cm?.getNetworkCapabilities(it) } }.getOrNull()
            item("类型", caps?.let { transportName(it) } ?: "未连接")
            item("下行带宽", caps?.let { HwFormat.kbps(it.linkDownstreamBandwidthKbps) })
            item("上行带宽", caps?.let { HwFormat.kbps(it.linkUpstreamBandwidthKbps) })
            flag("已验证可上网", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            flag("计费网络", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.let { !it })
        }

        block("Wi-Fi") {
            item("硬件", pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
            flag("已开启", runCatching { wifi?.isWifiEnabled }.getOrNull(), yes = "开启", no = "关闭")
            item("5 GHz", runCatching { wifi?.is5GHzBandSupported }.getOrNull())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item("6 GHz", runCatching { wifi?.is6GHzBandSupported }.getOrNull())
                item("Wi-Fi 6", runCatching {
                    wifi?.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)
                }.getOrNull())
                item("Wi-Fi 5", runCatching {
                    wifi?.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC)
                }.getOrNull())
            }
            item("Wi-Fi Direct", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT))
            item("热点感知（Aware）", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
            item("RTT 测距", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
            @Suppress("DEPRECATION") // 新接口要注册回调，一次性读取用旧的就够
            val conn = runCatching { wifi?.connectionInfo }.getOrNull()
            if (conn != null && conn.networkId != -1) {
                item("协商速率", conn.linkSpeed.takeIf { it > 0 }?.let { "$it Mbps" })
                item("频段", conn.frequency.takeIf { it > 0 }?.let { "$it MHz" })
                item("信号强度", "${conn.rssi} dBm")
                @Suppress("DEPRECATION")
                item("IP 地址", intToIp(conn.ipAddress))
            }
        }

        block("移动网络") {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            item("硬件", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            item("制式", phoneType(runCatching { tm?.phoneType }.getOrNull()))
            item("运营商", runCatching { tm?.networkOperatorName }.getOrNull())
            item("SIM 状态", simState(runCatching { tm?.simState }.getOrNull()))
            item("eSIM", pm.hasSystemFeature("android.hardware.telephony.euicc"))
        }

        block("蓝牙") {
            item("硬件", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            item("低功耗（BLE）", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            // 适配器状态在安卓 12 起要 BLUETOOTH_CONNECT 运行时权限；
            // 车机是安卓 10，清单里的 BLUETOOTH 就够用。拿不到就显示未知，不去申请权限
            val adapter = runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            }.getOrNull()
            flag("已开启", runCatching { adapter?.isEnabled }.getOrNull(), yes = "开启", no = "关闭")
            item("LE 广播", runCatching { adapter?.isMultipleAdvertisementSupported }.getOrNull())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item("LE Audio", runCatching { adapter?.isLeAudioSupported == FEATURE_SUPPORTED }.getOrNull())
            }
        }

        block("其他无线") {
            item("NFC", pm.hasSystemFeature(PackageManager.FEATURE_NFC))
            item("GPS", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS))
            item("定位（网络）", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK))
            item("USB 主机模式", pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
            item("USB 外设模式", pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY))
            item("以太网", pm.hasSystemFeature("android.hardware.ethernet"))
        }

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        if (interfaces.isNotEmpty()) {
            block("网络接口") {
                interfaces.filter { runCatching { it.isUp }.getOrDefault(false) }.forEach { nif ->
                    val addresses = runCatching {
                        nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress }
                    }.getOrNull().orEmpty().filterNotNull()
                    item(
                        nif.name,
                        listOfNotNull(
                            addresses.takeIf { it.isNotEmpty() }?.joinToString("、"),
                            runCatching { "MTU ${nif.mtu}" }.getOrNull(),
                            if (runCatching { nif.isLoopback }.getOrDefault(false)) "回环" else null,
                        ).joinToString(" · ").ifEmpty { "已启用" },
                    )
                }
            }
        }
    }
}

/** 音频。采样率、延迟、当前接入的设备。 */
fun collectAudio(context: Context): HwSection {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val pm = context.packageManager

    return section(HwCategory.AUDIO) {
        block {
            // 这两个数决定了能不能做低延迟音频：按它们开 AudioTrack 才能走快速通道
            item("输出采样率", am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.let { "$it Hz" })
            item("输出缓冲", am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.let { "$it 帧" })
            item("低延迟音频", pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY))
            item("专业音频", pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO))
            item("麦克风", pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            item("MIDI", pm.hasSystemFeature(PackageManager.FEATURE_MIDI))
            item(
                "未处理音源",
                am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)?.let { it == "true" },
            )
        }

        block("音量档位") {
            VOLUME_STREAMS.forEach { (stream, label) ->
                item(label, am?.getStreamMaxVolume(stream)?.let { max ->
                    "${am.getStreamVolume(stream)} / $max"
                })
            }
        }

        val outputs = runCatching { am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull().orEmpty()
        if (outputs.isNotEmpty()) {
            block("输出设备") {
                outputs.forEach { d -> item(audioDeviceType(d.type), audioDeviceDetail(d)) }
            }
        }
        val inputs = runCatching { am?.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull().orEmpty()
        if (inputs.isNotEmpty()) {
            block("输入设备") {
                inputs.forEach { d -> item(audioDeviceType(d.type), audioDeviceDetail(d)) }
            }
        }
    }
}

private fun audioDeviceDetail(d: AudioDeviceInfo): String = listOfNotNull(
    d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
    d.sampleRates.takeIf { it.isNotEmpty() }?.let { "${it.min()}~${it.max()} Hz" },
    d.channelCounts.takeIf { it.isNotEmpty() }?.let { "${it.max()} 声道" },
).joinToString(" · ").ifEmpty { "已连接" }

private val VOLUME_STREAMS = listOf(
    AudioManager.STREAM_MUSIC to "媒体",
    AudioManager.STREAM_RING to "铃声",
    AudioManager.STREAM_ALARM to "闹钟",
    AudioManager.STREAM_NOTIFICATION to "通知",
    AudioManager.STREAM_SYSTEM to "系统",
    AudioManager.STREAM_VOICE_CALL to "通话",
)

private fun audioDeviceType(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 外设"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路"
    AudioDeviceInfo.TYPE_FM -> "调频收音"
    AudioDeviceInfo.TYPE_FM_TUNER -> "FM 调谐器"
    AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
    AudioDeviceInfo.TYPE_BUS -> "车载总线"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混音"
    else -> "类型 $type"
}

private fun transportName(caps: NetworkCapabilities): String = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙共享"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
    caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB 共享"
    else -> HwItem.UNKNOWN
}

private fun phoneType(v: Int?): String = when (v) {
    TelephonyManager.PHONE_TYPE_NONE -> "无"
    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
    else -> HwItem.UNKNOWN
}

private fun simState(v: Int?): String = when (v) {
    TelephonyManager.SIM_STATE_ABSENT -> "无 SIM 卡"
    TelephonyManager.SIM_STATE_READY -> "就绪"
    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "需要 PIN"
    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "需要 PUK"
    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "网络锁定"
    TelephonyManager.SIM_STATE_NOT_READY -> "未就绪"
    TelephonyManager.SIM_STATE_PERM_DISABLED -> "已永久禁用"
    TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "读卡错误"
    TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "受限"
    else -> HwItem.UNKNOWN
}

/** WifiManager.getConnectionInfo 给的是主机字节序的 int，要按小端拆回点分十进制。 */
private fun intToIp(raw: Int): String? {
    if (raw == 0) return null
    return (0..3).joinToString(".") { ((raw shr (it * 8)) and 0xFF).toString() }
}

/** `BluetoothStatusCodes.FEATURE_SUPPORTED`，API 33 才有。 */
private const val FEATURE_SUPPORTED = 10
