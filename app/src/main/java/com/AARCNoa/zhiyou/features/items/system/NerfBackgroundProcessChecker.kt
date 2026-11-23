package com.AARCNoa.zhiyou.features.items.system

import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature

@Feature(
    name = "禁用微信进程状态检测器",
    categories = ["系统与隐私"],
    description = "微信会定期检测主进程状态并主动结束进程，本功能关闭该检测"
)
object NerfBackgroundProcessChecker : SwitchFeature(), IResolveDex {

    private val methodPerformProcessCheck by dexMethod {
        matcher {
            usingEqStrings("pass this check,because request is null! ????")
        }
    }

    override fun onEnable() {
        methodPerformProcessCheck.hookBefore {
            try {
                // 仅当原方法返回 void 时才设置 result = null，避免对非 void 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                val returnType = (method as java.lang.reflect.Method).returnType
                if (returnType == Void.TYPE) {
                    result = null
                }
            }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }
    }
}
