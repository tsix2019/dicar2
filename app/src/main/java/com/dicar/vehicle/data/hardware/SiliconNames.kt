package com.dicar.vehicle.data.hardware

/**
 * 把 `/proc/cpuinfo` 里的数字编号和系统属性里的平台代号翻译成人能看懂的名字。
 *
 * 全是查表。表里没有的一律原样返回十六进制 / 代号本身，**绝不猜**——
 * 一个蒙错的「骁龙 865」比老老实实写 `sm6125` 有害得多。
 */
object SiliconNames {

    /** `CPU implementer`：ARM 官方给各家分的编号。 */
    fun implementer(code: Int?): String? = when (code) {
        null -> null
        0x41 -> "ARM"
        0x42 -> "Broadcom"
        0x43 -> "Cavium"
        0x44 -> "DEC"
        0x46 -> "富士通"
        0x48 -> "海思"
        0x49 -> "Infineon"
        0x4d -> "Motorola / Freescale"
        0x4e -> "NVIDIA"
        0x50 -> "Applied Micro"
        0x51 -> "高通"
        0x53 -> "三星"
        0x56 -> "Marvell"
        0x61 -> "Apple"
        0x66 -> "Faraday"
        0x69 -> "Intel"
        0x6d -> "Microsoft"
        0xc0 -> "Ampere"
        else -> "0x%02x".format(code)
    }

    /**
     * `CPU implementer` + `CPU part` → 核心型号。
     *
     * 同一颗 SoC 里大小核的 part 不同，所以这是区分 big.LITTLE 架构最可靠的办法
     * （比按频率分组准：有些芯片大小核最高频一样）。
     */
    fun core(implementer: Int?, part: Int?): String? {
        if (part == null) return null
        val name = when (implementer) {
            0x41 -> ARM_PARTS[part]
            0x51 -> QUALCOMM_PARTS[part]
            0x48 -> HISILICON_PARTS[part]
            0x4e -> NVIDIA_PARTS[part]
            0x53 -> SAMSUNG_PARTS[part]
            else -> null
        }
        return name ?: "0x%03x".format(part)
    }

    private val ARM_PARTS = mapOf(
        0xc05 to "Cortex-A5", 0xc07 to "Cortex-A7", 0xc08 to "Cortex-A8",
        0xc09 to "Cortex-A9", 0xc0d to "Cortex-A12", 0xc0e to "Cortex-A17",
        0xc0f to "Cortex-A15", 0xc14 to "Cortex-R4", 0xc15 to "Cortex-R5",
        0xd01 to "Cortex-A32", 0xd02 to "Cortex-A34", 0xd03 to "Cortex-A53",
        0xd04 to "Cortex-A35", 0xd05 to "Cortex-A55", 0xd06 to "Cortex-A65",
        0xd07 to "Cortex-A57", 0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73",
        0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76", 0xd0c to "Neoverse-N1",
        0xd0d to "Cortex-A77", 0xd0e to "Cortex-A76AE",
        0xd40 to "Neoverse-V1", 0xd41 to "Cortex-A78", 0xd42 to "Cortex-A78AE",
        0xd44 to "Cortex-X1", 0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710",
        0xd48 to "Cortex-X2", 0xd49 to "Neoverse-N2", 0xd4a to "Neoverse-E1",
        0xd4b to "Cortex-A78C", 0xd4c to "Cortex-X1C", 0xd4d to "Cortex-A715",
        0xd4e to "Cortex-X3", 0xd4f to "Neoverse-V2",
        0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4",
        0xd84 to "Neoverse-V3", 0xd85 to "Cortex-X925", 0xd87 to "Cortex-A725",
        0xd8e to "Neoverse-N3",
    )

    private val QUALCOMM_PARTS = mapOf(
        0x00f to "Scorpion", 0x02d to "Scorpion", 0x04d to "Krait", 0x06f to "Krait",
        0x201 to "Kryo（金）", 0x205 to "Kryo（金）", 0x211 to "Kryo（银）",
        0x800 to "Kryo 2xx 金", 0x801 to "Kryo 2xx 银",
        0x802 to "Kryo 3xx 金", 0x803 to "Kryo 3xx 银",
        0x804 to "Kryo 4xx 金", 0x805 to "Kryo 4xx/5xx 银",
        0xc00 to "Falkor", 0xc01 to "Saphira",
    )

