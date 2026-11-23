package com.AARCNoa.zhiyou.loader.startup

import android.app.Application
import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.AARCNoa.zhiyou.loader.abc.IHookBridge
import com.AARCNoa.zhiyou.loader.abc.ILoaderService
import com.AARCNoa.zhiyou.loader.utils.HybridClassLoader
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.hookAfterDirectly
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders

object UnifiedEntryPoint {

    private const val TAG = "UnifiedEntryPoint"

    fun entry(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        initialClassLoader: ClassLoader,
        modulePath: String
    ) {
        val self = ClassLoaders.MODULE
        val selfParent = self.parent
        HybridClassLoader.moduleParentClassLoader = selfParent
        self.reflekt()
            .firstField { name = "parent"; superclass() }
            .set(HybridClassLoader)

        WeLogger.d(TAG, "hooking Application.attachBaseContext")

        "com.tencent.mm.app.Application".toClass(initialClassLoader).reflekt()
            .firstMethod { name = "attachBaseContext" }
            .hookAfterDirectly {
                WeLogger.d(TAG, "Application.attachBaseContext invoked, hooking Instrumentation.callApplicationOnCreate")
                val currentClassLoader = (thisObject as Context).classLoader
                "android.app.Instrumentation".toClass(currentClassLoader).reflekt()
                    .firstMethod("callApplicationOnCreate").hookAfterDirectly {
                        WeLogger.d(TAG, "Instrumentation.callApplicationOnCreate invoked, running StartupAgent")
                        runCatching {
                            StartupAgent.startup(
                                loaderService,
                                hookBridge,
                                modulePath,
                                args[0] as Application
                            )
                        }.onFailure { error ->
                            // 模块 APK 在微信进程运行期间被覆盖安装时，LSPosed 传进来的
                            // modulePath 指向的旧目录已被系统删除，dlopen(libdexkit.so) 必然失败。
                            // 这不是代码问题，重启微信就会走新路径加载，所以降为 W 并写明处置办法，
                            // 避免每次装机都留下一条看起来像崩溃的 E + 大段 native 堆栈。
                            if (error is UnsatisfiedLinkError && error.message?.contains(".so\" not found") == true) {
                                WeLogger.w(
                                    TAG,
                                    "StartupAgent 跳过本次启动：模块 native 库不在 modulePath 下" +
                                        "（通常是模块 APK 在微信运行期间被覆盖安装）。重启微信即可恢复。",
                                )
                            } else {
                                WeLogger.e(TAG, "StartupAgent failed", error)
                            }
                        }
                    }
            }
    }
}
