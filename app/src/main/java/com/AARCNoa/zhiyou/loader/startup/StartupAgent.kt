package com.AARCNoa.zhiyou.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import com.AARCNoa.zhiyou.loader.abc.IHookBridge
import com.AARCNoa.zhiyou.loader.abc.ILoaderService
import com.AARCNoa.zhiyou.loader.utils.HybridClassLoader
import com.AARCNoa.zhiyou.loader.utils.NativeLoader
import com.AARCNoa.zhiyou.utils.HostFramework
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private const val TAG = "StartupAgent"

    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) {
        if (initialized) return
        initialized = true

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        checkWriteXorExecuteForModulePath(modulePath)

        HostInfo.init(application)
        NativeLoader.init(application)
        // MMKV 就绪后才能写偏好：把「哪个框架 + 哪套 API 加载了模块」记进跨进程缓存，
        // 微信内的设置页靠它显示「激活框架」。
        HostFramework.recordAtLoad(loaderService.entryPointName, StartupInfo.entryApi)
        WeLauncher.init(application)

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }
    }

    private fun checkWriteXorExecuteForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nThis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "hidden api is not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = runCatching {
            Class.forName("android.app.ContextImpl")
        }.getOrElse { return false }

        var mActivityToken: Field? = null
        var mToken: Field? = null

        try {
            mActivityToken = kContextImpl.getDeclaredField("mActivityToken")
        } catch (_: NoSuchFieldException) {
        }
        try {
            mToken = kContextImpl.getDeclaredField("mToken")
        } catch (_: NoSuchFieldException) {
        }

        return mActivityToken != null || mToken != null
    }
}
