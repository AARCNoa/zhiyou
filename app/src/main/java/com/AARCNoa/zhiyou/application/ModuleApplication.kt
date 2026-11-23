package com.AARCNoa.zhiyou.application

import android.app.Application
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.crash.JavaCrashHandler
import com.AARCNoa.zhiyou.utils.hook_status.HookStatus

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        // 框架服务监听尽早注册：MainActivity.onCreate 里注册会晚于 LSPosed 的首次绑定，
        // 部分环境下拿不到 onServiceBind，表现为「未检测到 Xposed 框架」。
        runCatching { HookStatus.init(this) }
            .onFailure { WeLogger.e("ModuleApplication", "failed to register xposed service listener", it) }
        // 安装全局 Java 崩溃捕获，防止未捕获异常导致闪退
        runCatching { JavaCrashHandler.install() }
            .onFailure { WeLogger.e("ModuleApplication", "failed to install crash handler", it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 只记录，不主动 System.gc()：系统在 TRIM 时会自行回收，主动 GC 只会带来
        // 一次全堆 STW 卡顿，换不到任何可观测的收益。
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            WeLogger.i("ModuleApplication", "onTrimMemory: level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        WeLogger.w("ModuleApplication", "onLowMemory")
    }
}
