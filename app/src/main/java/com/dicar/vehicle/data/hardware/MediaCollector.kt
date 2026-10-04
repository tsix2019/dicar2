package com.dicar.vehicle.data.hardware

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import java.util.UUID

/**
 * 编解码器与 DRM。
 *
 * 列表按「硬件优先、编码/解码分开」整理：硬件编解码能不能跑某个分辨率，
 * 直接决定车机放视频卡不卡，这是同类工具里最被关心的一页。
 */
fun collectCodecs(): HwSection {
    val codecs = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
    }.getOrNull().orEmpty()

    val video = codecs.filter { it.supportedTypes.any { t -> t.startsWith("video/") } }
    val audio = codecs.filter { it.supportedTypes.any { t -> t.startsWith("audio/") } }

    return section(HwCategory.CODEC) {
        block {
            item("编解码器总数", "${codecs.size} 个")
            item("视频", "${video.count { !it.isEncoder }} 解码 / ${video.count { it.isEncoder }} 编码")
            item("音频", "${audio.count { !it.isEncoder }} 解码 / ${audio.count { it.isEncoder }} 编码")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                item("硬件加速", "${codecs.count { it.isHardwareAccelerated }} 个")
                item("纯软件", "${codecs.count { it.isSoftwareOnly }} 个")
            }
        }

        codecBlock("视频解码", video.filter { !it.isEncoder })
        codecBlock("视频编码", video.filter { it.isEncoder })
        codecBlock("音频解码", audio.filter { !it.isEncoder })
        codecBlock("音频编码", audio.filter { it.isEncoder })

        block("数字版权（DRM）") {
            val widevine = drmProps(WIDEVINE)
            item("Widevine", if (widevine.isEmpty()) "不支持" else "支持")
            widevine.forEach { (k, v) -> item("  $k", v) }
            item("PlayReady", if (drmProps(PLAYREADY).isEmpty()) "不支持" else "支持")
            item("ClearKey", if (drmProps(CLEARKEY).isEmpty()) "不支持" else "支持")
        }
    }
}

private fun SectionScope.codecBlock(title: String, list: List<MediaCodecInfo>) {
    if (list.isEmpty()) return
    block(title) {
        // 硬件的排前面：用户真正关心的是「这台机子硬解能撑到哪」
        list.sortedWith(compareByDescending<MediaCodecInfo> { hardware(it) }.thenBy { it.name })
            .forEach { info -> item(info.name, codecSummary(info)) }
    }
}

private fun hardware(info: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
    // 安卓 10 之前只能看名字：OMX.google.* / c2.android.* 是 AOSP 的软件实现
    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

private fun codecSummary(info: MediaCodecInfo): String {
    val type = info.supportedTypes.firstOrNull() ?: return HwItem.UNKNOWN
    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
    val parts = mutableListOf(if (hardware(info)) "硬件" else "软件")
    parts += type.substringAfter('/')

    caps?.videoCapabilities?.let { v ->
        runCatching {
            val w = v.supportedWidths.upper
            val h = v.supportedHeights.upper
            parts += "最高 $w×$h"
            parts += "${v.getSupportedFrameRatesFor(w, h).upper.toInt()} fps"
            // 低码率编解码器（h263 上限 384 kbps）整除成 0 会看着像坏了
            parts += HwFormat.kbps(v.bitrateRange.upper / 1000)
        }
    }
    caps?.audioCapabilities?.let { a ->
        runCatching {
            a.supportedSampleRateRanges.lastOrNull()?.let { parts += "最高 ${it.upper} Hz" }
            parts += "${a.maxInputChannelCount} 声道"
        }
    }
    caps?.profileLevels?.size?.takeIf { it > 0 }?.let { parts += "$it 个档次" }
    runCatching { caps?.maxSupportedInstances }.getOrNull()?.takeIf { it > 0 }?.let { parts += "并发 $it" }
    return parts.joinToString(" · ")
}

/** 读 DRM 方案的属性。方案不存在时构造函数就会抛，返回空表即可。 */
private fun drmProps(uuid: UUID): Map<String, String> {
    if (!runCatching { MediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)) return emptyMap()
    var drm: MediaDrm? = null
    return try {
        drm = MediaDrm(uuid)
        buildMap {
            DRM_PROPERTIES.forEach { (key, label) ->
                runCatching { drm.getPropertyString(key) }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { put(label, it) }
            }
            runCatching { drm.connectedHdcpLevel }.getOrNull()?.let { put("HDCP 等级", hdcpName(it)) }
            runCatching { drm.maxHdcpLevel }.getOrNull()?.let { put("HDCP 上限", hdcpName(it)) }
            runCatching { drm.maxSessionCount }.getOrNull()?.takeIf { it > 0 }
                ?.let { put("最大会话数", it.toString()) }
        }
    } catch (e: Throwable) {
        emptyMap()
    } finally {
        runCatching { drm?.close() }
    }
}

private fun hdcpName(level: Int): String = when (level) {
    MediaDrm.HDCP_NONE -> "无"
    MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> "无数字输出"
    MediaDrm.HDCP_V1 -> "v1"
    MediaDrm.HDCP_V2 -> "v2"
    MediaDrm.HDCP_V2_1 -> "v2.1"
    MediaDrm.HDCP_V2_2 -> "v2.2"
    MediaDrm.HDCP_V2_3 -> "v2.3"
    else -> HwItem.UNKNOWN
}

private val DRM_PROPERTIES = listOf(
    // L1 = 整条解码链在 TEE 里，能放 1080p 以上的正版流媒体；L3 只能软解，画质被限制
    "securityLevel" to "安全等级",
    "version" to "版本",
    "vendor" to "厂商",
    "description" to "说明",
    "algorithms" to "算法",
    "systemId" to "系统 ID",
    "maxNumberOfSessions" to "最大会话数",
)

private val WIDEVINE = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
private val PLAYREADY = UUID(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)
private val CLEARKEY = UUID(0x1077efecc0b24d02L, -0x531cc3e1ad1d04b5L)
