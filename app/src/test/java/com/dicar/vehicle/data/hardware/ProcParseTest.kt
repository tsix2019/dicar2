package com.dicar.vehicle.data.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuInfoParseTest {

    @Test
    fun `读出平台、核心身份和指令集`() {
        val id = ProcParse.cpuIdentity(ARM64)
        assertEquals("Qualcomm Technologies, Inc SM6125", id.hardware)
        assertEquals(0x51, id.implementer)
        assertEquals(0x805, id.part)
        assertEquals(0xd, id.variant)
        assertEquals(14, id.revision)
        assertEquals(8, id.processorCount)
        assertTrue("aes" in id.features)
        assertTrue("asimddp" in id.features)
    }

    @Test
    fun `同名字段重复出现时取第一次`() {
        // 每个核心都会重复一遍 CPU implementer，不能被最后一个覆盖掉
        val id = ProcParse.cpuIdentity(ARM64)
        assertEquals(0x51, id.implementer)
    }

    @Test
    fun `逐核解析能分出大小核`() {
        val cores = ProcParse.cpuCores(ARM64)
        assertEquals(8, cores.size)
        // 前 6 个是 0x805（小核），后 2 个是 0x804（大核）
        assertEquals(List(6) { 0x805 } + listOf(0x804, 0x804), cores.map { it.part })
        assertEquals((0..7).toList(), cores.map { it.index })
    }

    @Test
    fun `x86 模拟器上退回 model name`() {
        val id = ProcParse.cpuIdentity(X86)
        assertEquals("Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz", id.modelName)
        assertNull(id.implementer)
        assertEquals(4, id.processorCount)
        // x86 的指令集在 flags 行里，不是 Features
        assertTrue("sse4_2" in id.features)
    }

    @Test
    fun `空输入和垃圾输入都不炸`() {
        val id = ProcParse.cpuIdentity("")
        assertEquals(0, id.processorCount)
        assertNull(id.hardware)
        assertTrue(ProcParse.cpuCores("<html>403</html>").isEmpty())
    }

    private companion object {
        val ARM64 = buildString {
            repeat(8) { i ->
                appendLine("processor\t: $i")
                appendLine("BogoMIPS\t: 38.40")
                appendLine(
                    "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics " +
                        "fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
                )
                appendLine("CPU implementer\t: 0x51")
                appendLine("CPU architecture: 8")
                appendLine("CPU variant\t: 0xd")
                appendLine("CPU part\t: ${if (i < 6) "0x805" else "0x804"}")
                appendLine("CPU revision\t: 14")
                appendLine()
            }
            appendLine("Hardware\t: Qualcomm Technologies, Inc SM6125")
        }

        val X86 = buildString {
            repeat(4) { i ->
                appendLine("processor\t: $i")
                appendLine("model name\t: Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz")
                appendLine("flags\t\t: fpu vme de pse tsc msr pae sse4_1 sse4_2 avx2")
                appendLine()
            }
        }
    }
}

class MemInfoParseTest {

    @Test
    fun `kB 统一换算成字节`() {
        val mem = ProcParse.memInfo(SAMPLE)
        assertEquals(3_845_632L * 1024, mem["MemTotal"])
        assertEquals(1_500_000L * 1024, mem["MemAvailable"])
    }

    @Test
    fun `没有单位的项原样保留`() {
        // HugePages_Total 是个计数，不是容量，不能乘 1024
        assertEquals(0L, ProcParse.memInfo(SAMPLE)["HugePages_Total"])
    }

    @Test
    fun `残缺的行直接跳过`() {
        val mem = ProcParse.memInfo("MemTotal:\nGarbage\nMemFree:   100 kB")
        assertEquals(1, mem.size)
        assertEquals(100L * 1024, mem["MemFree"])
    }

    private companion object {
        val SAMPLE = """
            MemTotal:        3845632 kB
            MemFree:          123456 kB
            MemAvailable:    1500000 kB
            Cached:           900000 kB
            SwapTotal:       1922816 kB
            HugePages_Total:       0
        """.trimIndent()
    }
}

