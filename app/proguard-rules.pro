# BYDAuto 设备类全部通过反射从车机 framework 加载，不在 APK 内，无需 keep。

# 辅助进程 HelperMain 是通过 app_process 按类名启动的（shell 字符串引用，R8 看不到），
# 必须完整保留，否则 release 包会把它裁剪/改名导致 app_process 找不到入口。
-keep class com.dicar.vehicle.helper.** { *; }

# 保留行号，便于从车机 logcat 定位崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# dadb 依赖 okio；okio 的可选 nio 入口在 Android 上不存在，忽略告警
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
