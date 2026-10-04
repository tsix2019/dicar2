package com.dicar.vehicle.data.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionDslTest {

    @Test
    fun `读不到的值记成未知而不是藏起来`() {
        // 「这项读不到」本身就是信息，尤其是在排查某台车机为什么和别人不一样的时候
        val s = section(HwCategory.SOC) {
            block { item("型号", null as String?); item("厂商", "  ") }
        }
        assertEquals(listOf(HwItem.UNKNOWN, HwItem.UNKNOWN), s.blocks[0].items.map { it.value })
    }

    @Test
    fun `itemIfPresent 读不到就整行不出现`() {
        val s = section(HwCategory.SOC) {
            block {
                item("有值", "x")
                itemIfPresent("没值", null)
                itemIfPresent("空白", "   ")
            }
        }
        assertEquals(listOf("有值"), s.blocks[0].items.map { it.label })
    }

    @Test
    fun `一条都没有的段不会留下空壳`() {
        val s = section(HwCategory.GPU) {
            block("空段") { itemIfPresent("无", null) }
            block("有内容") { item("a", "1") }
        }
        assertEquals(listOf("有内容"), s.blocks.map { it.subtitle })
    }

    @Test
    fun `布尔值翻译成支持与不支持`() {
        val s = section(HwCategory.GPU) {
            block { item("Vulkan", true); item("NFC", false); item("未知项", null as Boolean?) }
        }
        assertEquals(listOf("支持", "不支持", HwItem.UNKNOWN), s.blocks[0].items.map { it.value })
    }

    @Test
    fun `条目总数跨段累加`() {
        val s = section(HwCategory.SOC) {
            block { item("a", "1"); item("b", "2") }
            block("二") { item("c", "3") }
        }
        assertEquals(3, s.itemCount)
    }
}

class FilterSectionsTest {

    private val sections = listOf(
        section(HwCategory.SOC) {
            block("核心分簇") { item("簇 1", "4 核 Cortex-A55") }
            block("指令集") { item("主 ABI", "arm64-v8a") }
        },
        section(HwCategory.PROPS) {
            block("构建（2 条）") {
                item("ro.build.type", "user")
                item("ro.build.version.sdk", "29")
            }
        },
    )

    @Test
    fun `空关键词原样返回`() {
        assertEquals(sections, filterSections(sections, "   "))
    }

    @Test
    fun `标签和值都能命中`() {
        assertEquals(1, filterSections(sections, "abi").size)
        // 值里的 Cortex 也要能搜到
        assertEquals(HwCategory.SOC, filterSections(sections, "cortex").single().category)
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(filterSections(sections, "ARM64"), filterSections(sections, "arm64"))
    }

    @Test
    fun `命中小标题时整段保留`() {
        // 搜「指令集」应该看到整段，而不是只剩标题行
        val hit = filterSections(sections, "指令集").single()
        assertEquals(1, hit.blocks.size)
        assertEquals(listOf("主 ABI"), hit.blocks[0].items.map { it.label })
    }

    @Test
    fun `命中分类名时整个分类保留`() {
        val hit = filterSections(sections, "系统属性").single()
        assertEquals(2, hit.itemCount)
    }

    @Test
    fun `没命中的分类直接丢掉，不留空卡片`() {
        assertTrue(filterSections(sections, "完全不存在的东西").isEmpty())
        assertFalse(filterSections(sections, "ro.build").any { it.blocks.isEmpty() })
    }
}

class RedactorTest {

    @Test
    fun `按字段名命中的整条打码`() {
        assertEquals(Redactor.MASK, Redactor.scrub("序列号", "ABCD1234"))
        assertEquals(Redactor.MASK, Redactor.scrub("ro.serialno", "0x9f2c"))
        assertEquals(Redactor.MASK, Redactor.scrub("IP 地址", "192.168.1.5"))
        assertEquals(Redactor.MASK, Redactor.scrub("ro.boot.btmacaddr", "随便什么"))
    }

    @Test
    fun `字段名正常但值里藏着 MAC 也要抹掉`() {
        // 这是光看字段名会漏掉的情况：某条不起眼的系统属性里带着 MAC
        assertEquals(
            "wlan0 ${Redactor.MASK} up",
            Redactor.scrub("ro.vendor.wifi.info", "wlan0 a4:5e:60:c1:02:ff up"),
        )
    }

    @Test
    fun `值里的内网地址也抹掉`() {
        assertEquals("网关 ${Redactor.MASK}", Redactor.scrub("net.dns1", "网关 10.0.0.138"))
    }

    @Test
    fun `回环和全零地址留着`() {
        // 这两个不是标识，留着才能看出服务监听在哪
        assertEquals("127.0.0.1:5555", Redactor.scrub("adb 监听", "127.0.0.1:5555"))
        assertEquals("0.0.0.0", Redactor.scrub("绑定地址", "0.0.0.0"))
    }

    @Test
    fun `未知值和普通值原样放过`() {
        assertEquals(HwItem.UNKNOWN, Redactor.scrub("序列号", HwItem.UNKNOWN))
        assertEquals("Cortex-A55", Redactor.scrub("核心型号", "Cortex-A55"))
        // 版本号长得像 IP，但段数不对，不该被误伤
        assertEquals("4.14.117", Redactor.scrub("内核版本", "4.14.117"))
    }
}
