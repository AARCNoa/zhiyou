package com.AARCNoa.zhiyou.utils

import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.preferences.WePrefs
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * 「激活框架」信息：模块是被哪个 Xposed 框架、以哪套 API 加载的。
 *
 * 以前这套信息分两个来源写缓存：
 * - 环境名（LSPosed / LSPatch）只在**模块 App 进程**里探测后写盘，微信进程里的设置页
 *   经常读到空值，于是显示「未知」；
 * - API 版本只在 libxposed 入口（standard flavor）的 `onModuleLoaded` 里写，
 *   而它执行时 MMKV 还没初始化，写入必然失败。
 *
 * 现在统一成一份缓存 [Preferences.CACHED_FRAMEWORK]，由**真正加载过模块的进程**写入：
 * - 微信进程：`/proc/self/maps` 找框架 native 库 → 类探测 → 调用栈兜底，拿到品牌，
 *   再取 [XposedBridge] 的 API 级别；
 * - 模块 App 进程：libxposed 服务绑定成功后，用框架自报的名字/版本覆盖（更准）。
 *
 * UI 只读 [display]，读不到就地探一次，不会再有「未知」以外的空白。
 */
object HostFramework {

    private const val TAG = "HostFramework"
    private const val UNKNOWN = "未知"

    /** `/proc/self/maps` 里出现即说明该框架的 native 部分注入进了本进程。 */
    private val BRAND_BY_LIB = listOf(
        "zygisk_lsposed" to "LSPosed",
        "lspatch" to "LSPatch",
        "edxposed" to "EdXposed",
        "taichi" to "太极",
    )

    /** 框架实现类：类能加载到，品牌就确定（比字符串匹配更硬）。 */
    private val BRAND_BY_CLASS = listOf(
        "org.lsposed.lspd.core.Main" to "LSPosed",
        "org.lsposed.lspd.impl.LSPosedBridge" to "LSPosed",
        "org.lspatch.Loader" to "LSPatch",
        "org.lspatch.runtime.LSPatchProvider" to "LSPatch",
        "org.meowcat.edxposed.core.Main" to "EdXposed",
    )

    /**
     * 模块刚在**微信进程**启动完（MMKV 已就绪）时调用一次。
     *
     * @param entryName 入口实现类名，用来区分经典 Xposed API 与 LibXposed API
     * @param entryApi  LibXposed 入口自报的 API 级别，经典入口传 0
     */
    fun recordAtLoad(entryName: String, entryApi: Int = 0) {
        runCatching {
            val text = buildString {
                append(brand() ?: UNKNOWN)
                append(" · ")
                // 两条入口上报的都是框架自己的 API 级别（LSPosed 兼容层为 102），
                // 不再额外区分「Xposed / LibXposed」前缀，避免出现「Xposed API 102」这种误解。
                val api = if (entryName.contains("Lsp10x") && entryApi > 0) entryApi else xposedApiLevel()
                if (api > 0) append("API ").append(api) else append("Xposed")
            }
            val cached = WePrefs.getStringOrDef(Preferences.CACHED_FRAMEWORK, "")
            if (cached != text) {
                WePrefs.putString(Preferences.CACHED_FRAMEWORK, text)
            }
            // 每个进程只留 WeLauncher 那条启动横幅，这里降到 D（logcat 仍可见）。
            WeLogger.d(TAG, "激活框架：$text")
        }.onFailure { WeLogger.w(TAG, "识别激活框架失败", it) }
    }

    /**
     * 模块 App 进程里 libxposed 服务绑定成功时调用：框架自报的名字/版本最权威。
     * 只在拿到有效名字时覆盖，避免把微信进程刚写好的结果盖回空值。
     */
    fun recordFromService(name: String?, version: String?, apiVersion: Int) {
        val framework = listOfNotNull(
            name?.takeIf { it.isNotBlank() },
            version?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        if (framework.isBlank()) return
        val text = if (apiVersion > 0) "$framework · API $apiVersion" else framework
        runCatching {
            if (WePrefs.getStringOrDef(Preferences.CACHED_FRAMEWORK, "") != text) {
                WePrefs.putString(Preferences.CACHED_FRAMEWORK, text)
            }
        }
    }

    /** UI 读取：优先跨进程缓存，没有就当场探一次。 */
    fun display(): String = runCatching {
        val cached = WePrefs.getStringOrDef(Preferences.CACHED_FRAMEWORK, "")
        if (cached.isNotBlank()) cached else recordless()
    }.getOrDefault(UNKNOWN)

    private fun recordless(): String {
        val found = brand()
        val api = xposedApiLevel()
        return when {
            found != null && api > 0 -> "$found · API $api"
            found != null -> found
            api > 0 -> "API $api"
            else -> UNKNOWN
        }
    }

    // ------------------------------------------------------------------ 探测

    private fun brand(): String? = brandFromMaps() ?: brandFromClasses() ?: brandFromStack()

    private fun brandFromMaps(): String? = runCatching {
        File("/proc/self/maps").useLines { lines ->
            lines.firstNotNullOfOrNull { line ->
                val lower = line.lowercase()
                BRAND_BY_LIB.firstOrNull { lower.contains(it.first) }?.second
            }
        }
    }.getOrNull()

    private fun brandFromClasses(): String? {
        val loaders = listOfNotNull(
            HostFramework::class.java.classLoader,
            runCatching { HostInfo.application.classLoader }.getOrNull(),
        )
        for ((className, brand) in BRAND_BY_CLASS) {
            for (loader in loaders) {
                if (runCatching { Class.forName(className, false, loader) }.isSuccess) return brand
            }
        }
        return null
    }

    /** 兜底：框架分派 hook 回调时，调用栈上一定有它自己的类。 */
    private fun brandFromStack(): String? {
        for (frame in Throwable().stackTrace) {
            val name = frame.className
            when {
                name.startsWith("org.lsposed.") -> return "LSPosed"
                name.startsWith("org.lspatch.") -> return "LSPatch"
                name.startsWith("org.meowcat.edxposed.") -> return "EdXposed"
                name.startsWith("com.taichi.") -> return "太极"
            }
        }
        return null
    }

    /** 框架通过经典 XposedBridge 暴露的 API 级别（LSPosed 当前上报 102）。 */
    private fun xposedApiLevel(): Int {
        runCatching {
            XposedBridge::class.java.getField("XPOSED_BRIDGE_VERSION").getInt(null)
        }.getOrNull()?.let { if (it > 0) return it }
        return runCatching { XposedBridge.getXposedVersion() }.getOrDefault(0)
    }
}
