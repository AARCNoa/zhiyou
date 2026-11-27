package com.AARCNoa.zhiyou.features.items.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.AARCNoa.zhiyou.features.api.ui.WeConversationContextMenuApi
import com.AARCNoa.zhiyou.features.items.chat.ConversationAggregation.FolderChoice
import com.AARCNoa.zhiyou.preferences.WePrefs.Companion.prefOption
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.FolderAddIcon
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.android.showToast

/**
 * 「对话归拢」的首页对话长按菜单项：把该对话加入手动文件夹。
 *
 * 不是独立功能：随 [ConversationAggregation] 的开关注册/注销，
 * 因此开启「对话归拢」后菜单项默认就存在，设置页也不会再出现单独开关。
 */
internal object AddToAggregationFolder : WeConversationContextMenuApi.IMenuItemsProvider {

    /** 加入文件夹后是否自动打开该文件夹的编辑对话框；在「对话归拢」管理弹窗里配置。 */
    var showConfigDialog by prefOption("add_to_folder_show_config_dialog", false)

    fun register() {
        WeConversationContextMenuApi.addProvider(this)
    }

    fun unregister() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777019,
                text = "加入文件夹",
                drawable = FolderAddIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() && !ConversationAggregation.isAggregationFolderId(talker)
                },
            ) { context ->
                onMenuClick(context.activity, context.talker)
            }
        )
    }

    private fun onMenuClick(context: Context, talker: String) {
        val folders = ConversationAggregation.aggregationFolders()
        if (folders.isEmpty()) {
            showToast(context, "暂无文件夹, 请先在「对话归拢」中新建一个")
            return
        }

        showFolderPicker(context, folders, talker)
    }

    private fun showFolderPicker(context: Context, folders: List<FolderChoice>, talker: String) {
        showComposeDialog(context) {
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text("加入文件夹") },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            FolderPickRow(folder) {
                                if (folder.isAuto) {
                                    showToast(context, "「${folder.name}」为自动归拢文件夹, 无法手动添加对话")
                                    return@FolderPickRow
                                }
                                onDismiss()
                                addToFolder(context, folder, talker)
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                }
            )
        }
    }

    private fun addToFolder(context: Context, folder: FolderChoice, talker: String) {
        if (!ConversationAggregation.addToFolder(folder.id, talker)) {
            showToast(context, "「${folder.name}」无法手动添加对话")
            return
        }
        showToast(context, "已加入「${folder.name}」")
        if (showConfigDialog) {
            ConversationAggregation.showAddToFolderDialog(context, folder.id, talker)
        }
    }

    @Composable
    private fun FolderPickRow(folder: FolderChoice, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(folder.name)
            Text(
                text = if (folder.isAuto) "自动归拢, 不可手动添加" else "手动文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
