package com.AARCNoa.zhiyou.update

import android.os.Build
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一次检查的结果。页面只认这三种，版本号比较只在这个文件里做。 */
sealed interface UpdateResult {
    data class Available(val manifest: UpdateManifest, val asset: UpdateAsset) : UpdateResult
    data object UpToDate : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 更新检查：拉清单 → 比版本 → 选包。
 *
 * 清单放在仓库根、和源码一起提交，三个源读的是同一份文件，只是线路不同：
 * jsDelivr（CDN，最稳）→ gh-proxy + raw（raw 域名国内基本打不开，必须套代理）→
 * GitHub API 的 contents 接口（60 次/小时限流，放最后）。
 *
 * 刻意不解析 Release API 的 tag：我们发版用 `v1.1` 这种两位版本名，
 * 和 versionCode 对不上，从 tag 反推版本的写法在这里必然出错。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** 换仓库只改这两行；[RELEASES_PAGE] 与三个源都由它拼出来。 */
    private const val REPO = "AARCNoa/zhiyou"
    private const val BRANCH = "main"

    /** 所有线路都不通时，唯一还能让用户自己去看的地方。 */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    /** 自动检查的间隔：一天一次。手动检查不受它限制。 */
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 探测时读多少字节。太小测不出慢速线路，太大费流量，64KB 是个折中。 */
    private const val PROBE_BYTES = 64 * 1024

    /**
     * 拿不到清单时给 UI 的一句话。
     *
     * 不写「请检查网络」：清单还没推到仓库时三个源全是 404，这里分不清是断网还是没发布，
     * 写成网络问题会把用户引到错误方向。
     */
    private const val OFFLINE_REASON = "暂时拿不到更新信息，请稍后重试"

    private data class Source(val url: String, val accept: String? = null)

