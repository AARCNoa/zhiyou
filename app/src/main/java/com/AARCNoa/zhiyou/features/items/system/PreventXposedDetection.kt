package com.AARCNoa.zhiyou.features.items.system

import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.HostInfo

/**
 * Java 层反检测，静默功能：没有开关，随模块启动常驻生效。
 *
 * Google Play 版微信没有这条检测，强行 hook 反而可能闪退，因此运行时自动跳过。
 */
@Feature(name = "禁止微信检测 Xposed", categories = ["系统与隐私"], description = "防止微信检测 Xposed 框架是否存在")
object PreventXposedDetection : ApiFeature(), IResolveDex {

    private val methodCheckStackTraceElements by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app")
        matcher {
            usingEqStrings("com.zte.heartyservice.SCC.FrameworkBridge")
        }
    }

    override fun onEnable() {
        if (methodCheckStackTraceElements.isPlaceholder || HostInfo.isHostGooglePlay) return

        methodCheckStackTraceElements.hookBefore {
            try {
                // 仅当原方法返回 boolean 时才设置 result = false，避免对非 boolean 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                        result = false
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }
    }
}
