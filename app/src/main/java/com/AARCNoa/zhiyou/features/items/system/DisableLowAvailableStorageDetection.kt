package com.AARCNoa.zhiyou.features.items.system

import android.app.Activity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature

@Feature(
    name = "禁用存储空间不足检测",
    categories = ["系统与隐私"],
    description = "移除微信在可用空间不足时强制清理的限制"
)
object DisableLowAvailableStorageDetection : SwitchFeature(), IResolveDex {

    private val methodSplashActivitySplashFinished by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.splash.SplashActivity"
            usingEqStrings("WxSplash.SplashActivity", "Call splashFinished.")
        }
    }
    private val classStaticValuesHolder by dexClass {
        matcher {
            usingEqStrings("UIPageFragmentActivity", "LuckyMoneyNewPrepareUI", "RemittanceUI")
        }
    }

    override fun onEnable() {
        methodSplashActivitySplashFinished.hookBefore {
            classStaticValuesHolder.clazz.reflekt()
                .firstField { type = Boolean::class }
                .setStatic(false)
        }

        "com.tencent.mm.plugin.clean.ui.fileindexui.StorageDisableAlertUI"
            .toClass().hookAfterOnCreate {
                val activity = thisObject as Activity
                activity.finish()
            }
    }
}