    private val SOURCES = listOf(
        Source("https://fastly.jsdelivr.net/gh/$REPO@$BRANCH/update.json"),
        Source("https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/$BRANCH/update.json"),
        Source(
            url = "https://api.github.com/repos/$REPO/contents/update.json",
            // 不带这个头返回的是 base64 包一层的 JSON，读出来是乱码。
            accept = "application/vnd.github.raw",
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 探测单独一个 client。
     *
     * 清单那条线路慢一点无所谓（一天一次），但探测就是在比谁快，
     * 必须用同一套 3 秒上限，否则「超时」和「很慢」分不开。
     */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── 对外 ────────────────────────────────────────────────────────────────

    /**
     * 进程内缓存最近一次读到的清单。
     *
     * 页面在每次重组时都会问一次「有没有新版本」（首页那行、设置页副标题），
     * 每次都解析一遍 JSON 太浪费；清单只在检查成功时变，由 [save] 一起更新。
     * 两个页面都在微信进程，不存在另一份缓存要同步。
     */
    @Volatile
    private var memo: UpdateManifest? = null

    @Volatile
    private var memoLoaded = false

    /**
     * 上次真正发出请求的时刻（只在内存里）。
     *
     * 主页每次进入都会问一次；[lastCheckAt] 要请求回来才写，所以「刚装上、三个源都不通」
     * 时它一直是 0，会把每次进主页都变成一次 12 秒的探测。有了这个，
     * 失败同样在 24 小时内只试一次。
     */
    @Volatile
    private var attemptAt = 0L

    /**
     * 检查更新。
     *
     * [force] = false 时，24 小时内直接吃缓存，不发请求；[force] = true 用
     * 「设置页手动点」那条路，一定会去拉一次。
     *
     * 失败也写检查时间：否则断网时每次切回主页都会再请求一次三个源。
     */
    suspend fun check(force: Boolean = false): UpdateResult {
        val now = System.currentTimeMillis()
        val cached = cached()
        // 缓存为空也要节流：刚装上、或者三个源都不通时，否则每次进主页都要再撞一遍。
        if (!force && now - maxOf(lastCheckAt(), attemptAt) < CHECK_INTERVAL_MS) {
            return cached?.let { compare(it) } ?: UpdateResult.Failed(OFFLINE_REASON)
        }
        attemptAt = now

        val fetched = fetch()
        WePrefs.putLong(Preferences.UPDATE_CHECKED_AT, now)
        if (fetched == null) {
            // 断网、或清单还没推到仓库，都是正常状态：记 D 级，不让它在启动日志里冒充告警。
            WeLogger.d(TAG, "更新清单获取失败：三个源都不可用")
            // 缓存里已经有更新的版本时继续提示它；否则如实报失败，
            // 不拿旧结果冒充「已是最新」。
            val known = cached?.let { compare(it) }
            return if (known is UpdateResult.Available) known else UpdateResult.Failed(OFFLINE_REASON)
        }
        save(fetched)
        return compare(fetched)
    }

    /** 缓存里的「有新版本」；没有就返回 null。首页那行提示读它，不触发网络。 */
    fun cachedAvailable(): UpdateResult.Available? =
        cached()?.let { compare(it) as? UpdateResult.Available }

    fun lastCheckAt(): Long = runCatching {
        WePrefs.getLongOrDef(Preferences.UPDATE_CHECKED_AT, 0L)
    }.getOrDefault(0L)

    /**
     * 实测哪条线路真能下、谁最快，返回最快的那条；都不行返回 null。
     *
     * 用 64KB 的 Range 请求而不是 HEAD：国内常见「TCP 通、TLS 通、就是拿不到数据」，
     * 只有真读到字节才算可用。几条线路并发探测，按读完时间排序。
     */
    suspend fun pickFastestUrl(urls: List<String>): String? = supervisorScope {
        urls.asSequence()
            .filter { it.toHttpUrlOrNull() != null }
            .distinct()
            .map { url -> async(Dispatchers.IO) { probe(url) } }
            .toList()
            .awaitAll()
            .filterNotNull()
            .minByOrNull { it.second }
            ?.first
    }

    // ── 拉取 ────────────────────────────────────────────────────────────────

    private fun cached(): UpdateManifest? {
        if (memoLoaded) return memo
        val parsed = runCatching {
            val raw = WePrefs.getStringOrDef(Preferences.UPDATE_MANIFEST, "")
            if (raw.isBlank()) null else DefaultJson.decodeFromString<UpdateManifest>(raw)
        }.getOrNull()
        memo = parsed
        memoLoaded = true
        return parsed
    }

    private fun save(manifest: UpdateManifest) {
        memo = manifest
        memoLoaded = true
        WePrefs.putString(Preferences.UPDATE_MANIFEST, DefaultJson.encodeToString(manifest))
    }

    /**
     * 三个源并发跑，取「按 [SOURCES] 顺序第一个成功」的结果。
     *
     * 并发而不是串联：各自 4 秒上限，最坏也就等一个超时；串联在断网时要等 12 秒。
     * 取第一个成功而不是最快成功：顺序本身就是优先级，免得某次 CDN 抖动把请求
     * 拉到第三方代理上。
     */
    private suspend fun fetch(): UpdateManifest? = supervisorScope {
        SOURCES.map { source ->
            async(Dispatchers.IO) {
                runCatching { readManifest(source) }
                    .onFailure { WeLogger.d(TAG, "源不可用：${source.url}", it) }
                    .getOrNull()
            }
        }.awaitAll().firstOrNull { it != null }
    }

    private fun readManifest(source: Source): UpdateManifest? {
        val request = Request.Builder().url(source.url).apply {
            source.accept?.let { header("Accept", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            // 404 / 403 都当这条线路不可用，换下一个源，不往 UI 抛。
            if (!response.isSuccessful) return null
            val manifest = DefaultJson.decodeFromString<UpdateManifest>(response.body.string())
            // 版本号是唯一判断依据，0 或负数说明文件写错了，同样当这条源不可用。
            return manifest.takeIf { it.versionCode > 0 }
        }
    }

    // ── 比较与选包 ──────────────────────────────────────────────────────────

    private fun compare(manifest: UpdateManifest): UpdateResult {
        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return UpdateResult.UpToDate
        val asset = manifest.pickAsset(BuildConfig.FLAVOR_SLUG, Build.SUPPORTED_ABIS.toList())
        if (asset == null) {
            // 发了新版但没有当前入口的包：对这台设备来说就是没有可装的更新。
            WeLogger.w(TAG, "新版本 ${manifest.versionName} 没有 ${BuildConfig.FLAVOR_SLUG} 入口的包")
            return UpdateResult.UpToDate
        }
        return UpdateResult.Available(manifest, asset)
    }

    // ── 线路探测 ────────────────────────────────────────────────────────────

    /** 读 64KB，返回「线路 to 耗时毫秒」；读不到数据就是不可用。 */
    private fun probe(url: String): Pair<String, Long>? = try {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-$PROBE_BYTES")
            .build()
        val started = System.nanoTime()
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                // 用 read 而不是 readByteArray：包比 64KB 大得多，短读是正常的，
                // readByteArray 会在读不满时抛异常，反而把可用线路判死。
                val read = response.body.byteStream().read(ByteArray(PROBE_BYTES))
                if (read <= 0) null else url to (System.nanoTime() - started) / 1_000_000
            }
        }
    } catch (e: Throwable) {
        WeLogger.d(TAG, "线路不可用：$url", e)
        null
    }
}
