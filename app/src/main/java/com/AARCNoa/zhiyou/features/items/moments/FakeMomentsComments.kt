package com.AARCNoa.zhiyou.features.items.moments

import android.content.ContentValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.AARCNoa.zhiyou.features.api.core.WeDatabaseApi
import com.AARCNoa.zhiyou.features.api.core.WeDatabaseListenerApi
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsContextMenuApi
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.ContactsSelector
import com.AARCNoa.zhiyou.ui.content.DefaultColumn
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.EditIcon
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.runOnUiThread
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import com.AARCNoa.zhiyou.utils.reflection.BString
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.makeAccessible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

/**
 * 伪评论：朋友圈长按菜单 →「伪评论」→ 选好友 → 输入内容 → 好友出现在这条朋友圈的评论区。
 *
 * 评论实际保存在这条朋友圈自己的 `SnsInfo.attrBuf`：
 * `SnsObject.CommentUserList`。这里不是 `SnsComment` 表——后者是 SNS 通知记录，
 * 真机表结构也只有 snsID / talker / curActionBuf 等列，并不保存信息流评论正文。
 *
 * 与「伪点赞」共用已验证的 `WeMomentsApi.rewriteSnsInfoAttrBuf` 写回链路，仅本机显示。
 */
@Feature(name = "伪评论", categories = ["朋友圈"], description = "让选中的好友以指定内容出现在这条朋友圈的评论区（仅本机显示）")
object FakeMomentsComments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "FakeMomentsComments"
    private const val MENU_ID = 777021
    private const val PREF_KEY = "fake_moments_comments"

    private class Inserted(val wxId: String, val content: String, val commentId: Long)

    /** snsId -> 本机注入的伪评论，供清除和微信刷新动态后重新注入。 */
    private val inserted = ConcurrentHashMap<Long, MutableList<Inserted>>()

    private lateinit var entryClass: Class<*>
    private lateinit var usernameField: Field
    private lateinit var nicknameField: Field
    private lateinit var contentField: Field
    private lateinit var replyUsernameField: Field
    private lateinit var parseFromMethod: Method

    override fun onEnable() {
        val resolved = runCatching {
            entryClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
            // 与「伪点赞」「隐藏联系人朋友圈」一致：Username、Nickname、Content、ReplyUsername。
            val strings = entryClass.reflekt().fields { type = BString }.map { it.self.makeAccessible() }
            require(strings.size >= 4) { "SNS comment entry has ${strings.size} string fields" }
            usernameField = strings[0]
            nicknameField = strings[1]
            contentField = strings[2]
            replyUsernameField = strings[3]
            parseFromMethod = SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
            true
        }.getOrElse {
            WeLogger.e(TAG, "解析朋友圈评论结构失败", it)
            return
        }
        if (!resolved) return
        loadConfig()
        WeMomentsContextMenuApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        WeDatabaseListenerApi.removeListener(this)
    }

    /** 微信从服务器刷新这条动态时会重写 attrBuf，在写库前把本机伪评论补回去。 */
    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) {
        if (table != "SnsInfo") return
        runCatching {
            val snsId = (values.get("snsId") as? Number)?.toLong() ?: return@runCatching
            val comments = inserted[snsId].orEmpty()
            if (comments.isEmpty()) return@runCatching
            val raw = values.get("attrBuf") as? ByteArray ?: return@runCatching
            values.put("attrBuf", rebuildAttrBuf(raw, comments, comments) ?: return@runCatching)
        }.onFailure { WeLogger.e(TAG, "重新注入伪评论失败", it) }
    }

    override fun getMenuItems() = listOf(
        WeMomentsContextMenuApi.MenuItem(MENU_ID, "伪评论", EditIcon, { _, _ -> true }) { moment ->
            val snsInfo = moment.snsInfo ?: return@MenuItem
            val snsId = runCatching {
                (snsInfo.reflekt().getField("field_snsId", true) as Number).toLong()
            }.getOrNull()
            if (snsId == null) {
                showToast("拿不到这条朋友圈的 ID")
                return@MenuItem
            }
            val activity = moment.activity

            fun showSelector() {
                showComposeDialog(activity) {
                    ContactsSelector(
                        title = "选择要评论的好友",
                        contacts = WeDatabaseApi.getContacts(),
                        initialSelectedWxIds = emptySet(),
                        onDismiss = onDismiss,
                        onConfirm = { selected ->
                            onDismiss()
                            if (selected.isEmpty()) return@ContactsSelector
                            showComposeDialog(activity) {
                                CommentContentDialog(
                                    snsId = snsId,
                                    selected = selected,
                                    onDismiss = { onDismiss() },
                                )
                            }
                        },
                    )
                }
            }

            val existing = inserted[snsId].orEmpty()
            if (existing.isEmpty()) {
                showSelector()
                return@MenuItem
            }

            // 已有伪评论时先给出一键清空入口；不清空则继续原来的选好友流程。
            showComposeDialog(activity) {
                AlertDialogContent(
                    title = { Text("伪评论") },
                    text = { Text("本条已有 ${existing.size} 条伪评论，是否清空？") },
                    confirmButton = {
                        Button(onClick = {
                            onDismiss()
                            showSelector()
                        }) { Text("继续伪评论") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            onDismiss()
                            clearFakeComments(snsId)
                        }) { Text("一键清空") }
                    },
                )
            }
        },
    )

    @Composable
    private fun CommentContentDialog(snsId: Long, selected: Set<String>, onDismiss: () -> Unit) {
        var input by remember { mutableStateOf("") }
        val existing = inserted[snsId].orEmpty()
        AlertDialogContent(
            title = { Text("评论内容") },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("例如 666") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (existing.isNotEmpty()) {
                        TextButton(onClick = {
                            onDismiss()
                            clearFakeComments(snsId)
                        }) { Text("清除本条伪评论（${existing.size}）") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val content = input.trim()
                    if (content.isEmpty()) {
                        showToast("请输入评论内容")
                        return@Button
                    }
                    onDismiss()
                    insertFakeComments(snsId, selected, content)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }

    private fun insertFakeComments(snsId: Long, wxIds: Set<String>, content: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val previous = inserted[snsId].orEmpty()
            var localId = System.currentTimeMillis()
            val additions = wxIds.map { wxId -> Inserted(wxId, content, localId++) }
            val updated = previous + additions
            val applied = WeMomentsApi.rewriteSnsInfoAttrBuf(snsId) { raw ->
                rebuildAttrBuf(raw, previous + additions, updated)
            }
            if (!applied) {
                showToastSuspend("伪评论写入失败，请查看日志")
                return@launch
            }
            inserted[snsId] = updated.toMutableList()
            saveConfig()
            runOnUiThread { FakeMomentsLikes.refreshMomentsLists() }
            WeLogger.i(TAG, "已写入 ${additions.size} 条伪评论：snsId=$snsId")
            showToastSuspend("已为 ${additions.size} 位好友写入伪评论")
        }
    }

    private fun clearFakeComments(snsId: Long) {
        val previous = inserted[snsId].orEmpty()
        if (previous.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val applied = WeMomentsApi.rewriteSnsInfoAttrBuf(snsId) { raw ->
                rebuildAttrBuf(raw, previous, emptyList())
            }
            if (!applied) {
                showToastSuspend("伪评论清除失败，请查看日志")
                return@launch
            }
            inserted.remove(snsId)
            saveConfig()
            runOnUiThread { FakeMomentsLikes.refreshMomentsLists() }
            showToastSuspend("已清除 ${previous.size} 条伪评论")
        }
    }

    /**
     * 保留真实评论，先移除本轮管理的旧伪评论，再追加带用户名、昵称和正文的新伪评论。
     * 评论列表和两处计数必须一起更新，否则详情页和信息流可能显示不一致。
     */
    private fun rebuildAttrBuf(raw: ByteArray, remove: List<Inserted>, additions: List<Inserted>): ByteArray? =
        runCatching {
            val snsObj = SnsObject()
            parseFromMethod.invoke(snsObj, raw)
            val removeKeys = remove.mapTo(HashSet()) { it.wxId to it.content }
            val merged = LinkedList<Any>()
            (snsObj.CommentUserList as? List<*>).orEmpty().filterNotNull().forEach { entry ->
                val username = usernameField.get(entry) as? String
                val content = contentField.get(entry) as? String
                if ((username to content) in removeKeys) return@forEach
                merged.add(entry)
            }
            additions.forEach { item ->
                val entry = entryClass.createInstance()
                usernameField.set(entry, item.wxId)
                nicknameField.set(entry, displayNameOf(item.wxId))
                contentField.set(entry, item.content)
                replyUsernameField.set(entry, "")
                merged.add(entry)
            }
            snsObj.CommentUserList = merged
            snsObj.CommentUserListCount = merged.size
            snsObj.CommentCount = merged.size
            snsObj.toByteArray()
        }.onFailure { WeLogger.e(TAG, "重建朋友圈评论列表失败", it) }.getOrNull()

    private fun displayNameOf(wxId: String): String =
        runCatching { WeDatabaseApi.getDisplayName(wxId) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: wxId

    private fun loadConfig() {
        val raw = runCatching { WePrefs.getString(PREF_KEY) }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(raw)
            for (key in root.keys()) {
                val snsId = key.toLongOrNull() ?: continue
                val array = root.getJSONArray(key)
                val list = mutableListOf<Inserted>()
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list += Inserted(o.getString("wxId"), o.getString("content"), o.optLong("commentId"))
                }
                inserted[snsId] = list
            }
        }.onFailure { WeLogger.e(TAG, "读取伪评论配置失败", it) }
    }

    private fun saveConfig() {
        runCatching {
            val root = JSONObject()
            for ((snsId, list) in inserted) {
                if (list.isEmpty()) continue
                val array = JSONArray()
                for (item in list) {
                    array.put(
                        JSONObject().put("wxId", item.wxId).put("content", item.content).put("commentId", item.commentId)
                    )
                }
                root.put(snsId.toString(), array)
            }
            WePrefs.putString(PREF_KEY, root.toString())
        }.onFailure { WeLogger.e(TAG, "保存伪评论配置失败", it) }
    }
}
