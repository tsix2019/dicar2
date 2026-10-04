package com.dicar.vehicle.data.hardware

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 系统。版本、构建、运行时、车机标识。 */
fun collectSystem(context: Context): HwSection {
    val pm = context.packageManager

    return section(HwCategory.SYSTEM) {
        block("设备") {
            item("厂商", Build.MANUFACTURER)
            item("品牌", Build.BRAND)
            item("型号", Build.MODEL)
            item("设备代号", Build.DEVICE)
            item("产品名", Build.PRODUCT)
            itemIfPresent("市售名", SysFs.firstProp("ro.product.marketname", "ro.config.marketing_name"))
            item("序列号", serial())
        }

        block("安卓") {
            item("版本", "Android ${Build.VERSION.RELEASE}")
            item("API 等级", Build.VERSION.SDK_INT.toString())
            item("安全补丁", Build.VERSION.SECURITY_PATCH)
            item("代号", Build.VERSION.CODENAME)
            item("构建 ID", Build.ID)
            item("构建版本", Build.DISPLAY)
            item("增量版本", Build.VERSION.INCREMENTAL)
            item("构建类型", "${Build.TYPE} / ${Build.TAGS}")
            item("构建时间", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(Build.TIME)))
            item("指纹", Build.FINGERPRINT)
        }

        block("内核与运行时") {
            // /proc/version 在新安卓上对应用是禁读的，但 os.version 这个 JVM 属性
            // 里就是内核版本号，拿它兜底
            val version = SysFs.text("/proc/version")
            item("内核版本", version?.let { ProcParse.kernelVersion(it) } ?: System.getProperty("os.version"))
            itemIfPresent("内核完整串", version?.replace('\n', ' '))
            item("Java 虚拟机", sysProps("java.vm.name", "java.vm.version"))
            item("ART/Dalvik", SysFs.prop("persist.sys.dalvik.vm.lib.2"))
            flag("64 位 Zygote", SysFs.prop("ro.zygote")?.contains("64"))
            item("运行平台", sysProps("os.name", "os.arch", "os.version"))
            item("时钟频率", "${HardwareMonitor.clockTicks} Hz")
        }

        block("安全与完整性") {
            // 三条合起来判断「这台机子有没有被动过」，任何一条异常都值得注意
            item("SELinux", selinux())
            flag("可调试构建", SysFs.prop("ro.debuggable") == "1")
            flag("发布签名", Build.TAGS?.contains("release-keys"))
            flag("检测到 su", suPath() != null)
            itemIfPresent("su 路径", suPath())
            flag(
                "ADB 已启用",
                Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1,
                yes = "已启用", no = "已关闭",
            )
            item("存储加密", encryption())
            item("验证启动", SysFs.firstProp("ro.boot.verifiedbootstate", "ro.boot.veritymode"))
            flag("Treble", SysFs.prop("ro.treble.enabled") == "true")
            item("VNDK 版本", SysFs.firstProp("ro.vndk.version", "persist.sys.vndk"))
        }

        block("运行状态") {
            val uptime = SystemClock.elapsedRealtime()
            item("开机时长", HwFormat.duration(uptime))
            item("其中唤醒", HwFormat.duration(SystemClock.uptimeMillis()))
            item("深度睡眠", HwFormat.duration(uptime - SystemClock.uptimeMillis()))
            item(
                "上次开机",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(Date(System.currentTimeMillis() - uptime)),
            )
            item("时区", TimeZone.getDefault().let { "${it.id}（${it.displayName}）" })
            item("语言", Locale.getDefault().toString())
            item("外部存储状态", Environment.getExternalStorageState())
        }

        block("软件环境") {
            // 安卓 11 起有「包可见性」限制：没有 QUERY_ALL_PACKAGES 时只能看到一部分。
            // 不去申请那个权限（它在商店上架要专门说明理由），而是如实标注数字不全
            val partial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "（仅本应用可见的部分）" else ""
            item("已安装应用", runCatching {
                "${pm.getInstalledPackages(0).size} 个$partial"
            }.getOrNull())
            item("系统应用", runCatching {
                val count = pm.getInstalledApplications(0)
                    .count { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
                "$count 个$partial"
            }.getOrNull())
            item("WebView", webViewVersion(context))
            item("Google Play 服务", packageVersion(pm, "com.google.android.gms"))
            item("共享库", runCatching { pm.systemSharedLibraryNames?.size }.getOrNull()?.let { "$it 个" })
        }

        // 车机专有：这台设备到底是不是比亚迪车机、哪一代 DiLink
        val bydProps = SysFs.allProps().filterKeys { it.contains("byd", ignoreCase = true) }
        block("车机") {
            flag(
                "BYDAuto 框架",
                runCatching { Class.forName("android.hardware.bydauto.BYDAutoDeviceManager"); true }
                    .getOrDefault(false),
                yes = "已安装", no = "未检测到",
            )
            itemIfPresent("DiLink 版本", SysFs.firstProp("ro.byd.dilink.version", "ro.build.dilink.version"))
            itemIfPresent("车型代号", SysFs.firstProp("ro.byd.car.type", "ro.byd.vehicle.type", "ro.byd.model"))
            item("BYD 相关属性", "${bydProps.size} 条")
            bydProps.toSortedMap().forEach { (k, v) -> item("  $k", v) }
        }
    }
}

