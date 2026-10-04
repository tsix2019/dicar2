package com.dicar.vehicle.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一个已发布版本。 */
data class ReleaseInfo(
    /** 去掉 `v` 前缀的版本号，如 `0.2.0`。 */
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    /** 发布日期 `yyyy-MM-dd`，取不到为 null。 */
    val publishedOn: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: ReleaseInfo, val current: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 通过 GitHub Releases 接口检查新版本。
 *
 * **这是 App 唯一一处访问外部网络的地方**，且只在用户点「检查更新」或显式打开
 * 「启动时自动检查」时才会发生。请求只是一次匿名 GET，不带任何车辆数据或设备标识；
 * GitHub 那边能看到的仅是你的 IP 和 User-Agent。
 */
class UpdateChecker(
    private val currentVersion: String,
    private val endpoint: String = LATEST_RELEASE_API,
) {

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        val body = try {
            fetch(endpoint)
        } catch (e: IOException) {
            return@withContext UpdateState.Failed("网络不可达：${e.message ?: "连接失败"}")
        } catch (e: HttpStatus) {
            return@withContext UpdateState.Failed(e.describe())
        }

        val release = ReleaseParser.parse(body)
            ?: return@withContext UpdateState.Failed("解析发布信息失败")

        if (ReleaseParser.isNewer(release.version, currentVersion)) {
            UpdateState.Available(release, currentVersion)
        } else {
            UpdateState.UpToDate(currentVersion)
        }
    }

    private class HttpStatus(val code: Int) : Exception() {
        fun describe(): String = when (code) {
            403, 429 -> "GitHub 接口限流了，过一会儿再试"
            404 -> "仓库里还没有发布任何版本"
            else -> "服务器返回 $code"
        }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub 接口要求带 User-Agent，不带会直接 403
            setRequestProperty("User-Agent", "DiCar/$currentVersion")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatus(code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val REPO = "tsix2019/dicar2"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        private const val TIMEOUT_MS = 8000
    }
}

/** 版本比较与 JSON 解析。抽成纯函数便于单测，不碰网络。 */
internal object ReleaseParser {

    fun parse(json: String): ReleaseInfo? = runCatching {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val apk = obj.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
        }
        ReleaseInfo(
            version = stripPrefix(tag),
            title = obj.optString("name").takeIf { it.isNotBlank() } ?: tag,
            notes = toPlainText(obj.optString("body")),
            pageUrl = obj.optString("html_url").takeIf { it.isNotBlank() }
                ?: UpdateChecker.RELEASES_PAGE,
            apkUrl = apk,
            publishedOn = obj.optString("published_at").takeIf { it.length >= 10 }?.substring(0, 10),
        )
    }.getOrNull()

    fun stripPrefix(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    /**
     * 把发布说明里的 Markdown 标记去掉。对话框里就是一个普通 Text，
     * 原样显示的话满屏 `###` 和 `**`，还不如纯文本好读。
     * 表格、分隔线、HTML 片段直接丢掉——它们在纯文本里没有意义。
     */
    fun toPlainText(markdown: String): String = markdown
        .lineSequence()
        .map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("---") || it.startsWith("|") || it.startsWith("<") }
        .map { line ->
            line.trimStart('#', '>', ' ')
                .replace(MD_LINK, "$1")
                .replace(MD_EMPHASIS, "$1")
                .replace("`", "")
                .let { if (it.startsWith("- ")) "· " + it.removePrefix("- ") else it }
        }
        .joinToString("\n")
        .trim()

    private val MD_EMPHASIS = Regex("""\*{1,3}([^*]+)\*{1,3}""")
    private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")

    /**
     * 解析成数字段。预发布后缀（`-beta1`）和构建元数据（`+abc`）一律丢掉，
     * 任何一段不是纯数字就整体返回 null——宁可当作「解析不了」，也不要猜。
     */
    fun parseVersion(raw: String): List<Int>? {
        val core = stripPrefix(raw).substringBefore('-').substringBefore('+')
        if (core.isBlank()) return null
        val parts = core.split('.').map { it.trim().toIntOrNull() }
        if (parts.isEmpty() || parts.any { it == null || it < 0 }) return null
        return parts.filterNotNull()
    }

    /** 缺失的段按 0 补，所以 `1.2` 和 `1.2.0` 相等。 */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** 任一侧解析不出来就返回 false：宁可不提示，也不要误报有新版本。 */
    fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        return compare(l, c) > 0
    }
}
