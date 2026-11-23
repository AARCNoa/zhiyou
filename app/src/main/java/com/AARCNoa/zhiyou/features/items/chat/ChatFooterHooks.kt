package com.AARCNoa.zhiyou.features.items.chat

import android.view.View
import android.widget.ImageButton
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.api.ui.WeChatInputBarMenuApi
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.ui.utils.findViewByChildIndexes
import com.AARCNoa.zhiyou.ui.utils.findViewWhich
import com.AARCNoa.zhiyou.ui.utils.findViewsWhich
import com.AARCNoa.zhiyou.utils.WeLogger
import android.widget.Button as AndroidButton

@Feature(name = "聊天输入栏钩子", categories = ["API"], description = "集中提供聊天输入栏相关钩子")
object ChatFooterHooks : ApiFeature(), IResolveDex {

    private const val TAG = "ChatFooterHooks"

    // ChatFooter.l0() is the deobfuscated initSmileyBtn — found by the string reference
    // WeChat emits in its internal tracing framework at ChatFooter.java:4198.
    private val methodInitSmileyBtn by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("initSmileyBtn")
        }
    }

    /**
     * 定位原生发送按钮: 第 0 个子视图里文本为 "发送"/"send" 的 Button。
     * 语音输入模式下这个按钮可能不存在, 因此返回可空。
     */
    private fun findSendButton(root: View): AndroidButton? =
        root.findViewWhich<AndroidButton> { view ->
            view.javaClass.name == "android.widget.Button" && run {
                val text = (view as AndroidButton).text?.toString()?.trim() ?: ""
                text == "发送" || text.equals("send", ignoreCase = true)
            }
        }

    override fun onEnable() {
        methodInitSmileyBtn.hookAfter {
            val chatFooter = thisObject as ChatFooter
            val searchedView = chatFooter.findViewByChildIndexes<View>(0) ?: return@hookAfter
            val imgButtons = searchedView.findViewsWhich<ImageButton> { view ->
                view.javaClass.simpleName == "WeImageButton"
            }.toList()

            // 监听**无条件**挂上，是否生效在按下那一刻判断。
            // 以前是「开关打开时才 setOnLongClickListener」，但 ChatFooter 实例会被
            // 复用（同一个聊天页来回切），关掉开关后旧监听还留在 View 上 →
            // 「关了长按语音/表情，长按还能用」。按下时判 isEnabled 才是实时的。
            imgButtons.getOrNull(0)?.setOnLongClickListener { view ->
                if (!VoicePanel.isEnabled) return@setOnLongClickListener false
                VoicePanel.openPanel(view)
                true
            }

            imgButtons.getOrNull(1)?.setOnLongClickListener { v ->
                if (!StickerPanel.isEnabled) return@setOnLongClickListener false
                StickerPanel.openPanel(v)
                true
            }

            // 长按菜单入口不属于任何一个功能: 菜单内容由 WeChatInputBarMenuApi 的
            // provider 决定 (发送语音文件 / 发送卡片消息 / @所有人 各自注册)。
            // 因此这里无条件挂监听, 按下时再看有没有 provider —— 用户随时开关功能
            // 都能生效, 不需要重开聊天页。
            val targets = buildList {
                imgButtons.lastOrNull()?.let(::add)
                findSendButton(searchedView)?.let(::add)
            }
            WeLogger.d(
                TAG,
                "input bar hooks: imageButtons=${imgButtons.size} longPressTargets=${targets.size}",
            )
            targets.forEach { button ->
                button.setOnLongClickListener { view ->
                    val hasProviders = WeChatInputBarMenuApi.hasProviders
                    WeLogger.d(TAG, "input bar long-press: providers=$hasProviders")
                    if (!hasProviders) return@setOnLongClickListener false
                    WeChatInputBarMenuApi.showMenu(view.context, chatFooter)
                    true
                }
            }
        }
    }
}
