package com.AARCNoa.zhiyou.features.core

import com.AARCNoa.zhiyou.BuildConfig
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.cache.DexCacheManager
import com.AARCNoa.zhiyou.dexkit.resolution.resolveAllDex
import com.AARCNoa.zhiyou.features.api.ui.WeSettingsInjector
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.TargetProcesses
import com.AARCNoa.zhiyou.utils.VersionCompat
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.AARCNoa.zhiyou.utils.reflection.DexKit
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    /** 上次弹「加载完成」的 elapsedRealtime，用于节流。 */
    private const val KEY_STARTUP_TOAST_AT = "toast_startup_at"

    fun loadFeatures() {
        // 非主进程（push / appbrand）只启动白名单里的少数功能，且**绝不访问** FeaturesProvider：
        // 那个列表一被读取就会实例化全部功能对象。实测 push 进程因此从 108MB 涨到 307MB，
        // 在这类内存吃紧的机器上会被 LMK 反复杀死，微信连接池随之卡在 CLOSE-WAIT，发消息全红。
        if (!TargetProcesses.isInMain) {
            val items = NonMainFeatures.itemsForCurrentProcess()
            // 白名单功能同样要靠缓存把 hook 目标还原出来，否则 hookBefore 拿到的是占位符，
            // 功能会"启动成功但完全没反应"——这是最难发现的一种失效。
            var loaded = 0
            items.filterIsInstance<IResolveDex>().forEach { item ->
                val name = (item as? BaseFeature)?.name ?: "?"
                val path = runCatching { DexCacheManager.cachePathFor(item) }.getOrElse { "?" }
                val exists = runCatching { java.io.File(path).exists() }.getOrDefault(false)
                val cache = runCatching { DexCacheManager.loadItemCache(item) }.getOrNull()
                if (cache != null) {
                    runCatching { item.loadFromCache(cache) }
                        .onFailure { WeLogger.e(TAG, "loadFromCache failed: $name", it) }
                    loaded++
                }
                // 只在缓存不可用时打一行。正常情况每个进程启动都会刷「exists=true
                // cacheLoaded=true」这种必然成立的内容，属于排查期的脚手架。
                if (!exists || cache == null) {
                    WeLogger.w(
                        TAG,
                        "白名单 $name 的 dex 缓存不可用：exists=$exists cacheLoaded=${cache != null} path=$path",
                    )
                }
            }
            WeLogger.i(TAG, "非主进程：${items.size} 个白名单功能，还原 $loaded 份 dex 描述符")
            NonMainFeatures.startAll()
            return
        }

        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        verifyNonMainRegistry()

        // 一次读盘完成「校验 + 恢复」：以前是 getOutdatedItems（逐项读 JSON 校验）之后再
        // loadDescriptorsFromCache（逐项再读一遍），289 个功能就是 578 次文件读 + JSON 解析，
        // 全部压在启动的主线程上。现在合并成一遍（见 DexCacheManager.loadItemCacheChecked）。
        val allBrokenItems = loadDescriptorsFromCache(allDexItems)
        WeLogger.i(TAG, "dex cache: ${allDexItems.size - allBrokenItems.size}/${allDexItems.size} 项可直接使用")

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        val elapsed = measureTime {
            allFeatures.forEach { feature ->
                val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

                // 当前微信版本已不支持的功能（微信把这部分实现删掉了）：完全不启动，
                // 即便旧配置里开关仍是开着的。unavailableReason 必须是稳定判定（版本号），
                // 不能用 DexKit 解析状态——解析未完成时同样为 true，会误跳过。
                if (feature is SwitchFeature && feature.unavailableReason != null) {
                    // 属于正常分支（该功能就是为本版本准备的），设置页也会展示原因，不再占 I 级。
                    WeLogger.d(TAG, "skipping ${feature.name} — 当前微信版本不支持：${feature.unavailableReason}")
                    return@forEach
                }

                if (isBroken && feature !is WeSettingsInjector) {
                    WeLogger.w(TAG, "skipping ${feature.name} — incomplete cache, awaiting re-resolution")
                    return@forEach
                }

                // 单个 feature 启动失败绝不能中断整轮加载：之前没有隔离，任何一个 feature
                // 在 startup() 里抛异常，排在它后面的功能（含各类 API 服务）就全都不会启动，
                // 表现成「开关是开的但功能没反应」。
                runCatching { feature.startup() }
                    .onFailure { WeLogger.e(TAG, "startup failed for ${feature.displayName}", it) }
            }
        }
        WeLogger.i(TAG, "enabling all hook items took $elapsed")

        logUnresolvedDelegates(allDexItems)

        maybeShowStartupToast()
    }

    /**
     * 「加载完成」提示。只在**用户把微信拉到前台**时弹：
     * 进程被系统回收后由 push 服务拉起时 importance = SERVICE(300)，
     * 这种后台重启不该提示，否则一次内存回收循环就会看成「模块反复重载」。
     * 另外做 60s 节流，极端情况下最多每分钟一条。
     */
    private fun maybeShowStartupToast() {
        if (!TargetProcesses.isInMain) return
        if (!Preferences.showStartupToast) return
        val app = runCatching { HostInfo.application }.getOrNull()
        if (app == null) {
            WeLogger.d(TAG, "startup toast skipped: no application")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val now = SystemClock.elapsedRealtime()
                val last = WePrefs.getLongOrDef(KEY_STARTUP_TOAST_AT, 0L)
                // last > now 说明设备重启过（elapsedRealtime 归零），按首次处理。
                if (last in 1..now && now - last < 60_000L) {
                    WeLogger.d(TAG, "startup toast skipped: shown ${(now - last) / 1000}s ago")
                    return@postDelayed
                }
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val importance = am?.runningAppProcesses
                    ?.firstOrNull { it.pid == Process.myPid() }
                    ?.importance
                if (importance != null &&
                    importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                ) {
                    WeLogger.d(TAG, "startup toast skipped: background start (importance=$importance)")
                    return@postDelayed
                }
                WePrefs.putLong(KEY_STARTUP_TOAST_AT, now)
                WeLogger.d(TAG, "startup toast shown")
                showToast("${BuildConfig.BRAND} 加载成功!")
            }.onFailure { WeLogger.d(TAG, "startup toast skipped: ${it.message}") }
        }, 1_500L)
    }

    // ---------------------------------------------------------------------------

    /**
     * 校验 [NonMainFeatures] 白名单没有漏登记。
     *
     * 任何 `targetProcessMask` 含非主进程位的功能，都必须出现在白名单里；漏了会导致它在
     * push / appbrand 静默不启动——这种失效没有报错，只能靠这里的启动日志发现。
     */
    private fun verifyNonMainRegistry() {
        runCatching {
            val declared = FeaturesProvider.ALL_HOOK_ITEMS
                .filter { item ->
                    // SwitchFeature 用掩码静态声明；ApiFeature 自己判断进程，只能靠显式标记。
                    (item is SwitchFeature && (item.targetProcessMask and TargetProcesses.PROC_MAIN.inv()) != 0) ||
                        item.runsInNonMainProcess
                }
                .map { it.javaClass }
                .toSet()
            val missing = declared - NonMainFeatures.registeredClasses
            if (missing.isNotEmpty()) {
                WeLogger.e(
                    TAG,
                    "非主进程功能未登记白名单：${missing.joinToString { it.simpleName }}；" +
                        "这些功能在 push/appbrand 不会启动，请补进 NonMainFeatures"
                )
            }
        }.onFailure { WeLogger.e(TAG, "非主进程白名单校验失败", it) }
    }

    /**
     * 把「DexKit 没找到的方法」按功能汇总成几条日志。
     *
     * 这些 key 对应的 hook 会在 [BaseFeature.hookBefore] / [BaseFeature.hookAfter] 里被静默跳过，
     * 表现就是「开关是开的但功能没反应」。以前只能把 dex_cache 的 JSON 拉下来逐个比对才能发现，
     * 现在启动时直接打出来，微信改版后一眼就能看出哪些功能需要重新适配。
     */
    private fun logUnresolvedDelegates(items: List<IResolveDex>) {
        val report = items.mapNotNull { item ->
            val missing = item.dexDelegates.filter { it.isPlaceholder }.map { it.key }
            if (missing.isEmpty()) null
            else ((item as? BaseFeature)?.displayName ?: item.javaClass.simpleName) to missing
        }
        if (report.isEmpty()) {
            WeLogger.i(TAG, "DexKit 解析完整：所有 hook 目标均已找到")
            return
        }
        // 已登记 supersededAnchors 的锚点是「旧版路径、新版已有替代实现」；
        // 整个功能被判定为「当前微信版本不支持」的，也不算待适配。
        val superseded = items.sumOf { item ->
            val base = item as? BaseFeature
            val declared = base?.supersededAnchors.orEmpty()
            val wholeDisabled = runCatching { base?.unavailableReason != null }.getOrDefault(false)
            item.dexDelegates.count { it.isPlaceholder && (wholeDisabled || it.key in declared) }
        }
        val total = report.sumOf { it.second.size }
        val realMissing = total - superseded
        // 待适配为 0 时只是「有锚点走了替代路径」的信息，不该计进日志页的告警数；
        // 真的有待适配的锚点才升级为 W。
        val summary = "DexKit 未解析 $total 个方法（已由新路径替代 $superseded 个，" +
            "实际待适配 $realMissing 个），涉及 ${report.size} 个功能"
        if (realMissing > 0) WeLogger.w(TAG, summary) else WeLogger.i(TAG, summary)
        // 明细只在排查时看，降到 debug：否则每次冷启动会固定刷 1 条汇总 + N 条明细的告警。
        report.forEach { (name, keys) ->
            WeLogger.d(TAG, "  · $name：${keys.joinToString("、")}")
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐项读缓存并恢复委托状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载；该 item 记为待修复。
     * - hash 与构建期不一致（resolveDex 实现变了）→ 同样只是记为待修复。
     * - 缓存文件不存在 / 读取失败 → 删除损坏文件，整个 item 记为待修复。
     *
     * @return 需要 DexKit 重新扫描的 item。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val brokenItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val base = item as? BaseFeature
            val path = base?.displayName ?: item.javaClass.simpleName
            val cache = try {
                DexCacheManager.loadItemCacheChecked(item)
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                null
            }

            if (cache == null) {
                WeLogger.w(TAG, "cache missing/corrupt for $path")
                base?.let { runCatching { DexCacheManager.deleteCache(it.name) } }
                brokenItems += item
                continue
            }

            // 即使整体判定为失效，也先把能用的委托恢复出来：WeSettingsInjector 这类
            // 允许带病启动的功能，至少能拿到一部分锚点。
            val missingKeys = item.loadFromCache(cache.values)
            if (!cache.valid || missingKeys.isNotEmpty()) {
                // 两种失效原因要分开说：hash 变了（改了 resolveDex）时 missing 往往是空的，
                // 旧文案会打成「loaded 48/48, missing: []」，看起来像误报。
                val total = item.dexDelegates.size
                WeLogger.w(
                    TAG,
                    "$path: 缓存需重新解析（valid=${cache.valid}, " +
                        "loaded ${total - missingKeys.size}/$total, missing=$missingKeys）"
                )
                brokenItems += item
            }
        }

        return brokenItems
    }

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        // 不再弹「DEX 适配」对话框：这条路本来就是自动修复，弹窗只增加了「点重启」这一步。
        // 改成后台静默修复 + 结束时一条 toast，失败才提示用户看日志。
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val bridge = runCatching { DexKit }.getOrNull()
            if (bridge == null) {
                WeLogger.w(TAG, "DexKit 不可用，跳过后台修复")
                return@launch
            }

            var repaired = 0
            val failed = mutableListOf<String>()
            brokenItems.forEach { item ->
                val name = (item as? BaseFeature)?.displayName ?: item.javaClass.simpleName
                runCatching {
                    item.resolveAllDex(bridge)
                    DexCacheManager.saveItemCache(item)
                }.onSuccess {
                    repaired++
                    WeLogger.i(TAG, "已自动适配: $name")
                }.onFailure {
                    failed += name
                    WeLogger.e(TAG, "自动适配失败: $name", it)
                }
            }

            // 版本兼容性只在「修复完之后仍然解析不到」时才提示。
            //
            // 修复前判会把「微信升级后首次启动」误报成不兼容：那一刻 dex 缓存的 key 全变，
            // 每一项都要重新解析，失效功能数等于功能总数，直接命中最高档
            // 「兼容性较差，N/N 个功能暂未适配」并弹提示 —— 实际几秒后就适配完了。
            val allItems = FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<IResolveDex>()
            val stillBroken = countFeaturesNeedingAdaptation(allItems)
            val compatible = reportCompatibility(stillBroken, allItems.size)

            val message = when {
                !compatible -> "当前微信版本部分功能暂未适配，等待模块更新"
                repaired == 0 && failed.isEmpty() -> null
                failed.isEmpty() -> "已自动适配 $repaired 个功能，重启微信后生效"
                repaired == 0 -> "${failed.size} 个功能适配失败，详见日志"
                else -> "$repaired 个已适配，${failed.size} 个失败（详见日志）"
            }
            if (message != null) {
                withContext(Dispatchers.Main) { runCatching { showToast(message) } }
            }
        }
    }

    /**
     * 还剩几个功能解析不到锚点。
     *
     * 口径与 [logUnresolvedDelegates] 一致：已登记 `supersededAnchors` 的旧锚点、
     * 以及整个功能被判为「当前版本不支持」的，都不算待适配。
     */
    private fun countFeaturesNeedingAdaptation(items: List<IResolveDex>): Int =
        items.count { item ->
            val base = item as? BaseFeature
            val wholeDisabled = runCatching { base?.unavailableReason != null }.getOrDefault(false)
            if (wholeDisabled) {
                false
            } else {
                val declared = base?.supersededAnchors.orEmpty()
                item.dexDelegates.any { it.isPlaceholder && it.key !in declared }
            }
        }

    /** 记录版本兼容性结论；不兼容时返回 false，由调用方决定是否提示用户。 */
    private fun reportCompatibility(failed: Int, total: Int): Boolean = try {
        val (compatible, message) = VersionCompat.checkCompatibility(failed, total)
        if (compatible) WeLogger.i(TAG, "版本兼容性: $message")
        else WeLogger.w(TAG, "版本兼容性警告: $message")
        compatible
    } catch (e: Throwable) {
        WeLogger.e(TAG, "版本兼容性检测异常", e)
        true
    }
}
