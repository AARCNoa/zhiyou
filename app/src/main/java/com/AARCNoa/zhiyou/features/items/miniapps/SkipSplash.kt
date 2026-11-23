package com.AARCNoa.zhiyou.features.items.miniapps

import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.TargetProcesses

@Feature(name = "跳过启动页面", categories = ["小程序"], description = "跳过小程序启动页面, 变相去广告 (实验性)")
object SkipSplash : SwitchFeature(), IResolveDex {

    private val methodShowSplash by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand")
        matcher {
            declaredClass = "com.tencent.mm.plugin.appbrand.AppBrandRuntime"
            returnType = "void"
            paramCount = 0
            usingEqStrings("Loading页展示")
        }
    }

    override val targetProcessMask get() = TargetProcesses.PROC_MAIN or TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        methodShowSplash.hookBefore {
            try {
                // 仅当原方法返回 void 时才设置 result = null，避免对非 void 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Void.TYPE) {
                        result = null
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }
    }
}
