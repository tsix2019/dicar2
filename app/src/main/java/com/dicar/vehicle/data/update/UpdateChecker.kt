package com.dicar.vehicle.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /**
     * 是否来自降级通道。接口不可用时只能从网页重定向里拿到版本号，
     * 没有更新说明也没有下载直链——界面要讲清楚这一点，而不是让人以为这版没写说明。
     */
    val viaFallback: Boolean = false,
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
    private val fallbackEndpoint: String = LATEST_RELEASE_PAGE,
) {

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        val release = try {
            ReleaseParser.parse(fetch(endpoint)) ?: return@withContext fallback("解析发布信息失败")
        } catch (e: IOException) {
            return@withContext fallback("网络不可达：${e.message ?: "连接失败"}")
        } catch (e: HttpStatus) {
            return@withContext fallback(e.describe())
        }
        decide(release)
    }

    private fun decide(release: ReleaseInfo): UpdateState =
        if (ReleaseParser.isNewer(release.version, currentVersion)) {
            UpdateState.Available(release, currentVersion)
        } else {
            UpdateState.UpToDate(currentVersion)
        }

    /**
     * 接口走不通时的降级通道：直接请网页版的 `/releases/latest`，它会 302 到
     * `/releases/tag/<版本号>`，从 `Location` 头里就能把版本号抠出来。
     *
     * 这条路**不吃 API 的每小时 60 次匿名配额**，所以限流时它照样能用。
     * 代价是只有版本号，没有更新说明和下载直链。
     */
    private fun fallback(reason: String): UpdateState {
        val tag = runCatching { redirectTag(fallbackEndpoint) }.getOrNull()
            ?: return UpdateState.Failed(reason)
        val version = ReleaseParser.stripPrefix(tag)
        if (!ReleaseParser.isNewer(version, currentVersion)) return UpdateState.UpToDate(currentVersion)
        return UpdateState.Available(
            ReleaseInfo(
                version = version,
                title = "版本 $version",
                notes = "",
                pageUrl = "$RELEASES_PAGE/tag/$tag",
                apkUrl = null,
                publishedOn = null,
                viaFallback = true,
            ),
            currentVersion,
        )
    }

    /** 发一次不跟随重定向的请求，从 `Location` 里取 tag。 */
    private fun redirectTag(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // 关键：不能跟随重定向，否则拿不到带版本号的 Location
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "DiCar/$currentVersion")
        }
        return try {
            ReleaseParser.tagFromReleaseUrl(conn.getHeaderField("Location"))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * HTTP 错误。
     *
     * 403 有好几种成因，不能一律说成「限流」：只有 `X-RateLimit-Remaining` 确实是 0
     * 才是配额用完，其余情况把 GitHub 自己给的理由原样带出来，便于判断到底卡在哪。
     */
    private class HttpStatus(
        val code: Int,
        val rateRemaining: Int?,
        val rateResetEpoch: Long?,
        val serverMessage: String?,
    ) : Exception() {
        fun describe(): String = when {
            rateRemaining == 0 -> "GitHub 匿名接口每小时 $ANON_QUOTA 次已用完${resetHint()}"
            code == 404 -> "仓库里还没有发布任何版本"
            code == 403 || code == 429 -> "GitHub 拒绝了请求（$code${serverMessage?.let { "：$it" }.orEmpty()}）"
            else -> "服务器返回 $code${serverMessage?.let { "：$it" }.orEmpty()}"
        }

        private fun resetHint(): String = rateResetEpoch
            ?.let { "，${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it * 1000))} 后恢复" }
            ?: "，过一会儿再试"
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
            if (code != HttpURLConnection.HTTP_OK) {
                throw HttpStatus(
                    code = code,
                    rateRemaining = conn.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull(),
                    rateResetEpoch = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull(),
                    serverMessage = conn.errorStream
                        ?.let { stream -> runCatching { stream.bufferedReader().use { it.readText() } }.getOrNull() }
                        ?.let { ReleaseParser.errorMessage(it) },
                )
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val REPO = "tsix2019/dicar2"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        /** 网页版同名地址，302 到带版本号的 tag 页，不消耗 API 配额。 */
        const val LATEST_RELEASE_PAGE = "$RELEASES_PAGE/latest"
        /** GitHub 对未登录请求按 IP 计的每小时上限。 */
        const val ANON_QUOTA = 60
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
     * 从 `…/releases/tag/v0.4.0` 这样的地址里取出 tag。
     * 降级通道靠它：网页版 `/releases/latest` 的 302 `Location` 就长这样。
     */
    fun tagFromReleaseUrl(location: String?): String? =
        location?.trim()?.substringAfter("/releases/tag/", "")
            ?.substringBefore('?')?.substringBefore('#')
            ?.takeIf { it.isNotBlank() && '/' !in it }

    /**
     * 从 GitHub 的错误响应里取 `message`。
     *
     * 限流时这句话里带着调用方的公网 IP（「API rate limit exceeded for 1.2.3.4」），
     * 显示前先抹掉——这条信息会出现在截图里，没必要把 IP 一起带出去。
     */
    fun errorMessage(body: String): String? = runCatching {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
        ?.replace(IPV4, "…")
        ?.take(MAX_SERVER_MESSAGE)

    private val IPV4 = Regex("""\b((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)\b""")
    private const val MAX_SERVER_MESSAGE = 120

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
