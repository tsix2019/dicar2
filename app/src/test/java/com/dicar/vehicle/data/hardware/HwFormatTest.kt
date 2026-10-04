package com.dicar.vehicle.data.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HwFormatTest {

    @Test
    fun `容量按 1024 进位`() {
        assertEquals("512 B", HwFormat.bytes(512))
        assertEquals("1.00 KB", HwFormat.bytes(1024))
        assertEquals("1.00 GB", HwFormat.bytes(1024L * 1024 * 1024))
        assertEquals("7.63 GB", HwFormat.bytes(8_191_000_000))
    }

    @Test
    fun `超过 100 就不要小数了`() {
        // 「763 MB」比「763.42 MB」好读，多出来的两位没有任何意义
        assertEquals("763 MB", HwFormat.bytes(800_000_000))
        assertEquals("99.21 MB", HwFormat.bytes(104_028_000))
    }

    @Test
    fun `读不到和负数都显示未知`() {
        assertEquals(HwItem.UNKNOWN, HwFormat.bytes(null))
        assertEquals(HwItem.UNKNOWN, HwFormat.bytes(-1))
        assertEquals(HwItem.UNKNOWN, HwFormat.kHz(0))
        assertEquals(HwItem.UNKNOWN, HwFormat.percent(null))
    }

    @Test
    fun `频率从 kHz 自动升到 MHz 和 GHz`() {
        assertEquals("1.80 GHz", HwFormat.kHz(1_804_800))
        assertEquals("600 MHz", HwFormat.kHz(600_000))
        assertEquals("500 kHz", HwFormat.kHz(500))
        // sysfs 的 cpufreq 是 kHz，GPU 节点给的是 Hz，两个入口不能搞混
        assertEquals("650 MHz", HwFormat.hz(650_000_000))
    }

    @Test
    fun `网速和频率分开格式化`() {
        assertEquals("150 Mbps", HwFormat.kbps(150_000))
        assertEquals("1.0 Gbps", HwFormat.kbps(1_000_000))
        assertEquals("300 kbps", HwFormat.kbps(300))
    }

    @Test
    fun `开机时长精确到分钟，不足一分钟才给秒`() {
        assertEquals("3 天 4 小时 5 分", HwFormat.duration(273_900_000))
        assertEquals("2 小时 0 分", HwFormat.duration(7_200_000))
        assertEquals("5 分", HwFormat.duration(300_000))
        assertEquals("42 秒", HwFormat.duration(42_000))
    }

    @Test
    fun `已用斜杠总量带百分比`() {
        assertEquals("1.00 GB / 4.00 GB（25%）", HwFormat.usage(1L shl 30, 4L shl 30))
        assertEquals(HwItem.UNKNOWN, HwFormat.usage(100, 0))
        assertEquals(HwItem.UNKNOWN, HwFormat.usage(null, 1024))
    }

    @Test
    fun `电流大于一毫安换成 mA 并保留符号`() {
        // 负号要留着：放电是负、充电是正，抹掉符号就看不出在充还是在放
        assertEquals("-520 mA", HwFormat.microAmp(-520_000))
        assertEquals("1200 mA", HwFormat.microAmp(1_200_000))
        assertEquals("800 µA", HwFormat.microAmp(800))
        assertEquals(HwItem.UNKNOWN, HwFormat.microAmp(Int.MIN_VALUE))
    }

    @Test
    fun `温度和英寸`() {
        assertEquals("48.5 ℃", HwFormat.celsius(48.52f))
        assertEquals("12.30 英寸", HwFormat.inches(12.3f))
        assertEquals(HwItem.UNKNOWN, HwFormat.inches(0f))
    }

    @Test
    fun `标称容量不能按 2 的幂取整`() {
        // 3 GB 的机器 MemTotal 大约 2.8 GB，取 2 的幂会报成 4 GB——
        // 3 / 6 / 12 GB 的机器太多了，必须按常见档位查
        assertEquals("3.00 GB", HwFormat.nominalRam(2_950_000_000))
        assertEquals("6.00 GB", HwFormat.nominalRam(5_800_000_000))
        assertEquals("12.00 GB", HwFormat.nominalRam(11_600_000_000))
        assertEquals("4.00 GB", HwFormat.nominalRam(3_900_000_000))
    }

    @Test
    fun `标称容量刚好等于档位时不往上跳一档`() {
        assertEquals("8.00 GB", HwFormat.nominalRam(8L shl 30))
    }

    @Test
    fun `标称容量超出已知档位就不猜`() {
        assertNull(HwFormat.nominalRam(200L shl 30))
        assertNull(HwFormat.nominalRam(0))
        assertNull(HwFormat.nominalRam(null))
    }

    @Test
    fun `列表过滤空串，全空时显示未知`() {
        assertEquals("ARM、高通", HwFormat.list(listOf("ARM", "", "高通")))
        assertEquals(HwItem.UNKNOWN, HwFormat.list(listOf("", "  ")))
    }
}

class SiliconNamesTest {

    @Test
    fun `按 implementer 加 part 定位核心型号`() {
        assertEquals("Cortex-A55", SiliconNames.core(0x41, 0xd05))
        assertEquals("Cortex-X4", SiliconNames.core(0x41, 0xd82))
        assertEquals("Kryo 4xx 金", SiliconNames.core(0x51, 0x804))
    }

    @Test
    fun `表里没有就原样给十六进制，绝不瞎猜`() {
        assertEquals("0xfff", SiliconNames.core(0x41, 0xfff))
        assertEquals("0x123", SiliconNames.core(0x99, 0x123))
        assertEquals("0x99", SiliconNames.implementer(0x99))
    }

    @Test
    fun `没有 part 就说不出核心`() {
        assertEquals(null, SiliconNames.core(0x41, null))
        assertEquals(null, SiliconNames.implementer(null))
    }

    @Test
    fun `平台代号查市售名，大小写和空白都容忍`() {
        assertEquals("骁龙 665", SiliconNames.soc("sm6125"))
        assertEquals("骁龙 665", SiliconNames.soc(" SM6125 "))
        assertEquals("骁龙 8155 车规平台", SiliconNames.soc("sa8155p"))
    }

    @Test
    fun `查不到返回 null，让调用方退回显示代号本身`() {
        assertEquals(null, SiliconNames.soc("mystery9000"))
        assertEquals(null, SiliconNames.soc(""))
        assertEquals(null, SiliconNames.soc(null))
    }
}