    private val HISILICON_PARTS = mapOf(0xd01 to "TaiShan-v110", 0xd40 to "TaiShan-v120")

    private val NVIDIA_PARTS = mapOf(0x000 to "Denver", 0x003 to "Denver 2", 0x004 to "Carmel")

    private val SAMSUNG_PARTS = mapOf(
        0x001 to "Exynos-M1", 0x002 to "Exynos-M3", 0x003 to "Exynos-M4", 0x004 to "Exynos-M5",
    )

    /**
     * 平台代号（`ro.board.platform` / `ro.hardware`）→ 市售名。
     *
     * 安卓 12 起有 `Build.SOC_MODEL` 可以直接读，但车机多是安卓 10，只能靠这张表。
     * 车规芯片（SA 系列）单独列出来：这个 App 跑的就是车机。
     */
    fun soc(platform: String?): String? {
        val key = platform?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return SOC_NAMES[key]
    }

    private val SOC_NAMES = mapOf(
        // 车规
        "msm8996au" to "骁龙 820A（车规）",
        "sdm660_au" to "骁龙 660A（车规）",
        "sa6155" to "骁龙 SA6155 车规平台",
        "sa6155p" to "骁龙 SA6155P 车规平台",
        "sa8155" to "骁龙 SA8155 车规平台",
        "sa8155p" to "骁龙 8155 车规平台",
        "sa8195p" to "骁龙 8195 车规平台",
        "sa8295p" to "骁龙 8295 车规平台",
        "sa8255p" to "骁龙 8255 车规平台",
        "mt2712" to "联发科 MT2712 车规平台",
        "mt8666" to "联发科 MT8666 车规平台",
        "mt8675" to "联发科 MT8675 车规平台",
        // 手机 SoC：车机上常见的几代
        "msm8953" to "骁龙 625 / 626",
        "msm8937" to "骁龙 430",
        "msm8940" to "骁龙 435",
        "msm8998" to "骁龙 835",
        "sdm630" to "骁龙 630",
        "sdm636" to "骁龙 636",
        "sdm660" to "骁龙 660",
        "sdm670" to "骁龙 670",
        "sdm710" to "骁龙 710",
        "sdm845" to "骁龙 845",
        "sm6115" to "骁龙 662",
        "sm6125" to "骁龙 665",
        "sm6150" to "骁龙 675",
        "sm6225" to "骁龙 680",
        "sm7125" to "骁龙 720G",
        "sm7150" to "骁龙 730",
        "sm8150" to "骁龙 855",
        "sm8250" to "骁龙 865",
        "sm8350" to "骁龙 888",
        "sm8450" to "骁龙 8 Gen 1",
        "sm8550" to "骁龙 8 Gen 2",
        "sm8650" to "骁龙 8 Gen 3",
        "kirin710" to "麒麟 710",
        "kirin980" to "麒麟 980",
        "kirin990" to "麒麟 990",
        "exynos9820" to "Exynos 9820",
        "mt6765" to "联发科 Helio P35",
        "mt6785" to "联发科 Helio G95",
        "mt6889" to "天玑 1000",
        "mt6893" to "天玑 1200",
        "ranchu" to "Android 模拟器（Goldfish/Ranchu）",
        "goldfish" to "Android 模拟器（Goldfish）",
    )

    /** 常见指令集扩展的中文注解，只标注值得一提的，其余原样列出。 */
    fun featureNote(feature: String): String? = when (feature.lowercase()) {
        "neon", "asimd" -> "SIMD 向量"
        "aes" -> "AES 加速"
        "sha1", "sha2", "sha512", "sha3" -> "哈希加速"
        "crc32" -> "CRC32 加速"
        "fphp", "asimdhp" -> "半精度浮点"
        "atomics" -> "原子指令（LSE）"
        "sve", "sve2" -> "可变长向量"
        "dotprod", "asimddp" -> "点积（AI 推理加速）"
        "i8mm" -> "Int8 矩阵乘"
        "bf16", "bti", "mte" -> "ARMv8.5+ 扩展"
        else -> null
    }
}
