package com.AARCNoa.zhiyou.activity.settings

import com.AARCNoa.zhiyou.features.api.core.WeConversationApi
import com.AARCNoa.zhiyou.features.api.core.WeDatabaseApi
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.features.items.contacts.HideContacts

/**
 * 密友功能的状态诊断——只采集，不管展示。
 *
 * 为什么只有这几项：总开关、隐藏联系人、三个拦截开关本身就是功能列表里的可开关行，
 * 在诊断里再列一遍就是同一状态两处维护。这里只收「设置页里看不到、出问题时又必须看」
 * 的只读状态（名单人数、临时显示、锚点解析情况等）。
 *
 * 全部是内存与偏好读取（[ModuleDiagnostics.anchors] 读 DexKit 缓存，不重扫），
 * 可以安全地在组合期调用。
 */
internal data class DiagItem(
    val label: String,
    val value: String,
    val ok: Boolean,
    val detail: String,
)

/** 采集一次完整状态。 */
internal fun hiddenContactsDiag(): List<DiagItem> = buildList {
    val count = runCatching { HideContacts.hiddenContacts.size }.getOrDefault(0)
    add(
        DiagItem(
            label = "名单人数",
            value = "$count 人",
            ok = count > 0,
            detail = if (count > 0) "当前隐藏名单共 $count 人。"
            else "名单为空——总开关开着也不会有任何隐藏效果，先在「隐藏联系人」里添加。",
        )
    )

    val temp = runCatching { HideContacts.isTemporarilyShown }.getOrDefault(false)
    add(
        DiagItem(
            label = "临时显示",
            value = if (temp) "生效中" else "未激活",
            ok = !temp,
            detail = "生效期间名单内所有人恢复可见；用口令恢复或到期自动结束。",
        )
    )

    val triple = runCatching { HideContacts.tripleClickTitle }.getOrDefault(false)
    add(
        DiagItem(
            label = "三击标题切换",
            value = if (triple) "开启" else "关闭",
            ok = true,
            detail = "连续三击主页顶部标题栏可临时显隐。这是按需项，关闭不算异常。",
        )
    )

    val cmd = runCatching { HideContacts.searchCommandText }.getOrDefault("#wm#")
    val minutes = runCatching { HideContacts.searchCommandMinutes }.getOrDefault(30)
    add(
        DiagItem(
            label = "搜索框临时显示",
            value = if (cmd.isBlank()) "未设置" else cmd,
            ok = cmd.isNotBlank(),
            detail = if (cmd.isBlank()) "未设置指令，无法从搜索框临时显示。"
            else "在微信主页搜索框输入「$cmd」可临时显示 $minutes 分钟。",
        )
    )

    // 同样先主动初始化一次：否则微信刚启动、还没碰数据库时这里会显示「未就绪」。
    val dbReady = runCatching { WeDatabaseApi.ensureDatabase() }.getOrDefault(false)
    add(
        DiagItem(
            label = "微信数据库",
            value = if (dbReady) "已就绪" else "未就绪",
            ok = dbReady,
            detail = "会话列表 / 通讯录 / 标签的过滤都依赖数据库查询改写；未就绪时这些隐藏面不生效，等微信启动完成后自动恢复。",
        )
    )

    addAll(anchorDiag())
}

/**
 * 锚点自检：类名探测 + DexKit 解析状态。
 *
 * 用来区分「开关没开」和「当前微信版本把类改了」——后者再怎么开关都没用。
 */
private fun anchorDiag(): List<DiagItem> {
    val out = mutableListOf<DiagItem>()
    val loader = HideContacts::class.java.classLoader

    listOf(
        Triple("聊天页", "com.tencent.mm.ui.chatting.ChattingUI", "onCreate"),
        Triple("资料页", "com.tencent.mm.plugin.profile.ui.ContactInfoUI", "onCreate"),
        Triple("通讯录", "com.tencent.mm.ui.contact.address.AddressLiveList", null),
        Triple("wcdb", "com.tencent.wcdb.database.SQLiteDatabase", null),
        Triple("wcdb compat", "com.tencent.wcdb.compat.SQLiteDatabase", null),
    ).forEach { (label, className, method) ->
        val clazz = runCatching { Class.forName(className, false, loader) }.getOrNull()
        val methodOk = method == null || runCatching {
            clazz?.declaredMethods?.any { it.name == method } == true
        }.getOrDefault(false)
        out += DiagItem(
            label = "锚点 · $label",
            value = when {
                clazz == null -> "未找到"
                !methodOk -> "方法缺失"
                else -> "已找到"
            },
            ok = clazz != null && methodOk,
            detail = when {
                clazz == null -> "当前微信版本里这个类已改名或挪包，依赖它的隐藏/拦截会退化，需要适配（无法自动修复）。"
                !methodOk -> "类在，但 $method 入口不存在——微信可能改了调用方式，拦截多半不生效。"
                else -> "类与方法在当前版本可用。"
            },
        )
    }

    // DexKit 解析状态：判定「开关开了但 Hook 没装上」卡在哪一步。
    //
    // 只取「密友功能」分类下的功能：名字里带「联系人 / 隐藏」的功能不止密友那几个，
    // 按名字模糊匹配会把「自动同意好友申请」这类别处的锚点也算进密友诊断。
    // 判定口径与自检页一致——已登记「旧路径被新实现替代」、或微信整体移除了实现的
    // 锚点，解析不到属于预期，不算异常（否则 8.0.80 上会长期挂着一条红字）。
    val anchors = runCatching {
        ModuleDiagnostics.anchors { _, _, categories ->
            SwitchFeature.CATEGORY_WITH_MASTER_SWITCH in categories
        }
    }.getOrDefault(emptyList())
    if (anchors.isNotEmpty()) {
        val okCount = anchors.count { it.resolved }
        val pending = anchors.filter { it.needsAdaptation() }
        val explained = anchors.size - okCount - pending.size
        out += DiagItem(
            label = "DexKit 锚点",
            value = if (pending.isEmpty()) "全部可用" else "$okCount/${anchors.size}",
            ok = pending.isEmpty(),
            detail = when {
                pending.isNotEmpty() ->
                    "未解析：${pending.joinToString("、") { it.key }}。" +
                        "这些锚点即使功能开关是开的，对应隐藏/拦截也不会生效。"
                explained > 0 ->
                    "相关锚点全部可用；另有 $explained 个旧路径锚点在当前版本已由新实现覆盖，不影响功能。"
                else -> "隐藏联系人相关功能的 Dex 锚点全部解析成功。"
            },
        )
    }
    return out
}

/** 刷新微信会话列表，让隐藏/恢复立即生效。 */
internal fun refreshHiddenContactsConversations(): String =
    runCatching { WeConversationApi.reloadConversations() }
        .fold({ "会话列表已刷新" }, { "刷新失败：" + (it.message ?: it.javaClass.simpleName) })

/** 复制到剪贴板的纯文本（反馈问题用）。 */
internal fun diagPlainText(items: List<DiagItem>): String =
    items.joinToString("\n") { (if (it.ok) "✅ " else "❌ ") + it.label + "：" + it.value }
