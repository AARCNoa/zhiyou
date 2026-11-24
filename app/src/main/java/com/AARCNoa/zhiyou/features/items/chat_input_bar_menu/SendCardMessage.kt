package com.AARCNoa.zhiyou.features.items.chat_input_bar_menu


import com.AARCNoa.zhiyou.features.api.core.WeMessageApi
import com.AARCNoa.zhiyou.features.api.ui.WeChatInputBarMenuApi
import com.AARCNoa.zhiyou.features.api.ui.WeCurrentConversationApi
import com.AARCNoa.zhiyou.features.core.Feature

import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.utils.android.showToast

@Feature(
    name = "发送卡片消息",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中添加「发送卡片消息」功能"
)
object SendCardMessage : SwitchFeature() {

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_card_message",
                icon = OutlinedIcons.Send_time_extension,
                label = ("发送卡片消息"),
                onClick = { context, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    val content = chatFooter.lastText

                    if (content.isEmpty()) {
                        showToast(
                            context,
                            ("输入内容为空！"),
                        )
                        return@ActionItem
                    }

                    val isSuccess = WeMessageApi.sendXmlAppMsg(currentConv, content)
                    if (!isSuccess) {
                        showToast(
                            context,
                            ("发送卡片消息失败，请检查格式"),
                        )
                        return@ActionItem
                    }

                    chatFooter.lastText = ""
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
