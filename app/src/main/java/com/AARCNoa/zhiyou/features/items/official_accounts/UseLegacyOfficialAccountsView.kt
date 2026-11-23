package com.AARCNoa.zhiyou.features.items.official_accounts

import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.features.api.ui.WeStartActivityApi
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger

@Feature(name = "恢复旧版公众号列表", categories = ["公众号"], description = "仅适用于旧版本微信，新版已移除旧 UI")
object UseLegacyOfficialAccountsView : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val className = intent.component?.className
        if (className == "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
            className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI"
        ) {
            try {
                val ctx = param.thisObject as? android.content.Context
                val pm = ctx?.packageManager ?: run {
                    WeLogger.w("UseLegacyOfficialAccountsView", "no context/packageManager available")
                    return
                }
                val checkIntent = Intent().apply {
                    component = ComponentName(
                        HostInfo.packageName,
                        "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"
                    )
                }
                if (pm.resolveActivity(checkIntent, 0) != null) {
                    intent.component = checkIntent.component
                    WeLogger.d("UseLegacyOfficialAccountsView", "redirected $className")
                } else {
                    WeLogger.d("UseLegacyOfficialAccountsView", "NewBizConversationUI not found, skip redirect")
                }
            } catch (e: Throwable) {
                WeLogger.w("UseLegacyOfficialAccountsView", "redirect check failed: ${e.message}")
            }
        }
    }
}
