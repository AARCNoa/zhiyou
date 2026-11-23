package com.AARCNoa.zhiyou.loader.startup

import com.AARCNoa.zhiyou.loader.abc.IHookBridge
import com.AARCNoa.zhiyou.loader.abc.ILoaderService

object StartupInfo {

    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null

    /** 模块 APK 路径（资源注入用）。 */
    val modulePath: String
        get() = runCatching { com.AARCNoa.zhiyou.utils.HostInfo.appInfo.sourceDir }.getOrDefault("")

    /**
     * LibXposed 入口自报的 API 级别（101/102）。经典 Xposed 入口不会赋值，保持 0。
     * 该值在 `onModuleLoaded` 里就确定了，但那时 MMKV 还没初始化，所以先存内存，
     * 等启动完成后由 [com.AARCNoa.zhiyou.utils.HostFramework] 统一写盘。
     */
    var entryApi: Int = 0
}
