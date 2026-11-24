package com.AARCNoa.zhiyou.features.items.chat

import com.AARCNoa.zhiyou.features.api.core.WeMessageApi
import com.AARCNoa.zhiyou.features.api.core.models.MessageType
import com.AARCNoa.zhiyou.features.api.ui.WeChatMessageContextMenuApi
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.ui.utils.DownloadIcon
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "文件下载到本地", categories = ["聊天"], description = "在文件消息菜单添加下载按钮, 允许将文件缓存并保存到本地")
object DownloadFilesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "DownloadFilesToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777022,
                "下载",
                DownloadIcon,
                OutlinedIcons.Download,
                { msgInfo -> msgInfo.type == MessageType.FILE }
            ) { _, _, msgInfo ->
                showToast("正在缓存并下载...")
                CoroutineScope(Dispatchers.IO).launch {
                    val path = WeMessageApi.downloadFile(msgInfo.instance) ?: run {
                        WeLogger.e(TAG, "failed to cache & download file")
                        showToastSuspend("文件下载失败! 查看日志以了解错误详情")
                        return@launch
                    }
                    showToastSuspend("已将文件下载到 $path")
                }
            }
        )
    }
}
