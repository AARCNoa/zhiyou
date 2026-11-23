package com.AARCNoa.zhiyou.features.items.ai

import com.AARCNoa.zhiyou.ai.AiAutoIncrementalScheduler
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature

/**
 * 自动增量整理的后台入口：只负责把调度器挂到功能生命周期上。
 *
 * 每个好友的开关在档案弹窗的「功能」里，不是功能列表里的总开关 —— 关掉它等于替所有
 * 好友取消了自动整理，用户要的是逐个控制，所以这里只提供整体停用的入口。
 */
@Feature(
    name = "自动增量整理",
    categories = ["API"],
    description = "对开启了自动整理的好友，每天到设定时间补一次新增聊天记录",
)
object AiAutoIncrementalFeature : ApiFeature() {

    override fun onEnable() {
        AiAutoIncrementalScheduler.onEnable()
    }

    override fun onDisable() {
        AiAutoIncrementalScheduler.onDisable()
    }
}