/** 系统特性。`PackageManager` 声明的全部 feature，决定了哪些 API 真的能用。 */
fun collectFeatures(context: Context): HwSection {
    val features = runCatching {
        context.packageManager.systemAvailableFeatures.toList()
    }.getOrNull().orEmpty()
    val named = features.mapNotNull { f -> f.name?.let { it to f.version } }.sortedBy { it.first }
    val unnamed = features.count { it.name == null }

    return section(HwCategory.FEATURE) {
        block {
            item("数量", "${features.size} 项")
            // 没有名字的那几条是 OpenGL ES 版本号，单独算出来更有意义
            if (unnamed > 0) item("无名特性", "$unnamed 项（OpenGL ES 版本声明）")
        }
        block("全部特性") {
            named.forEach { (name, version) ->
                item(name.removePrefix("android.hardware.").removePrefix("android.software."),
                    if (version > 0) "版本 $version" else "支持")
            }
        }
    }
}

/**
 * 系统属性全量。
 *
 * `getprop` 在一台车机上能有一千多条，是排查「这台机子和别人家哪不一样」最直接的材料。
 * 界面上默认折叠，完整内容走导出。
 */
fun collectProps(): HwSection {
    val props = SysFs.allProps()
    return section(HwCategory.PROPS) {
        block {
            item("数量", if (props.isEmpty()) "读取失败（getprop 不可用）" else "${props.size} 条")
        }
        if (props.isEmpty()) return@section
        // 按前缀归档，否则一千条平铺根本找不到东西
        PROP_GROUPS.forEach { (prefix, label) ->
            val group = props.filterKeys { it.startsWith(prefix) }.toSortedMap()
            if (group.isNotEmpty()) {
                block("$label（${group.size} 条）") {
                    group.forEach { (k, v) -> item(k, v) }
                }
            }
        }
        val rest = props.filterKeys { key -> PROP_GROUPS.none { key.startsWith(it.first) } }.toSortedMap()
        if (rest.isNotEmpty()) {
            block("其他（${rest.size} 条）") {
                rest.forEach { (k, v) -> item(k, v) }
            }
        }
    }
}

private val PROP_GROUPS = listOf(
    "ro.product." to "产品",
    "ro.build." to "构建",
    "ro.boot." to "引导",
    "ro.hardware" to "硬件",
    "ro.board." to "主板",
    "ro.vendor." to "厂商分区",
    "ro.config." to "配置",
    "dalvik.vm." to "虚拟机",
    "persist.sys." to "系统持久化",
    "persist." to "其他持久化",
    "sys." to "运行时",
    "init.svc." to "服务状态",
    "net." to "网络",
    "gsm." to "移动网络",
    "audio." to "音频",
    "media." to "多媒体",
    "debug." to "调试",
)

/** 拼几个 JVM 系统属性，缺的跳过（不同 ART 版本给的属性不一样）。 */
private fun sysProps(vararg keys: String): String? =
    keys.mapNotNull { System.getProperty(it) }.joinToString(" ").takeIf { it.isNotBlank() }

@Suppress("DEPRECATION") // 低版本上 Build.SERIAL 仍是唯一能免权限读到序列号的途径
private fun serial(): String? = when {
    // 从安卓 10 起 Build.SERIAL 对普通应用一律返回 UNKNOWN，getSerial() 要 READ_PHONE_STATE。
    // 不为了一行信息去申请那个权限，如实说明读不到的原因
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "受系统限制不可读"
    else -> Build.SERIAL.takeIf { it != Build.UNKNOWN }
}

private fun selinux(): String {
    // sysfs 节点在新安卓上读不到，但 getenforce 这个系统自带命令还能跑
    val raw = SysFs.text("/sys/fs/selinux/enforce")
        ?: SysFs.exec("getenforce")?.trim()
        ?: SysFs.prop("ro.boot.selinux")
    // 只认已知取值。不认识的一律当读不到，免得把命令的报错文字当成状态显示出去
    return when (raw?.lowercase()) {
        "1", "enforcing" -> "强制（Enforcing）"
        "0", "permissive" -> "宽容（Permissive）"
        "disabled" -> "已关闭"
        else -> HwItem.UNKNOWN
    }
}

private fun encryption(): String = when (SysFs.firstProp("ro.crypto.state", "ro.crypto.type")) {
    "encrypted" -> "已加密"
    "unencrypted" -> "未加密"
    "file" -> "文件级加密"
    "block" -> "块级加密"
    else -> SysFs.prop("ro.crypto.type") ?: HwItem.UNKNOWN
}

private fun suPath(): String? = SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }

private val SU_PATHS = listOf(
    "/system/bin/su", "/system/xbin/su", "/sbin/su",
    "/su/bin/su", "/system/sd/xbin/su", "/vendor/bin/su",
)

private fun webViewVersion(context: Context): String? {
    runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()?.let {
        return "${it.packageName} ${it.versionName}"
    }
    return packageVersion(context.packageManager, "com.google.android.webview")
}

private fun packageVersion(pm: PackageManager, pkg: String): String? =
    runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()
