package com.AARCNoa.zhiyou.dexkit.cache

import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.features.core.BaseFeature
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.fs.createDirsSafe
import org.json.JSONObject
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Dex 缓存管理器
 * 负责管理 Dex 查找结果的缓存，支持版本控制和增量更新。
 *
 * 缓存的 key → value 由各 [com.AARCNoa.zhiyou.dexkit.dsl.BaseDexDelegate] 直接提供
 */
object DexCacheManager {

    private const val TAG = "DexCacheManager"

    private const val CACHE_DIR_NAME = "dex_cache"
    private const val CACHE_FILE_SUFFIX = ".json"
    private const val KEY_HOST_VERSION = "host_version"

    private val cacheDir: Path by lazy {
        (KnownPaths.moduleData / CACHE_DIR_NAME).createDirsSafe()
    }

    fun init(currentVer: String) {
        val cachedVer = WePrefs.getString(KEY_HOST_VERSION)
        if (cachedVer != currentVer) {
            WeLogger.i(TAG, "host version changed: $cachedVer -> $currentVer, resetting all cache")
            clearAllCache()
            Preferences.noDexResolve = false
            WeLogger.i(TAG, "disabling NO_DEX_RESOLVE due to host version change")
        }

        WePrefs.putString(KEY_HOST_VERSION, currentVer)
    }

    /**
     * 一次读完并校验某个功能的缓存。
     *
     * 过去「校验」与「加载」各读一次文件、各解析一次 JSON，
     * 289 个功能就是 578 次文件读 + 578 次 JSON 解析，而且都发生在主线程的启动路径上。
     * 这里合并成一遍：能读到的 key 全部返回，[CacheLoadResult.valid] 表示整项可直接启用。
     *
     * @return null 表示文件不存在或读取/解析失败（调用方按「需重新解析」处理）。
     */
    fun loadItemCacheChecked(item: IResolveDex): CacheLoadResult? {
        val cacheFile = getCacheFile(cacheFileName(item))
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            val values = buildMap {
                for (key in json.keys()) {
                    if (key !in META_KEYS) put(key, json.get(key))
                }
            }

            // hash 缺失（生成器漏注册等）时跳过比对：降级为仅按 key 完整性校验，
            // 绝不因 hash 异常抛错崩微信，也不进入修复循环。
            val hashOk = calculateMethodHash(item)
                ?.let { it == json.optString("methodHash", "") }
                ?: true
            var keysOk = true
            for (delegate in item.dexDelegates) {
                val value = values[delegate.key] as? String
                if (value.isNullOrEmpty() || value == "null") {
                    keysOk = false
                    break
                }
            }

            CacheLoadResult(values, hashOk && keysOk)
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read cache for: ${(item as? BaseFeature)?.displayName ?: item}", e)
            null
        }
    }

    /** [loadItemCacheChecked] 的结果：可加载的 key→value，以及是否完整有效。 */
    data class CacheLoadResult(val values: Map<String, Any>, val valid: Boolean)

    /**
     * 将 [item] 所有委托的当前描述符持久化到缓存文件。
     * 数据来自 [IResolveDex.collectDescriptors]。
     */
    fun saveItemCache(item: IResolveDex) {
        val cacheFile = getCacheFile(cacheFileName(item))
        try {
            val json = JSONObject()
            // hash 缺失时跳过该字段而不是抛错：委托描述符照常持久化，
            // 下次启动按 key 完整性校验（见 loadItemCacheChecked），item 可正常启用。
            calculateMethodHash(item)?.let { json.put("methodHash", it) }
            json.put("timestamp", System.currentTimeMillis())

            item.collectDescriptors().forEach { (key, value) ->
                json.put(key, value)
            }

            cacheFile.writeText(json.toString(2))
            WeLogger.d(TAG, "cache saved for: ${item.cacheLabel()}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to save cache for: ${item.cacheLabel()}", e)
        }
    }

    /**
     * 从缓存文件加载原始 Map（不包含元数据 key）。
     * 由 [IResolveDex.loadFromCache] 消费，后者负责逐委托分发。
     */
    fun loadItemCache(item: IResolveDex): Map<String, Any>? {
        val cacheFile = getCacheFile(cacheFileName(item))
        if (!cacheFile.exists()) return null

        return try {
            val json = JSONObject(cacheFile.readText())
            buildMap {
                for (key in json.keys()) {
                    if (key !in META_KEYS) put(key, json.get(key))
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to load cache for: ${item.cacheLabel()}", e)
            null
        }
    }

    fun deleteCache(itemName: String) {
        getCacheFile(itemName).deleteIfExists()
    }

    fun clearAllCache() {
        cacheDir.listDirectoryEntries().forEach { path ->
            path.deleteIfExists()
        }
        WeLogger.i(TAG, "all cache cleared")
    }

    /**
     * 日志用的简短标识。
     *
     * 以前靠 `if (item !is BaseFeature) error(...)` 换来的智能转换直接读 `displayName`；
     * 那个守卫是多余的（`cacheFileName()` 已经保证同一件事），去掉后改在这里安全取名，
     * 非 BaseFeature 时也不会抛。
     */
    private fun IResolveDex.cacheLabel(): String =
        (this as? BaseFeature)?.displayName ?: javaClass.simpleName

    // ---------------------------------------------------------------------------

    private val META_KEYS = setOf("methodHash", "timestamp")

    private fun cacheFileName(item: IResolveDex): String =
        (item as? BaseFeature)?.name ?: error("item is not BaseFeature")

    private fun getCacheFile(path: String): Path =
        cacheDir / (path.replace("/", "_") + CACHE_FILE_SUFFIX)

    /** 仅用于诊断：把某个 item 的缓存绝对路径暴露出来（读不到时用于定位）。 */
    fun cachePathFor(item: IResolveDex): String =
        getCacheFile(cacheFileName(item)).toString()

    /**
     * 获取 resolveDex 方法编译时生成的哈希，用于检测实现变化。
     * 返回 null 表示编译期未注册该 item 的 hash（构建期生成器漏注册）：
     * 调用方必须降级处理（跳过 hash 比对），绝不允许因此抛异常崩微信。
     */
    private fun calculateMethodHash(item: IResolveDex): String? {
        val className = item.javaClass.name
        val hash = GeneratedMethodHashes.HASHES[className]
        if (hash.isNullOrBlank()) {
            // 生成器漏注册是「可降级」情况（缓存照常落盘，只是不再按 hash 失效），
            // 按日志规范不该占 E：E 留给真故障，否则真正的错误会被这类已知降级淹没。
            WeLogger.w(
                TAG,
                "no method hash registered for item $className; " +
                        "hash-based invalidation disabled for it, falling back to key-completeness check only"
            )
            return null
        }
        return hash
    }
}
