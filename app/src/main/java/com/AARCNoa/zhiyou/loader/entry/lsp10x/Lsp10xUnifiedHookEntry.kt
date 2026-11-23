@file:Suppress("unused")

package com.AARCNoa.zhiyou.loader.entry.lsp10x

import androidx.annotation.Keep
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.loader.entry.common.ModuleLoader
import com.AARCNoa.zhiyou.loader.startup.StartupInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Unified LibXposed entry point compatible with both API101 and API102.
 *
 * Strategy:
 * - API102 (standard new environment): enables hot-reload — settings changes take effect
 *   without restarting WeChat
 * - API101 (legacy / LSPatch): degrades gracefully — hot-reload is disabled, WeChat
 *   must be restarted for settings changes to take effect
 *
 * The API version is detected at runtime via [ModuleLoadedParam.apiVersion]:
 * it is kept in [StartupInfo.entryApi] until MMKV is ready, then written to the
 * cross-process cache by [com.AARCNoa.zhiyou.utils.HostFramework] so the settings UI can
 * display which framework/API actually loaded the module.
 */
@Keep
class Lsp10xUnifiedHookEntry : XposedModule() {

    companion object {
        private const val TAG = "Lsp10xUnifiedHookEntry"
        private const val API_102 = 102
        private const val API_101 = 101

        @Volatile
        var detectedApiVersion: Int = 0
            private set

        @Volatile
        var isHotReloadSupported: Boolean = false
            private set
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        detectedApiVersion = runCatching {
            param.javaClass.getDeclaredField("apiVersion").apply { isAccessible = true }.getInt(param)
        }.getOrDefault(API_101)
        isHotReloadSupported = detectedApiVersion >= API_102
        // 此时 MMKV 还没初始化（要等 Application 起来），只能先存内存；
        // 由 StartupAgent → HostFramework 在启动完成后统一写进跨进程缓存。
        StartupInfo.entryApi = detectedApiVersion

        WeLogger.d(TAG, "module loaded, detected API version: $detectedApiVersion, hot-reload: $isHotReloadSupported")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage) {
                WeLogger.d(TAG, "first package ready, initializing module (API=$detectedApiVersion)")

                ModuleLoader.init(
                    param.classLoader,
                    Lsp10xHookImpl,
                    Lsp10xHookImpl,
                    this.moduleApplicationInfo.sourceDir
                )
            }
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (!isHotReloadSupported) {
            WeLogger.w(TAG, "hot-reload requested but API version $detectedApiVersion does not support it")
            return false
        }
        WeLogger.d(TAG, "hot-reload requested, allowing...")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (!isHotReloadSupported) {
            WeLogger.w(TAG, "hot-reload completed but API version $detectedApiVersion does not support it — ignoring")
            return
        }
        WeLogger.d(TAG, "hot-reload completed, re-initializing module...")

        // Unhook all old hooks
        param.oldHookHandles.forEach { it.unhook() }

        // Re-initialize the module
        ModuleLoader.hotReload()
    }
}