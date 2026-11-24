package com.AARCNoa.zhiyou.features.items.chat_input_bar_menu


import com.AARCNoa.zhiyou.features.api.ui.WeChatInputBarMenuApi
import com.AARCNoa.zhiyou.features.api.ui.WeCurrentConversationApi
import com.AARCNoa.zhiyou.features.core.Feature

import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.features.items.chat.panel.selectAndSendVoice
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons

@Feature(
    name = "发送语音文件",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中添加「发送语音文件」功能"
)
object SendVoiceFile : SwitchFeature() {

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_voice_file",
                icon = OutlinedIcons.Voice_chat,
                label = ("发送语音文件"),
                onClick = { context, _ ->
                    selectAndSendVoice(context, WeCurrentConversationApi.value)
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
