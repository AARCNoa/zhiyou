package com.AARCNoa.zhiyou.features.items.chat

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.DexMethodDelegate
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.api.core.models.MessageInfo
import com.AARCNoa.zhiyou.features.api.ui.WeConversationListViewApi
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.TargetProcesses
import com.AARCNoa.zhiyou.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt

/** 屏蔽群聊 @全体成员 的系统通知和会话列表标记，保留消息及单独 @当前用户 的提醒。 */
@Feature(
    name = "屏蔽@全体成员提醒",
    categories = ["聊天"],
    description = "屏蔽 @全体成员 的系统通知和会话列表标记，保留群消息及单独 @我",
)
object BlockAllMentionNotifications : SwitchFeature(), IResolveDex {

    private const val TAG = "BlockAtAll"
    private const val NOTIFY_ALL = "notify@all"

    override val targetProcessMask get() = TargetProcesses.PROC_MAIN or TargetProcesses.PROC_PUSH

    /** push 进程常驻轻量 hook，并直接读取跨进程偏好，让主进程开关及时生效。 */
    override val shouldEnableOnStartup: Boolean
        get() = super.shouldEnableOnStartup || TargetProcesses.currentType == TargetProcesses.PROC_PUSH

    // 8.0.80 起入库方法多一个 boolean；两个形态共用同一条日志锚点。
    private val methodInsertMessageShort by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("Error insert message msg:%s talker:%s")
            paramCount(2)
            returnType("long")
        }
    }

    private val methodInsertMessageLong by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("Error insert message msg:%s talker:%s")
            paramCount(3)
            returnType("long")
        }
    }

    private val methodInsertMessage: DexMethodDelegate
        get() = if (!methodInsertMessageLong.isPlaceholder) methodInsertMessageLong else methodInsertMessageShort

    override val supersededAnchors: Map<String, String>
        get() = if (methodInsertMessageLong.isPlaceholder) {
            mapOf(
                "BlockAllMentionNotifications:methodInsertMessageLong" to
                    "8.0.79 及更早的入库方法由 methodInsertMessageShort 覆盖"
            )
        } else {
            mapOf(
                "BlockAllMentionNotifications:methodInsertMessageShort" to
                    "8.0.80 起入库方法由 methodInsertMessageLong 覆盖"
            )
        }

    /** 微信原生 msgSource 节点合并方法，同时负责把消息标记为待重新序列化。 */
    private val methodMergeMsgSourceNode by dexMethod {
        matcher {
            usingEqStrings("(?s)<alnode[^>]*>.*?</alnode>")
            paramCount(3)
            returnType("void")
        }
    }

    /** 前台/普通通知路径：args[1] 为群聊 ID，args[2] 为通知展示文本。 */
    private val methodDealNotify by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    /** 后台 LightPush 通知路径，args[5] 是解析后的 msgSource Map。 */
    private val methodLightPush by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(7)
            usingEqStrings("notifyForLightPush push:isShake: %B, isSound: %B ctrlFlag:%s")
        }
    }

    private val cdataAtUserList = Regex(
        "(<atuserlist(?:\\s[^>]*)?>\\s*<!\\[CDATA\\[)(.*?)(\\]\\]>\\s*</atuserlist>)",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val plainAtUserList = Regex(
        "(<atuserlist(?:\\s[^>]*)?>\\s*)(.*?)(\\s*</atuserlist>)",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val conversationAllLabel = Regex("\\[(?:@所有人|@全体成员|@全体)\\]\\s*")

    private val conversationBindListener = WeConversationListViewApi.IBindViewListener { _, row, _, _ ->
        if (isFeatureEnabled()) stripConversationAllLabel(row)
    }

    override fun onEnable() {
        if (TargetProcesses.isInMain) {
            WeConversationListViewApi.addListener(conversationBindListener)
            WeConversationListViewApi.refresh()
            methodInsertMessage.hookBefore {
                if (!isFeatureEnabled()) return@hookBefore
                val raw = args.getOrNull(0) ?: return@hookBefore
                val msgInfo = runCatching { MessageInfo(raw) }.getOrNull() ?: return@hookBefore
                if (msgInfo.isSend != 0 || !msgInfo.isInGroupChat) return@hookBefore
                val msgSource = findMessageSource(raw).orEmpty()
                val updatedNode = updatedAtUserListNode(msgSource) ?: return@hookBefore
                runCatching { methodMergeMsgSourceNode.method.invoke(null, raw, updatedNode, false) }
                    .onFailure { WeLogger.w(TAG, "写回消息提醒标记失败", it) }
            }
        }

        methodDealNotify.hookBefore {
            if (!isFeatureEnabled()) return@hookBefore
            val talker = args.getOrNull(1) as? String ?: return@hookBefore
            if (!talker.endsWith("@chatroom")) return@hookBefore
            val content = args.getOrNull(2) as? String ?: return@hookBefore
            if (!content.contains("@所有人")) return@hookBefore
            result = null
            WeLogger.i(TAG, "已屏蔽 @全体成员 的普通通知提醒")
        }

        methodLightPush.hookBefore {
            if (!isFeatureEnabled()) return@hookBefore
            val tokens = extractMentionTokens(args.getOrNull(5))
            if (NOTIFY_ALL !in tokens) return@hookBefore

            val selfWxId = selfWxId()
            if (selfWxId.isNotEmpty() && selfWxId in tokens) return@hookBefore
            // 拿不到当前 wxid 时采取保守策略：混合提及可能包含单独 @我，不直接取消。
            if (selfWxId.isEmpty() && tokens.any { it != NOTIFY_ALL }) return@hookBefore

            result = null
            WeLogger.i(TAG, "已屏蔽 @全体成员 的 LightPush 提醒")
        }
        installSystemNotificationHooks()
    }

    override fun onDisable() {
        if (TargetProcesses.isInMain) {
            WeConversationListViewApi.removeListener(conversationBindListener)
            WeConversationListViewApi.refresh()
        }
    }

    private fun isFeatureEnabled(): Boolean =
        runCatching { WePrefs.getBoolOrDef(name, _isEnabled) }.getOrDefault(_isEnabled)

    /** 最终系统发布出口，覆盖未经过 dealNotify/LightPush 的通知路径。 */
    private fun installSystemNotificationHooks() {
        NotificationManager::class.java.declaredMethods
            .filter { it.name == "notify" && it.parameterTypes.any { type -> type == Notification::class.java } }
            .forEach { method ->
                method.hookBefore {
                    if (!isFeatureEnabled()) return@hookBefore
                    val notification = args.filterIsInstance<Notification>().firstOrNull() ?: return@hookBefore
                    if (!notification.hasAllMentionText()) return@hookBefore
                    result = null
                    WeLogger.i(TAG, "已在系统通知发布前屏蔽 @全体成员：process=${TargetProcesses.currentType}")
                }
            }
    }

    private fun Notification.hasAllMentionText(): Boolean {
        val extras = extras ?: return false
        return listOf(
            tickerText,
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        ).any { it?.contains("@所有人") == true }
    }

    private fun stripConversationAllLabel(view: View) {
        if (view is TextView) {
            val original = view.text?.toString().orEmpty()
            val cleaned = conversationAllLabel.replace(original, "")
            if (cleaned != original) {
                view.text = cleaned
                WeLogger.i(TAG, "已移除会话列表的 @全体成员 标记")
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) stripConversationAllLabel(view.getChildAt(index))
        }
    }

    /** 混淆版本没有保留 field_msgSource 名称，按消息字段内容定位真正的 msgSource。 */
    private fun findMessageSource(raw: Any): String? {
        var type: Class<*>? = raw.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { field ->
                if (field.type != String::class.java) return@forEach
                val value = runCatching {
                    field.isAccessible = true
                    field.get(raw) as? String
                }.getOrNull()
                if (value?.contains(NOTIFY_ALL) == true && value.contains("atuserlist")) return value
            }
            type = type.superclass
        }
        return null
    }

    /** 返回只移除了 notify@all 的 atuserlist 节点；单独 @我的 token 和其他 XML 节点原样保留。 */
    private fun updatedAtUserListNode(xml: String): String? {
        var updatedNode: String? = null
        fun replacement(match: MatchResult): String {
            val tokens = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (NOTIFY_ALL !in tokens) return match.value
            val remaining = tokens.filterNot { it == NOTIFY_ALL }.joinToString(",")
            return (match.groupValues[1] + remaining + match.groupValues[3]).also { updatedNode = it }
        }

        cdataAtUserList.replace(xml, ::replacement)
        if (updatedNode == null) plainAtUserList.replace(xml, ::replacement)
        return updatedNode
    }

    /** 同时兼容 `.msgsource.atuserlist` 扁平键和嵌套 Map，解析失败时放行通知。 */
    private fun extractMentionTokens(msgSource: Any?): Set<String> {
        val tokens = mutableSetOf<String>()

        fun addTokens(raw: String) {
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(tokens::add)
        }

        fun collect(node: Any?, depth: Int) {
            if (node !is Map<*, *> || depth > 4) return
            for ((key, value) in node) {
                val name = key?.toString()?.lowercase().orEmpty()
                when {
                    name.contains("atuserlist") && value is CharSequence -> addTokens(value.toString())
                    name.contains("msgsource") && value is String -> {
                        var foundCdata = false
                        cdataAtUserList.findAll(value).forEach {
                            foundCdata = true
                            addTokens(it.groupValues[2])
                        }
                        if (!foundCdata) {
                            plainAtUserList.findAll(value).forEach { addTokens(it.groupValues[2]) }
                        }
                    }
                    value is Map<*, *> -> collect(value, depth + 1)
                }
            }
        }

        collect(msgSource, 0)
        return tokens
    }

    private fun selfWxId(): String = runCatching {
        HostInfo.application
            .getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            .getString("login_weixin_username", "")
            .orEmpty()
    }.getOrDefault("")
}
