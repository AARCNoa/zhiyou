package com.AARCNoa.zhiyou.ai

import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.ai.group.GroupDigestStore
import com.AARCNoa.zhiyou.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.div

/**
 * 总览里的「好友重要事件」。
 *
 * 自动回复在聊天里发现「用户需要亲自去做的事」时，通过 `report_important_event` 写进来；
 * 总览按好友列出，红点与群总结一致。**与好友档案无关**：档案记的是对方是谁，
 * 这里记的是「用户答应了什么、约好了什么」，删档案不该动这里的数据。
 *
 * 落盘在 `filesDir/ai/overview.json`，单文件 + 原子写。数据量很小（每人最多
 * [MAX_EVENTS_PER_FRIEND] 条），一次全量读写比按好友分文件省事，也不会出现「索引与单文件
 * 不一致」。
 */
@Serializable
data class OverviewIndex(
    val version: Int = CURRENT_VERSION,
    val friends: List<FriendOverview> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class FriendOverview(
    val wxId: String,
    /** 时间正序（老 → 新）。 */
    val events: List<OverviewEvent> = emptyList(),
) {
    val unread: Int get() = events.count { !it.read }

    fun markRead(): FriendOverview =
        if (events.none { !it.read }) this
        else copy(events = events.map { if (it.read) it else it.copy(read = true) })
}

@Serializable
data class OverviewEvent(
    val id: String,
    /** 记录时刻（毫秒），不是事件发生时间 —— 模型只报「现在聊到了这件事」。 */
    val at: Long,
    val title: String,
    val detail: String = "",
    val read: Boolean = false,
)

object AiOverviewStore {

    private const val TAG = "AiOverviewStore"

    /** 每人保留条数：重要事件本就稀疏，100 条足够回溯半年。 */
    private const val MAX_EVENTS_PER_FRIEND = 100

    /** 保留天数：超过这个时间的旧事件在看板上没有意义。 */
    private const val KEEP_DAYS = 180L
    private const val KEEP_MS = KEEP_DAYS * 24L * 3600_000L

    /** 同标题在这个窗口内重复提交视为同一件事（模型多轮里容易重复上报）。 */
    private const val DEDUPE_MS = 24L * 3600_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = BuildConfig.DEBUG
    }

    private val file: Path get() = AiProfileStore.root / "overview.json"

    /**
     * 进程内缓存。
     *
     * 入口红点每 400ms 问一次未读总数，面板打开时也要读一次；每次都解析 JSON 太浪费。
     * 写入只在 [appendEvent] / [markRead] / [removeFriend] 三处，写完同步更新缓存，
     * 与 [AiProfileStore] 一样不需要按 mtime 校验（同进程）。
     */
    @Volatile
    private var cached: OverviewIndex? = null

    fun load(): OverviewIndex {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val loaded = readFromDisk()
            cached = loaded
            loaded
        }
    }

    fun unreadTotal(): Int = load().friends.sumOf { it.unread }

    fun eventsOf(wxId: String): List<OverviewEvent> =
        load().friends.firstOrNull { it.wxId == wxId }?.events.orEmpty()

    /**
     * 还没被用户看过的事件。
     *
     * 入口红点按它算；提示词注入**不用**它 —— 用户点开看过后这件事就不再注入，
     * 模型于是又不知道该事记过，下一轮会重复提交。注入用 [todayEventsOf]。
     */
    fun unreadEventsOf(wxId: String): List<OverviewEvent> =
        eventsOf(wxId).filter { !it.read }

    /**
     * 今天记过的事件，不管用户看没看过。
     *
     * 这是注入给模型的集合：只要今天记过，就告诉它「别再提交」，
     * 否则用户点开看过之后，同一件事会被重新记一遍。用户手动清空后才从这里消失。
     */
    fun todayEventsOf(
        wxId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<OverviewEvent> {
        val today = AiDates.dayKey(nowMs)
        return eventsOf(wxId).filter { AiDates.dayKey(it.at) == today }
    }

    fun unreadOf(wxId: String): Int =
        load().friends.firstOrNull { it.wxId == wxId }?.unread ?: 0

    /**
     * 写一条重要事件。
     *
     * 返回 true 表示「已经存在或写入成功」—— 重复上报不是错误，调用方不需要据此报错。
     */
    @Synchronized
    fun appendEvent(
        wxId: String,
        title: String,
        detail: String = "",
        at: Long = System.currentTimeMillis(),
    ): Boolean {
        if (wxId.isBlank()) return false
        val cleanTitle = title.trim().take(TITLE_MAX)
        if (cleanTitle.isEmpty()) return false
        val cleanDetail = detail.trim().take(DETAIL_MAX)

        val current = load()
        val events = current.friends.firstOrNull { it.wxId == wxId }?.events.orEmpty()
        // 同一件事在多轮里被重复提交：窗口内同标题直接当作已记录。
        if (events.any { it.title == cleanTitle && at - it.at in 0..DEDUPE_MS }) return true

        val event = OverviewEvent(
            id = at.toString(36) + "-" + cleanTitle.hashCode().toUInt().toString(36),
            at = at,
            title = cleanTitle,
            detail = cleanDetail,
        )
        val kept = (events + event)
            .filter { it.at >= at - KEEP_MS }
            .sortedBy { it.at }
            .takeLast(MAX_EVENTS_PER_FRIEND)
        val updated = current.copy(
            friends = current.friends.filterNot { it.wxId == wxId } +
                FriendOverview(wxId = wxId, events = kept),
        )
        if (!write(updated)) return false
        cached = updated
        WeLogger.i(TAG, "重要事件已记录：$wxId · $cleanTitle")
        return true
    }

    /** 打开好友详情即清零该好友未读。返回 false 表示本来没有未读或写盘失败。 */
    @Synchronized
    fun markRead(wxId: String): Boolean {
        val current = load()
        val friend = current.friends.firstOrNull { it.wxId == wxId } ?: return false
        if (friend.events.none { !it.read }) return false
        val updated = current.copy(
            friends = current.friends.map { if (it.wxId == wxId) it.markRead() else it },
        )
        if (!write(updated)) return false
        cached = updated
        return true
    }

    /** 删档案时一并清掉该好友的未读，否则入口红点会一直亮着一个看不到的条目。 */
    @Synchronized
    fun removeFriend(wxId: String): Boolean {
        val current = load()
        if (current.friends.none { it.wxId == wxId }) return false
        val updated = current.copy(friends = current.friends.filterNot { it.wxId == wxId })
        if (!write(updated)) return false
        cached = updated
        return true
    }

    /**
     * 用户手动清空某位好友的重要事件。
     *
     * 与 [removeFriend] 行为相同，命名区分场景：这里清的是提醒，不动档案。
     * **只有用户主动点才清**，任何时候都不自动清理 —— 自动清会让模型在下一轮对话里
     * 把同一件事重新记一遍（它从聊天记录里又能判断出来），用户看到一个撤不掉的红点。
     */
    @Synchronized
    fun clearEvents(wxId: String): Boolean {
        val cleared = removeFriend(wxId)
        if (cleared) WeLogger.i(TAG, "重要事件已清空：$wxId")
        return cleared
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    private fun readFromDisk(): OverviewIndex {
        // 读文本与原子写都走 AiProfileStore 的公共实现（同目录、同进程、同一套失败日志），
        // 这里只负责「文本 → 索引」与解析失败时的兜底。
        val text = AiProfileStore.readText(file) ?: return OverviewIndex()
        return runCatching { json.decodeFromString<OverviewIndex>(text) }
            .getOrElse {
                // 解析失败按空索引处理，不覆盖原文件：下一次写入才重建。
                log("overview 解析失败：" + it.javaClass.simpleName + ": " + it.message)
                OverviewIndex()
            }
    }

    private fun write(index: OverviewIndex): Boolean =
        AiProfileStore.writeAtomic(file, json.encodeToString(index))

    private fun log(message: String) = storeLog(TAG, message)

    private const val TITLE_MAX = 40
    private const val DETAIL_MAX = 200
}

/**
 * 总览入口的显隐依据：至少一个群开着自动总结，或至少一个好友（档案就绪）开着自动回复。
 *
 * **不做轮询**：开关写入后由 [GroupDigestStore] / [AiProfileStore] 调 [refresh]，
 * 入口注册 [setListener] 后即时更新；启动初值由 [initialize] 在 IO 线程建一次。
 */
object AiOverviewEntryState {

    /** 初值先按「不显示」：没有任何来源的用户不会在冷启动时看到图标闪一下。 */
    @Volatile
    private var hasSource = false

    @Volatile
    private var listener: (() -> Unit)? = null

    val visible: Boolean get() = hasSource

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    /** 启动时在 IO 线程调一次：预热好友索引后建立初值。读失败按「有来源」处理，避免误藏。 */
    fun initialize() {
        val next = runCatching {
            AiProfileStore.warmAutoReplySet()
            val group = GroupDigestStore.hasEnabledGroup()
            val friend = AiProfileStore.anyAutoReplyOrNull() ?: true
            group || friend
        }.getOrDefault(true)
        publish(next)
    }

    /** 开关写入后调用；只读内存索引，可在任意线程。好友索引未预热时保持现状。 */
    fun refresh() {
        val next = runCatching {
            if (GroupDigestStore.hasEnabledGroup()) true
            else AiProfileStore.anyAutoReplyOrNull() ?: return
        }.getOrElse { return }
        publish(next)
    }

    private fun publish(next: Boolean) {
        if (next == hasSource) return
        hasSource = next
        listener?.invoke()
    }
}
