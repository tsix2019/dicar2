# BYDAuto 设备类全部通过反射从车机 framework 加载，不在 APK 内，无需 keep。
# 这里只需保证反射调用用到的 Kotlin 侧代码不被裁剪（目前没有被反射访问的 App 内类）。

# 保留行号，便于从车机 logcat 定位崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