class CpuStatParseTest {

    @Test
    fun `汇总行和各核都能取到`() {
        val times = ProcParse.cpuTimes(BEFORE)
        assertEquals(setOf("cpu", "cpu0", "cpu1"), times.keys)
        // idle 要把 iowait 算进去：等 IO 的时间 CPU 同样没在干活
        assertEquals(100_200L, times["cpu"]?.idle)
    }

    @Test
    fun `占用率按两次采样的差值算`() {
        val a = ProcParse.cpuTimes(BEFORE)
        val b = ProcParse.cpuTimes(AFTER)
        // cpu0：总量 +200，其中 idle +150，忙 50 → 25%
        assertEquals(25f, ProcParse.load(a["cpu0"], b["cpu0"])!!, 0.01f)
    }

    @Test
    fun `没有前一帧或者两帧之间没动静时返回 null 而不是 0`() {
        // 「测不出来」和「空闲」是两回事，画成 0% 会骗人
        val a = ProcParse.cpuTimes(BEFORE)
        assertNull(ProcParse.load(null, a["cpu0"]))
        assertNull(ProcParse.load(a["cpu0"], a["cpu0"]))
    }

    @Test
    fun `离线的核心不出现在结果里`() {
        // /proc/stat 里离线核心整行消失，不能按下标当核心编号
        val times = ProcParse.cpuTimes("cpu  1 1 1 1\ncpu3 1 1 1 1")
        assertEquals(setOf("cpu", "cpu3"), times.keys)
    }

    private companion object {
        val BEFORE = """
            cpu  1000 50 500 100000 200 0 30 0 0 0
            cpu0 300 10 200 25000 50 0 10 0 0 0
            cpu1 200 10 100 25000 50 0 10 0 0 0
            intr 12345
        """.trimIndent()

        val AFTER = """
            cpu  1100 50 550 100150 200 0 30 0 0 0
            cpu0 330 10 220 25140 60 0 10 0 0 0
            cpu1 200 10 100 25200 50 0 10 0 0 0
        """.trimIndent()
    }
}

class SelfStatParseTest {

    @Test
    fun `取出 utime 加 stime`() {
        assertEquals(165L, ProcParse.selfCpuTicks(normal))
    }

    @Test
    fun `进程名里有空格和括号也要数对字段`() {
        // 第二个字段带括号且内容任意，按空格硬切会整体错位——这是解析这个文件最容易栽的地方
        assertEquals(165L, ProcParse.selfCpuTicks(weirdName))
    }

    @Test
    fun `残缺输入返回 null`() {
        assertNull(ProcParse.selfCpuTicks(""))
        assertNull(ProcParse.selfCpuTicks("1234 (app) S 1 2 3"))
        assertNull(ProcParse.selfCpuTicks("没有括号的垃圾"))
    }

    private val tail = "S 1 1234 0 0 -1 4194624 5000 0 10 0 120 45 0 0 20 0 15 0 0"
    private val normal = "1234 (com.dicar.vehicle) $tail"
    private val weirdName = "1234 (weird (name) here) $tail"
}

class MiscProcParseTest {

    @Test
    fun `解析挂载点`() {
        val mounts = ProcParse.mounts(
            "/dev/block/dm-4 /system ext4 ro,seclabel,relatime 0 0\n" +
                "/dev/block/sda31 /data f2fs rw,lazytime 0 0"
        )
        assertEquals(2, mounts.size)
        assertEquals("/data", mounts[1].point)
        assertEquals("f2fs", mounts[1].fsType)
        assertTrue("ro" in mounts[0].options)
    }

    @Test
    fun `取内核版本号`() {
        assertEquals(
            "4.14.117-perf+",
            ProcParse.kernelVersion("Linux version 4.14.117-perf+ (builder@host) (clang 9.0) #1 SMP"),
        )
        assertNull(ProcParse.kernelVersion("乱七八糟"))
    }
}
