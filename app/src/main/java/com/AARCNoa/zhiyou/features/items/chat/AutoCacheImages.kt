package com.AARCNoa.zhiyou.features.items.chat

import android.content.ContentValues
import com.AARCNoa.zhiyou.features.api.core.WeMessageApi
import com.AARCNoa.zhiyou.features.api.core.models.MessageType
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.preferences.WePrefs

@Feature(name = "自动缓存图片", categories = ["聊天"], description = "监听接收到的图片消息, 自动触发微信从 CDN 下载, 将原图缓存到本地")
object AutoCacheImages : AutoCacheFeature() {

    override val typeCode = MessageType.IMAGE.code
    override val label = "图片"
    override val logTag = "AutoCacheImages"

    override var useWhitelist by WePrefs.prefOption("autocache_images_use_whitelist", false)
    override var whitelist by WePrefs.prefOption("autocache_images_whitelist", emptySet())
    override var blacklist by WePrefs.prefOption("autocache_images_blacklist", emptySet())

    override fun cache(values: ContentValues, msgSvrId: Long): Boolean {
        launchCache(msgSvrId) { WeMessageApi.cacheImage(msgSvrId) }
        return true
    }
}
