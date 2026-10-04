package com.dicar.vehicle.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    // ---------------- 版本号解析 ----------------

    @Test
    fun `去掉 v 前缀`() {
        assertEquals(listOf(0, 2, 0), ReleaseParser.parseVersion("v0.2.0"))
        assertEquals(listOf(0, 2, 0), ReleaseParser.parseVersion("V0.2.0"))
        assertEquals(listOf(0, 2, 0), ReleaseParser.parseVersion(" 0.2.0 "))
    }

    @Test
    fun `丢掉预发布后缀和构建元数据`() {
        assertEquals(listOf(1, 0, 0), ReleaseParser.parseVersion("v1.0.0-beta3"))
        assertEquals(listOf(1, 0, 0), ReleaseParser.parseVersion("1.0.0+20260101"))
    }

    @Test
    fun `非数字段一律视为解析失败`() {
        assertNull(ReleaseParser.parseVersion("nightly"))
        assertNull(ReleaseParser.parseVersion("v1.x.0"))
        assertNull(ReleaseParser.parseVersion(""))
        assertNull(ReleaseParser.parseVersion("v"))
    }

    // ---------------- 比较 ----------------

    @Test
    fun `按段比较而不是按字符串`() {
        // 字符串比较会把 0.10.0 判成比 0.9.0 小，这是最容易踩的坑
        assertTrue(ReleaseParser.isNewer("v0.10.0", "0.9.0"))
        assertTrue(ReleaseParser.isNewer("v0.2.0", "0.1.9"))
        assertTrue(ReleaseParser.isNewer("v1.0.0", "0.99.99"))
    }

    @Test
    fun `同版本和旧版本都不算有更新`() {
        assertFalse(ReleaseParser.isNewer("v0.2.0", "0.2.0"))
        assertFalse(ReleaseParser.isNewer("v0.1.0", "0.2.0"))
    }

    @Test
    fun `缺失的段按 0 补齐`() {
        assertFalse(ReleaseParser.isNewer("v1.2", "1.2.0"))
        assertTrue(ReleaseParser.isNewer("v1.2.1", "1.2"))
    }

    @Test
    fun `解析不了就不提示更新`() {
        // 宁可漏报也不要误报：标签写成 nightly 时不该弹「发现新版本」
        assertFalse(ReleaseParser.isNewer("nightly", "0.2.0"))
        assertFalse(ReleaseParser.isNewer("v9.9.9", "开发版"))
    }

    // ---------------- JSON ----------------

    @Test
    fun `解析 GitHub 发布信息`() {
        val info = ReleaseParser.parse(SAMPLE)!!
        assertEquals("0.2.0", info.version)
        assertEquals("DiCar 车况 v0.2.0 · 3D 车辆模型", info.title)
        assertEquals("2026-10-04", info.publishedOn)
        assertEquals("https://github.com/tsix2019/dicar2/releases/tag/v0.2.0", info.pageUrl)
        assertEquals(
            "https://github.com/tsix2019/dicar2/releases/download/v0.2.0/dicar-0.2.0.apk",
            info.apkUrl,
        )
        assertTrue(info.notes.startsWith("新增可选的 3D 车辆模型"))
    }

    @Test
    fun `附件里没有 apk 时 apkUrl 为空而不是报错`() {
        val info = ReleaseParser.parse("""{"tag_name":"v0.3.0","assets":[]}""")!!
        assertEquals("0.3.0", info.version)
        assertNull(info.apkUrl)
    }

    @Test
    fun `缺 tag_name 或者不是 JSON 都返回 null`() {
        assertNull(ReleaseParser.parse("""{"name":"没有 tag"}"""))
        assertNull(ReleaseParser.parse("<html>502 Bad Gateway</html>"))
        assertNull(ReleaseParser.parse(""))
    }

    private companion object {
        val SAMPLE = """
            {
              "tag_name": "v0.2.0",
              "name": "DiCar 车况 v0.2.0 · 3D 车辆模型",
              "body": "新增可选的 3D 车辆模型，并修正了图标的几何错误。\n\n详见 README。",
              "html_url": "https://github.com/tsix2019/dicar2/releases/tag/v0.2.0",
              "published_at": "2026-10-04T15:06:24Z",
              "assets": [
                { "name": "dicar-0.2.0.apk",
                  "browser_download_url": "https://github.com/tsix2019/dicar2/releases/download/v0.2.0/dicar-0.2.0.apk" }
              ]
            }
        """.trimIndent()
    }
}

class MarkdownPlainTextTest {

    @Test
    fun `去掉标题井号和粗斜体标记`() {
        val out = ReleaseParser.toPlainText("### 新增：3D 车辆模型\n\n**默认关**，可以*随时*打开。")
        assertEquals("新增：3D 车辆模型\n默认关，可以随时打开。", out)
    }

    @Test
    fun `链接只留文字`() {
        assertEquals(
            "详见 README。",
            ReleaseParser.toPlainText("详见 [README](https://github.com/tsix2019/dicar2)。"),
        )
    }

    @Test
    fun `列表项换成圆点`() {
        assertEquals("· 第一条\n· 第二条", ReleaseParser.toPlainText("- 第一条\n- 第二条"))
    }

    @Test
    fun `表格分隔线和 HTML 整行丢掉`() {
        val md = """
            升级说明
            
            | 项目 | 值 |
            |---|---|
            | SHA-256 | abc |
            
            ---
            <p align="center">图</p>
            结束
        """.trimIndent()
        assertEquals("升级说明\n结束", ReleaseParser.toPlainText(md))
    }

    @Test
    fun `反引号里的命令保留内容`() {
        assertEquals("先跑 adb install -r 再说", ReleaseParser.toPlainText("先跑 `adb install -r` 再说"))
    }
}
