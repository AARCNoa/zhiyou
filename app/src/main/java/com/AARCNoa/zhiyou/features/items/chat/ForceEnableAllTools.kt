package com.AARCNoa.zhiyou.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexField
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature

import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@Feature(
    name = "强制启用所有功能",
    categories = ["聊天"],
    description = "在聊天工具面板中强制启用所有功能"
)
object ForceEnableAllTools : SwitchFeature(), IResolveDex {

    private const val TAG = "ForceEnableAllTools"

    private val classAppPanelConfig by dexClass()
    private val fieldAppPanelConfig by dexField()
    private val fieldFirstFlag by dexField()
    private val fieldFlagEnabled by dexField()
    private val methodRefreshAppPanel by dexMethod()

    override fun onEnable() {
        methodRefreshAppPanel.hookBefore {
            val appPanel = thisObject ?: return@hookBefore
            val config = fieldAppPanelConfig.field.get(appPanel) ?: return@hookBefore
            config.reflekt().fields {
                type = fieldFirstFlag.field.type
            }.forEach {
                fieldFlagEnabled.field.setBoolean(it.get()!!, true)
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        val refreshMethod = dexKit.findMethod {
            searchPackages(APP_PANEL_PACKAGE)
            matcher {
                declaredClass = APP_PANEL_CLASS
                usingEqStrings("MicroMsg.AppPanel", "roomEnable:%s, hideRoomLive:%s")
                paramCount = 0
                returnType = "void"
            }
        }.singleOrNull() ?: run {
            WeLogger.e(TAG, "AppPanel 刷新方法未找到或多命中，强制启用不可用")
            return
        }
        methodRefreshAppPanel.setDescriptor(refreshMethod)

        // 下面三处都是结构推断：任一不唯一都只让本功能降级，不把异常抛给 Dex 调度器。
        val usedFields = refreshMethod.usingFields.map { it.field }.distinct()
        val firstFlag = usedFields.firstOrNull { field ->
            field.modifiers and Modifiers.FINAL != 0 &&
                field.type.fields.count { it.typeName == "boolean" } == 1
        } ?: run {
            WeLogger.e(TAG, "AppPanel 结构变化：找不到带单个 boolean 的 final 配置字段")
            return
        }
        val configClass = firstFlag.declaredClass
        val appPanelConfig = usedFields.singleOrNull { field ->
            field.className == APP_PANEL_CLASS && field.typeName == configClass.name
        } ?: run {
            WeLogger.e(TAG, "AppPanel 配置字段不唯一（期望 1 个 ${configClass.name}）")
            return
        }
        val flagEnabled = firstFlag.type.fields.singleOrNull {
            it.typeName == "boolean"
        } ?: run {
            WeLogger.e(TAG, "AppPanel 开关字段不唯一（期望 1 个 boolean）")
            return
        }

        classAppPanelConfig.setDescriptor(configClass)
        fieldAppPanelConfig.setDescriptor(appPanelConfig)
        fieldFirstFlag.setDescriptor(firstFlag)
        fieldFlagEnabled.setDescriptor(flagEnabled)
    }

    private const val APP_PANEL_PACKAGE = "com.tencent.mm.pluginsdk.ui.chat"
    private const val APP_PANEL_CLASS = "$APP_PANEL_PACKAGE.AppPanel"
}
