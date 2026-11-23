package com.AARCNoa.zhiyou.features.items.moments

import android.os.Looper
import android.os.Handler
import android.view.ViewGroup
import android.view.View
import android.app.Activity
import android.content.ContentValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.AARCNoa.zhiyou.ui.utils.StarIcon
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.reflection.BString
import com.AARCNoa.zhiyou.utils.strings.isGroupChatWxId
import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.makeAccessible
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedList

/**
 * 伪集赞：把自己选的联系人显示成某条朋友圈的点赞人。
 *
 * 注入点在**微信写 `SnsInfo` 的那一刻**（`SQLiteDatabase.updateWithOnConflict` 的监听），
 * 改写 `attrBuf` 里的点赞列表。这是唯一能同时影响「信息流点赞行」和「详情页」的位置 ——
 * 8.0.79 的信息流不走 `SnsUtil.snsInfoToSnsStruct`，在那层 hook 不会命中。
 *
 * 两个必须遵守的点：
 * 1. **必须写昵称**。点赞行按 protobuf 的 Nickname 渲染；只写 wxId 时详情页只剩头像、
 *    信息流整行不画。
 * 2. **保留真实点赞**，只去掉上一轮自己注入的人。
 *
 * 改配置时通过 `WeMomentsApi.rewriteSnsInfoAttrBuf` 立刻写回（走 SnsInfoStorage.update），
 * 所以不需要强杀微信；[onUpdate] 只是兜底 —— 微信自己重写这条 SnsInfo 时再注入一次。
 */
