package com.AARCNoa.zhiyou.loader.entry.common

import com.AARCNoa.zhiyou.features.core.FeaturesProvider
import com.AARCNoa.zhiyou.loader.abc.IHookBridge
import com.AARCNoa.zhiyou.loader.abc.ILoaderService
import com.AARCNoa.zhiyou.loader.startup.UnifiedEntryPoint
import com.AARCNoa.zhiyou.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private var isInitialized = false

    /** 仅用于热重载日志（说明当前是哪个入口在重载）。 */
    private lateinit var loaderService: ILoaderService

    @Suppress("unused")
    @JvmStatic
    fun init(
        initialClassLoader: ClassLoader,
        service: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String
    ) {
        if (isInitialized) return
        isInitialized = true

        loaderService = service

        // 进程级信息统一由 WeLauncher 的启动横幅输出，这里不重复刷一行。
        WeLogger.d(TAG, "loading in entry point ${service.entryPointName}")
        runCatching {
            UnifiedEntryPoint.entry(service, hookBridge, initialClassLoader, modulePath)
        }.onFailure { WeLogger.e(TAG, "UnifiedEntryPoint failed", it) }
    }

    /**
     * 热重载：先关掉所有已生效的功能，再按当前偏好重新启动。
     * 只在框架支持 API102+ 热重载时被调用（见 [com.AARCNoa.zhiyou.loader.entry.lsp10x.Lsp10xUnifiedHookEntry]）。
     */
    fun hotReload() {
        WeLogger.i(TAG, "hot-reloading in entry point ${loaderService.entryPointName}")

        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        WeLogger.i(TAG, "disabling ${allFeatures.count { it.isActive }} active features for hot-reload")

        allFeatures.forEach { feature ->
            if (feature.isActive) {
                runCatching {
                    feature.disable()
                }.onFailure { e ->
                    WeLogger.e(TAG, "failed to disable feature ${feature.displayName} during hot-reload", e)
                }
            }
        }

        WeLogger.i(TAG, "re-enabling features with current settings")
        allFeatures.forEach { feature ->
            runCatching {
                feature.startup()
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to startup feature ${feature.displayName} during hot-reload", e)
            }
        }

        WeLogger.i(TAG, "hot-reload complete: ${allFeatures.count { it.isActive }} features active")
    }
}
