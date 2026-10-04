package com.dicar.vehicle.data.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/** 内存。容量、分区、虚拟机堆上限。 */
fun collectMemory(context: Context): HwSection {
    val mem = SysFs.text("/proc/meminfo")?.let { ProcParse.memInfo(it) } ?: emptyMap()
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = am?.let { ActivityManager.MemoryInfo().also { m -> it.getMemoryInfo(m) } }

    return section(HwCategory.MEMORY) {
        block {
            item("总容量", HwFormat.bytes(info?.totalMem ?: mem["MemTotal"]))
            // 标称容量：内核会扣掉自己和保留区，所以 MemTotal 总是比包装盒上的小。
            // 往上取到最近的 2 的幂，才是用户心里那个「8G 内存」
            item("标称容量", HwFormat.nominalRam(info?.totalMem ?: mem["MemTotal"]))
            itemIfPresent("内存类型", SysFs.firstProp("ro.boot.ddr_type", "ro.boot.hardware.ddr"))
            flag("低内存设备", am?.isLowRamDevice)
            item("内存紧张阈值", HwFormat.bytes(info?.threshold))
        }

        block("明细（/proc/meminfo）") {
            // 这几项解释了「可用」和「空闲」为什么差那么多：缓存随时可以回收
            MEMINFO_KEYS.forEach { (key, label) ->
                itemIfPresent(label, mem[key]?.let { HwFormat.bytes(it) })
            }
        }

        block("交换与 ZRAM") {
            item("Swap 总量", HwFormat.bytes(mem["SwapTotal"]))
            item("Swap 空闲", HwFormat.bytes(mem["SwapFree"]))
            val zramDisks = SysFs.children("/sys/block") { it.name.startsWith("zram") }
            item("ZRAM 设备", if (zramDisks.isEmpty()) "无" else "${zramDisks.size} 个")
            zramDisks.forEach { d ->
                itemIfPresent(
                    "${d.name} 压缩算法",
                    SysFs.text("${d.absolutePath}/comp_algorithm")
                        // 当前算法用方括号标出来，其余是可选项
                        ?.let { Regex("\\[(\\w+)]").find(it)?.groupValues?.get(1) ?: it },
                )
                itemIfPresent("${d.name} 容量", SysFs.long("${d.absolutePath}/disksize")?.let { HwFormat.bytes(it) })
            }
        }

        block("应用可用堆") {
            // 这两个数决定了本 App 自己能用多少内存，OOM 的时候先看这里
            item("标准堆上限", am?.memoryClass?.let { "$it MB" })
            item("大堆上限", am?.largeMemoryClass?.let { "$it MB" })
            item("当前进程堆上限", HwFormat.bytes(Runtime.getRuntime().maxMemory()))
            item("Dalvik 堆配置", SysFs.prop("dalvik.vm.heapsize"))
            item("Dalvik 增长上限", SysFs.prop("dalvik.vm.heapgrowthlimit"))
            item("Dalvik 起始堆", SysFs.prop("dalvik.vm.heapstartsize"))
        }
    }
}

/** 存储。各分区容量、介质型号。 */
fun collectStorage(context: Context): HwSection {
    val mounts = SysFs.text("/proc/mounts")?.let { ProcParse.mounts(it) }.orEmpty()

    return section(HwCategory.STORAGE) {
        block("容量") {
            // 这是用户在「设置 → 存储」里看到的那个数
            volume("内部存储", Environment.getDataDirectory())
            volume("系统分区", Environment.getRootDirectory())
            context.getExternalFilesDir(null)?.let { volume("应用外部目录", it) }
            volume("缓存分区", context.cacheDir)
        }

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volumes = runCatching { sm?.storageVolumes }.getOrNull().orEmpty()
        if (volumes.isNotEmpty()) {
            block("存储卷") {
                volumes.forEachIndexed { i, v ->
                    val name = runCatching { v.getDescription(context) }.getOrNull() ?: "卷 ${i + 1}"
                    item(
                        name,
                        buildList {
                            add(if (v.isRemovable) "可移除" else "内置")
                            if (v.isPrimary) add("主卷")
                            if (v.isEmulated) add("模拟")
                            add(runCatching { v.state }.getOrNull() ?: HwItem.UNKNOWN)
                        }.joinToString(" · "),
                    )
                }
            }
        }

        // 物理介质：UFS 走 /sys/block/sd*，eMMC 走 mmcblk*
        val blocks = SysFs.children("/sys/block") { BLOCK_DEVICE.matches(it.name) }
        if (blocks.isNotEmpty()) {
            block("物理介质") {
                blocks.forEach { dev ->
                    val base = "${dev.absolutePath}/device"
                    val model = SysFs.text("$base/model") ?: SysFs.text("$base/name")
                    val vendor = SysFs.text("$base/vendor") ?: SysFs.text("$base/manfid")
                    // size 的单位是 512 字节扇区，不是字节——这是最容易看错的一个节点
                    val sizeBytes = SysFs.long("${dev.absolutePath}/size")?.times(512)
                    item(
                        dev.name,
                        listOfNotNull(
                            model?.trim(),
                            vendor?.trim()?.takeIf { it != model?.trim() },
                            SysFs.text("$base/rev")?.trim()?.let { "固件 $it" },
                            sizeBytes?.let { HwFormat.bytes(it) },
                        ).joinToString(" · ").ifEmpty { HwItem.UNKNOWN },
                    )
                }
            }
        }

        // /data_mirror、/data/user、各应用私有目录都是 /data 的 bind mount，
    // 列出来只有噪声，真正有信息量的是 /、/vendor、/data 这些真实分区
    val interesting = mounts.filter { m ->
        m.fsType in INTERESTING_FS && NOISY_MOUNTS.none { m.point.startsWith(it) }
    }
        if (interesting.isNotEmpty()) {
            block("已挂载文件系统") {
                item("挂载点总数", "${mounts.size} 个")
                interesting.distinctBy { it.point }.forEach { m ->
                    item(m.point, "${m.fsType}（${if ("ro" in m.options) "只读" else "可写"}）")
                }
            }
        }
    }
}

private fun BlockScope.volume(label: String, dir: File) {
    val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
    if (stat == null) {
        item(label, null as String?)
        return
    }
    val total = stat.blockCountLong * stat.blockSizeLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    item(label, HwFormat.usage(total - free, total))
}

private val BLOCK_DEVICE = Regex("(sd[a-z]|mmcblk\\d+|nvme\\d+n\\d+)")

private val INTERESTING_FS = setOf("ext4", "f2fs", "erofs", "vfat", "exfat", "ntfs", "squashfs", "ubifs")

private val NOISY_MOUNTS = listOf(
    "/apex", "/data_mirror", "/data/user", "/data/data", "/data/misc", "/mnt/pass_through",
)

private val MEMINFO_KEYS = listOf(
    "MemTotal" to "MemTotal（内核可见总量）",
    "MemFree" to "MemFree（完全空闲）",
    "MemAvailable" to "MemAvailable（实际可用）",
    "Buffers" to "Buffers（块设备缓冲）",
    "Cached" to "Cached（页缓存）",
    "Active" to "Active（活跃页）",
    "Inactive" to "Inactive（可回收页）",
    "Dirty" to "Dirty（待写回）",
    "Shmem" to "Shmem（共享内存）",
    "Slab" to "Slab（内核对象）",
    "KernelStack" to "KernelStack（内核栈）",
    "PageTables" to "PageTables（页表）",
    "VmallocTotal" to "VmallocTotal（内核虚拟地址空间）",
    "CmaTotal" to "CmaTotal（连续内存预留）",
)