@Feature(name = "伪集赞", categories = ["朋友圈"], description = "自定义朋友圈点赞用户列表")
object FakeMomentsLikes : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "FakeMomentsLikes"

    /** 持久化键：snsId → 要伪造的点赞人。 */
    private const val PREF_KEY = "fake_moments_likes"

    /** 持久化键：snsId → 上一次真正写进 attrBuf 的人（清除时靠它才能摘掉）。 */
    private const val PREF_KEY_INJECTED = "fake_moments_likes_injected"

    private val fakeLikeWxIds = mutableMapOf<Long, Set<String>>()

    /**
     * 上一轮实际写进数据库的人。
     *
     * 写入是持久的：清除配置后必须知道「上次写了谁」才摘得掉，否则那些人会永远留在点赞列表里。
     */
    private val injectedWxIds = mutableMapOf<Long, Set<String>>()

    /** 每个 snsId 只记一次注入日志，避免刷屏。 */
    private val loggedSnsIds = mutableSetOf<Long>()

    private lateinit var entryClass: Class<*>
    private lateinit var usernameField: Field
    private lateinit var nicknameField: Field
    private lateinit var parseFromMethod: Method

    override fun onEnable() {
        val resolved = runCatching {
            entryClass = SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
            // 声明顺序 [Username, Nickname, Content, ReplyUsername]（与「隐藏联系人朋友圈」同一结论，
            // 该顺序在 widget/t2 里按真实字段名被读取，可交叉验证）。
            val strings = entryClass.reflekt().fields { type = BString }.map { it.self.makeAccessible() }
            require(strings.size >= 2) { "SNS like entry has ${strings.size} string fields" }
            usernameField = strings[0]
            nicknameField = strings[1]
            parseFromMethod = SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to resolve SNS like entry fields", it)
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

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "SnsInfo") return
        runCatching {
            val snsId = values.get("snsId") as? Long ?: return@runCatching
            val raw = values.get("attrBuf") as? ByteArray ?: return@runCatching
            val fake = fakeLikeWxIds[snsId].orEmpty()
            // 既没有配置、也没有自己的注入记录时完全不动，避免无谓的重建与写盘。
            if (fake.isEmpty() && injectedWxIds[snsId].isNullOrEmpty()) return@runCatching
            values.put("attrBuf", rebuildAttrBuf(snsId, raw, fake) ?: return@runCatching)
            recordInjected(snsId, fake)
            if (fake.isNotEmpty() && loggedSnsIds.add(snsId)) {
                WeLogger.i(TAG, "写库注入 snsId=$snsId：伪点赞 ${fake.size} 个")
            }
        }.onFailure { WeLogger.e(TAG, "注入伪点赞失败", it) }
    }

    /**
     * 重建 `attrBuf`：保留真实点赞（去掉本轮要伪造的人、以及空昵称残渣），再追加带昵称的伪点赞。
     */
    private fun rebuildAttrBuf(snsId: Long, raw: ByteArray, fake: Set<String>): ByteArray? = runCatching {
        val snsObj = SnsObject()
        parseFromMethod.invoke(snsObj, raw)
        val merged = LinkedList<Any>()
        (snsObj.LikeUserList as? List<*>).orEmpty().filterNotNull().forEach { entry ->
            val username = usernameField.get(entry) as? String ?: return@forEach
            val nickname = nicknameField.get(entry) as? String
            val stale = injectedWxIds[snsId].orEmpty()
            // 去掉三类：本轮要伪造的人（去重）、上一轮注入过的人（清除时才摘得掉）、
            // 空昵称残渣（旧版本只写 wxId 留下的，微信里表现为只有头像没有名字）。
            if (username in fake || username in stale || nickname.isNullOrBlank()) return@forEach
            merged.add(entry)
        }
        fake.forEach { wxId ->
            val entry = entryClass.createInstance()
            usernameField.set(entry, wxId)
            nicknameField.set(entry, displayNameOf(wxId))
            merged.add(entry)
        }
        snsObj.LikeUserList = merged
        snsObj.LikeUserListCount = merged.size
        snsObj.LikeCount = merged.size
        snsObj.toByteArray()
    }.onFailure { WeLogger.e(TAG, "重建 attrBuf 失败", it) }.getOrNull()

    /**
     * 让朋友圈列表重新绑定一次。
     *
     * 信息流的点赞行来自列表项绑定时读到的 `SnsInfo`；我们改完库如果不重新绑定，
     * 界面还是旧数据（详情页每次都重新读，所以会出现「详情有、信息流没有」）。
     * 只对 SNS 相关 Activity 生效，且只调用列表自己的 `notifyDataSetChanged()`。
     */
    private fun refreshMomentsLists() {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val records = atClass.getDeclaredField("mActivities")
                .apply { isAccessible = true }
                .get(thread) as? Map<*, *> ?: return
            records.values.forEach { record ->
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: return@forEach
                if (!activity.javaClass.name.startsWith("com.tencent.mm.plugin.sns")) return@forEach
                activity.runOnUiThread { refreshListsIn(activity.window?.decorView) }
            }
        }.onFailure { WeLogger.w(TAG, "刷新朋友圈列表失败", it) }
    }

    private fun refreshListsIn(view: View?) {
        if (view is ViewGroup) {
            // RecyclerView / ListView 都暴露 getAdapter()，且 Adapter 上有 notifyDataSetChanged()
            runCatching {
                val adapter = view.javaClass.getMethod("getAdapter").invoke(view)
                adapter?.javaClass?.getMethod("notifyDataSetChanged")?.invoke(adapter)
            }
            runCatching { view.javaClass.getMethod("invalidateViews").invoke(view) }
            for (i in 0 until view.childCount) refreshListsIn(view.getChildAt(i))
        }
    }

    /** 记住「这次真正写进去了谁」，供下次清除时摘除。 */
    private fun recordInjected(snsId: Long, fake: Set<String>) {
        // 注意用 orEmpty() 比较：没有记录时 map[key] 是 null，直接和空集合比永远「不相等」，
        // 会在每次微信写库时都判定为变化、反复写配置（曾经一秒刷十几条）。
        if (injectedWxIds[snsId].orEmpty() == fake) return
        if (fake.isEmpty()) injectedWxIds.remove(snsId) else injectedWxIds[snsId] = fake
        saveConfig()
    }

    private fun displayNameOf(wxId: String): String =
        runCatching { WeDatabaseApi.getDisplayName(wxId) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: wxId

    // ── 配置持久化（重启微信后仍然有效）─────────────────────────────────────

    private fun loadConfig() {
        val raw = runCatching { WePrefs.getString(PREF_KEY) }.getOrNull() ?: return
        WeLogger.d(TAG, "读取伪点赞配置：$raw")
        runCatching {
            val root = JSONObject(raw)
            root.keys().forEach { key ->
                val snsId = key.toLongOrNull() ?: return@forEach
                // 兼容旧格式：早期版本存的是 {"snsId":{"fake":[...],"injected":[...]}}。
                val wxIds = root.optJSONArray(key)?.toStringSet()
                    ?: root.optJSONObject(key)?.optJSONArray("fake")?.toStringSet()
                    ?: emptySet()
                if (wxIds.isNotEmpty()) fakeLikeWxIds[snsId] = wxIds
            }
        }.onFailure { WeLogger.e(TAG, "failed to load fake likes config", it) }
        runCatching {
            val raw = WePrefs.getString(PREF_KEY_INJECTED) ?: return@runCatching
            val root = JSONObject(raw)
            root.keys().forEach { key ->
                val snsId = key.toLongOrNull() ?: return@forEach
                val wxIds = root.optJSONArray(key)?.toStringSet().orEmpty()
                if (wxIds.isNotEmpty()) injectedWxIds[snsId] = wxIds
            }
        }.onFailure { WeLogger.e(TAG, "failed to load injected map", it) }
        WeLogger.i(
            TAG,
            "伪点赞配置：${fakeLikeWxIds.size} 条动态（${fakeLikeWxIds.keys.joinToString()}），" +
                "已注入记录 ${injectedWxIds.size} 条",
        )
    }

    private fun saveConfig() {
        runCatching {
            val root = JSONObject()
            fakeLikeWxIds.forEach { (snsId, wxIds) -> root.put(snsId.toString(), JSONArray(wxIds.toList())) }
            val json = root.toString()
            WePrefs.putString(PREF_KEY, json)
            WeLogger.d(TAG, "保存伪点赞配置：$json")

            val injected = JSONObject()
            injectedWxIds.forEach { (snsId, wxIds) ->
                injected.put(snsId.toString(), JSONArray(wxIds.toList()))
            }
            WePrefs.putString(PREF_KEY_INJECTED, injected.toString())
        }.onFailure { WeLogger.e(TAG, "failed to save fake likes config", it) }
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()

    private fun setFakeLikes(snsId: Long, wxIds: Set<String>) {
        if (wxIds.isEmpty()) fakeLikeWxIds.remove(snsId) else fakeLikeWxIds[snsId] = wxIds
        saveConfig()
        // 立即写回存储层：设置/清除后不必强杀微信。失败时不影响配置，等微信下次写库再生效。
        val applied = WeMomentsApi.rewriteSnsInfoAttrBuf(snsId) { raw -> rebuildAttrBuf(snsId, raw, wxIds) }
        if (applied) recordInjected(snsId, wxIds)
        if (applied) {
            // 数据已落库，但信息流列表还握着旧的 SnsInfo（详情页每次重新读，所以只有详情会变）。
            // 主动让朋友圈的列表重新绑定一次；延时一点，等存储层把这次 update 落完。
            Handler(Looper.getMainLooper()).postDelayed({ refreshMomentsLists() }, 300)
        } else {
            WeLogger.w(TAG, "立即写回失败，下次微信写库时再生效：snsId=$snsId")
        }
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777006,
                "伪点赞",
                StarIcon,
                { _, _ -> true }
            ) { moment ->
                val contacts = WeDatabaseApi.getContacts()
                val snsInfo = moment.snsInfo!!
                val snsId = snsInfo.reflekt().getField("field_snsId", true) as Long

                val currentSelected = fakeLikeWxIds[snsId] ?: emptySet()

                showComposeDialog(moment.activity) {
                    var countInput by remember { mutableStateOf("") }

                    AlertDialogContent(
                        title = { Text("伪点赞方式") },
                        text = {
                            DefaultColumn {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        showComposeDialog(moment.activity) {
                                            ContactsSelector(
                                                title = "选择伪点赞用户",
                                                contacts = contacts,
                                                initialSelectedWxIds = currentSelected,
                                                onDismiss = onDismiss,
                                                onConfirm = { selectedWxids ->
                                                    setFakeLikes(snsId, selectedWxids)
                                                    showToast(
                                                        if (selectedWxids.isEmpty()) "已清除伪点赞配置"
                                                        else "已设置 ${selectedWxids.size} 个伪点赞"
                                                    )
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("选择指定联系人") }

                                HorizontalDivider()

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = countInput,
                                        onValueChange = { countInput = it.filter(Char::isDigit) },
                                        label = { Text("随机数量") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        val count = countInput.toIntOrNull() ?: 0
                                        if (count < 0) {
                                            showToast("请输入有效数量!")
                                            return@Button
                                        }
                                        if (count == 0) {
                                            setFakeLikes(snsId, emptySet())
                                            showToast("已清除伪点赞配置")
                                        } else {
                                            // 群聊不是点赞人，随机时排除掉
                                            val selected = contacts
                                                .filterNot { it.wxId.isGroupChatWxId }
                                                .shuffled()
                                                .take(count)
                                                .map { it.wxId }
                                                .toSet()
                                            setFakeLikes(snsId, selected)
                                            showToast("已随机设置 ${selected.size} 个伪点赞")
                                        }
                                        onDismiss()
                                    }) { Text("确定") }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton({
                                onDismiss()
                                setFakeLikes(snsId, emptySet())
                                showToast("已清除伪点赞配置")
                            }) { Text("清除") }
                        },
                        confirmButton = { TextButton(onDismiss) { Text("取消") } }
                    )
                }
            }
        )
    }

}
