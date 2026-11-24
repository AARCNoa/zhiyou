package com.AARCNoa.zhiyou.loader.startup

import android.content.Context
import android.content.res.Resources
import com.tencent.mm.boot.BuildConfig
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.dexkit.cache.DexCacheManager
import com.AARCNoa.zhiyou.features.core.FeaturesLoader
import com.AARCNoa.zhiyou.loader.utils.ActivityProxy
import com.AARCNoa.zhiyou.loader.utils.ParcelableFixer
import com.AARCNoa.zhiyou.utils.HostFramework
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.RuntimeConfig
import com.AARCNoa.zhiyou.utils.TargetProcesses
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.crash.ExitReasonRecorder
import com.AARCNoa.zhiyou.utils.crash.JavaCrashHandler
import com.AARCNoa.zhiyou.utils.crash.NativeCrashHandler
import com.AARCNoa.zhiyou.utils.crash.ProcessTimeline
import com.AARCNoa.zhiyou.utils.hookBeforeDirectly
import com.AARCNoa.zhiyou.utils.invokeOriginal
import com.AARCNoa.zhiyou.utils.reflection.int

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        // 尽早接管未捕获异常：模块后台线程/协程抛异常时只记录、不结束宿主进程，
        // 否则 Android 会直接杀掉整个微信进程（用户感知就是「微信被强行停止」）。
        runCatching { JavaCrashHandler.install() }

        // 原生崩溃（SIGSEGV/SIGABRT/SIGBUS…）Java 层完全抓不到，而它正是「微信莫名其妙
        // 被杀、日志里什么都没有」最常见的成因。Rust 侧 crash_handler 早已实现（且会链式
        // 调用微信自己注册的旧 handler，不会吞掉宿主处理），只是一直没被安装 —— 这里补上。
        runCatching { NativeCrashHandler.install() }
            .onFailure { WeLogger.w(TAG, "native crash handler install failed: ${it.message}") }

        // 记录上次进程退出原因（内存回收 / 崩溃 / ANR / 用户强行停止），供诊断页区分责任方。
        runCatching {
            val appCtx = context.applicationContext ?: context
            Thread({
                // 启动时间线 + 上次退出原因：用于判断「后台被杀」是偶发还是周期性。
                runCatching { ProcessTimeline.recordStartup(appCtx) }
                runCatching { ExitReasonRecorder.recordOnStartup(appCtx) }
                // AI 档案的初始化是内存线程：进程重启后还能读到 initializing 的档案，
                // 说明上一次被中断了，必须复位成失败态，否则弹窗会永远停在「准备中」。
                if (TargetProcesses.isInMain) {
                    runCatching { com.AARCNoa.zhiyou.ai.AiProfileInitializer.recoverInterrupted() }
                }
            }, "WCX-ExitReason").apply { isDaemon = true }.start()
        }

        ParcelableFixer.init()

        // 兜底：「资源表里有 ID、APK 里没有文件」会让微信自己 inflate 时闪退（raw svg、
        // 以及 xml 选择器里引用的子资源都算），这里只对这两类降级，详见 ResourceFallbackGuard 注释。
        com.AARCNoa.zhiyou.utils.fix.ResourceFallbackGuard.install()

        // 每个进程只留这一条启动横幅：框架版本、崩溃拦截、资源兜底的实际状态都在这里。
        // 以前框架识别 / native handler / svg 兜底各打一条「成功」，每次进程启动固定刷 3 行，
        // 而小程序每次冷启都是新进程，一天下来光这几行就上百条。
        WeLogger.i(
            TAG,
            "模块已就绪：${TargetProcesses.currentName} · ${HostFramework.display()} · " +
                "native=${if (NativeCrashHandler.isInstalled) "ok" else "no"} " +
                "svg=${if (com.AARCNoa.zhiyou.utils.fix.ResourceFallbackGuard.isInstalled) "ok" else "no"}",
        )

        // dex 缓存 key 只跟「微信版本 / 微信客户端版本」，不跟随模块构建指纹：
        // 缓存里存的是微信侧的类与方法描述符（com.tencent.mm.*），与模块自身 R8 混淆结果无关，
        // 所以同版本号的多次 dev 构建应当直接复用缓存（没有改动功能就不该整轮重新适配）。
        // 单个功能是否需要重新解析由 DexCacheManager 的 methodHash 决定：构建期生成器按
        // resolveDex 源码算哈希，只有该功能的定位实现变了、或缓存缺 key 时才重新解析。
        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs

            // Compose 的 Resources.getString 在宿主资源表缺 ID 时会抛 NotFoundException，
            // 这里统一降级为字符串 "null"，避免整页渲染崩掉。
            Resources::class.java.getDeclaredMethod("getString", int).hookBeforeDirectly {
                result = runCatching { invokeOriginal() }.getOrNull() ?: "null"
            }
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }

    }

    private const val TAG = "WeLauncher"
}
