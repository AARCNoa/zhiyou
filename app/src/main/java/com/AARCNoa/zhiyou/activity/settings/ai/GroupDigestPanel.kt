package com.AARCNoa.zhiyou.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.AARCNoa.zhiyou.ai.AiDates
import com.AARCNoa.zhiyou.ai.AiDates.timeOf
import com.AARCNoa.zhiyou.ai.AiOverviewStore
import com.AARCNoa.zhiyou.ai.AiProfileState
import com.AARCNoa.zhiyou.ai.AiProfileStore
import com.AARCNoa.zhiyou.ai.displayNameOrId
import com.AARCNoa.zhiyou.ai.AiProfileSummary
import com.AARCNoa.zhiyou.ai.AiTodaySummary
import com.AARCNoa.zhiyou.ai.OverviewEvent
import com.AARCNoa.zhiyou.ai.TodaySummary
import com.AARCNoa.zhiyou.ai.group.GroupDigestConfig
import com.AARCNoa.zhiyou.ai.group.GroupDigestStore
import com.AARCNoa.zhiyou.ai.group.GroupReport
import com.AARCNoa.zhiyou.ai.group.GroupSummary
import com.AARCNoa.zhiyou.ui.content.ScrimDialog
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.utils.android.copyToClipboard
import com.AARCNoa.zhiyou.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「总览」面板的内容：今日总结 + 好友 + 群聊。
 *
 * 壳（遮罩 / 300dp / 滑入 / 生命周期）由 `HomeSidePanelFeature.buildPanel` 提供，
 * 不要求「微信主页侧边栏」开关打开；这里只画内容。两块数据源互不相干，但放在同一张看板上：
 * - 好友：有档案的好友全部列出。开了自动回复的显示最近一条约定，没开的一行说明；
 *   有未读即红点，点进详情是编号列表，进入即清零。
 * - 群聊：群总结卡片，点进历史摘要。
 */
@Composable
fun GroupDigestPanel() {
    var friends by remember { mutableStateOf<List<FriendRow>>(emptyList()) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }

    var openFriendId by remember { mutableStateOf<String?>(null) }
    var openEvents by remember { mutableStateOf<List<OverviewEvent>>(emptyList()) }
    var openGroupId by remember { mutableStateOf<String?>(null) }
    var openConfig by remember { mutableStateOf<GroupDigestConfig?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var today by remember { mutableStateOf<TodaySummary?>(null) }
    var todayLoading by remember { mutableStateOf(true) }
    /**
     * 今日总结是否展开。
     *
     * 默认收起：面板顶部只有一行标题，不占地方。只有「今天第一次整理」那一次展开，
     * 让用户看到生成过程与结果；之后每次打开面板都是收起的。
     */
    var todayExpanded by remember { mutableStateOf(false) }
    /** 每次 +1 重算一次；配合 [todayForce] 区分「内容变了」与用户点「重新整理」。 */
    var todayNonce by remember { mutableStateOf(0) }
    var todayForce by remember { mutableStateOf(false) }

    /** 「好友」「群聊」两个分区默认展开：打开总览就要先看到内容。 */
    var friendsExpanded by remember { mutableStateOf(true) }
    var groupsExpanded by remember { mutableStateOf(true) }

    /**
     * 「未开启自动回复」那一组默认收起。
     *
     * 里面每一行只有一个占位说明，没有事件可看；开着会把真正有约定的好友往下推，
     * 面板又窄，滚两屏才见到有用的一行。
     */
    var idleFriendsExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            friends = AiProfileStore.loadIndex().profiles.map { summary ->
                val events = AiOverviewStore.eventsOf(summary.wxId)
                FriendRow(
                    summary = summary,
                    lastEvent = events.lastOrNull(),
                    unread = AiOverviewStore.unreadOf(summary.wxId),
                )
            }
            groups = GroupDigestStore.loadIndex().groups
        }
    }

    // 今日总结单独走一遍：要发请求，不能和列表读取挤在同一个 LaunchedEffect 里。
    LaunchedEffect(todayNonce) {
        todayLoading = true
        // 今天还没有落盘过 → 这一次算「第一次整理」，展开给用户看结果；之后默认收起。
        if (todayNonce == 0) {
            todayExpanded = withContext(Dispatchers.IO) { AiTodaySummary.cached() == null }
        }
        today = withContext(Dispatchers.IO) { AiTodaySummary.ensure(force = todayForce) }
        todayLoading = false
        todayForce = false
    }

    LaunchedEffect(openFriendId) {
        val id = openFriendId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            AiOverviewStore.markRead(id)
            openEvents = AiOverviewStore.eventsOf(id)
            // 清完未读就地更新那一行，不必重读整份索引。
            friends = friends.map { if (it.summary.wxId == id) it.copy(unread = 0) else it }
        }
    }

    LaunchedEffect(openGroupId) {
        val id = openGroupId ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            GroupDigestStore.markRead(id)
            openConfig = GroupDigestStore.readConfig(id)
            groups = GroupDigestStore.loadIndex().groups
        }
    }

    val openFriend = friends.firstOrNull { it.summary.wxId == openFriendId }
    val openGroup = groups.firstOrNull { it.groupId == openGroupId }
    val inDetail = openFriendId != null || openGroupId != null

    Column(
        Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackground())
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inDetail) {
                Text(
                    text = "‹ 返回",
                    fontSize = 14.sp,
                    color = WxPalette.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            openFriendId = null
                            openEvents = emptyList()
                            openGroupId = null
                            openConfig = null
                        }
                        .padding(8.dp),
                )
                Text(
                    text = openFriend?.summary?.displayNameOrId ?: openGroup?.name.orEmpty(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 用标题吃满中间：不能再叠一个 Spacer(weight)，两个 weight 并存时
                    // 标题短（好友备注名）会留出右侧空白，把「关闭」推离右边缘。
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            } else {
                Text(
                    text = "总览",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            // 关闭走点右侧遮罩或右滑：标题栏只留标题，窄面板里少一个按钮少一处拥挤。
        }

        when {
            openFriendId != null -> FriendEventsPane(
                friend = openFriend,
                events = openEvents,
                onClear = { confirmingClear = true },
            )
            openGroupId != null -> HistoryPane(config = openConfig)
            else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                TodaySummarySection(
                    summary = today,
                    loading = todayLoading,
                    expanded = todayExpanded,
                    onToggle = { todayExpanded = !todayExpanded },
                    onRefresh = {
                        // 手动整理要让人看到结果：当场展开，整理完保持展开。
                        todayExpanded = true
                        todayForce = true
                        todayNonce++
                    },
                )
                if (friends.isEmpty() && groups.isEmpty()) {
                    Text(
                        text = "总览还是空的。\n在 AI 页给好友建档、在群组里添加群之后，他们的约定与摘要会出现在这里。",
                        fontSize = 13.sp,
                        color = WxPalette.textSecondary(),
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    // 分两段：先看有内容的，占位的收进折叠组。
                    val (idleFriends, activeFriends) = friends.partition { it.idle }
                    if (friends.isNotEmpty()) {
                        SectionHeader(
                            text = "好友",
                            expanded = friendsExpanded,
                            onToggle = { friendsExpanded = !friendsExpanded },
                        )
                        if (friendsExpanded) {
                            activeFriends.forEach { friend ->
                                PanelFriendRow(
                                    friend = friend,
                                    onClick = { openFriendId = friend.summary.wxId },
                                )
                            }
                            if (idleFriends.isNotEmpty()) {
                                FoldHeader(
                                    text = "未开启自动回复",
                                    count = idleFriends.size,
                                    expanded = idleFriendsExpanded,
                                    onToggle = { idleFriendsExpanded = !idleFriendsExpanded },
                                )
                                if (idleFriendsExpanded) {
                                    idleFriends.forEach { friend ->
                                        PanelFriendRow(
                                            friend = friend,
                                            onClick = { openFriendId = friend.summary.wxId },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (groups.isNotEmpty()) {
                        SectionHeader(
                            text = "群聊",
                            expanded = groupsExpanded,
                            onToggle = { groupsExpanded = !groupsExpanded },
                        )
                        if (groupsExpanded) {
                            groups.forEach { group ->
                                PanelGroupRow(group = group, onClick = { openGroupId = group.groupId })
                            }
                        }
                    }
                }
            }
        }
    }

    // 清空要二次确认：这是唯一会丢掉提醒的操作，误点一次当天就再也看不到这些事了。
    val clearId = openFriendId
    if (confirmingClear && clearId != null) {
        val name = openFriend?.summary?.let { it.remark.ifBlank { it.nickname } }.orEmpty()
        ClearEventsDialog(
            name = name.ifBlank { "这位好友" },
            count = openEvents.size,
            onDismiss = { confirmingClear = false },
            onConfirm = {
                confirmingClear = false
                scope.launch {
                    withContext(Dispatchers.IO) { AiOverviewStore.clearEvents(clearId) }
                    openEvents = emptyList()
                    // 就地更新那一行：副标题回到「暂无重要事件」，红点清零。
                    friends = friends.map {
                        if (it.summary.wxId == clearId) {
                            it.copy(lastEvent = null, unread = 0)
                        } else {
                            it
                        }
                    }
                    // 今日总结的输入变了，重新算一次（签名已不同，不会命中缓存）。
                    todayNonce++
                }
            },
        )
    }
}

/** 清空提醒的确认框。 */
@Composable
private fun ClearEventsDialog(
    name: String,
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ScrimDialog(show = true, onDismissRequest = onDismiss, title = "清空提醒") {
        Text(
            text = "将清空「$name」的 $count 条重要事件。清空后 agent 不会再知道这些事已经记过，" +
                "对方再提到时会重新记录。好友档案不受影响。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 19.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AiSecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            AiDangerButton(text = "清空", onClick = onConfirm, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 今日总结：一行标题 + 一条分隔线，展开后正文直接排在下面。
 *
 * 不套卡片：侧边栏本来就窄，卡片再吃掉一层边距与圆角，展开后正文的可读宽度只剩一半。
 * 分隔线与好友、群聊用同一种实线，整块看起来是面板里的一个分区，而不是浮在上面的卡。
 */
@Composable
private fun TodaySummarySection(
    summary: TodaySummary?,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 整行都能点开：只靠右边两个字在小屏上不好点。
                // end = 10dp 让「展开」的文字右缘落在 16dp，与下方分隔线对齐。
                .clickable { onToggle() }
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "今日总结",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textSecondary(),
            )
            Spacer(Modifier.weight(1f))
            if (loading) {
                Text(
                    text = "整理中…",
                    fontSize = 12.sp,
                    color = WxPalette.textTertiary(),
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            } else {
                SummaryAction(text = "整理", onClick = onRefresh)
            }
            Spacer(Modifier.width(2.dp))
            ExpandToggle(expanded = expanded, onToggle = onToggle)
        }
        AiDivider(startPadding = 16.dp, endPadding = 16.dp)
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (summary != null) {
                    // 不单列标题：正文就是整理结果。模型只给了一句话、没有要点与待办时，
                    // 把那句话当正文，避免展开后一片空白。
                    if (summary.points.isEmpty() && summary.todos.isEmpty()) {
                        Text(
                            text = summary.headline,
                            fontSize = 13.sp,
                            color = WxPalette.textSecondary(),
                            lineHeight = 19.sp,
                        )
                    }
                    // 本地兜底给的是原始记录行，本身已带分隔符，再加「· 」会变成两层顿号。
                    val local = summary.source == AiTodaySummary.SOURCE_LOCAL
                    summary.points.forEach { point ->
                        Text(
                            text = if (local) point else "· $point",
                            fontSize = 12.sp,
                            color = WxPalette.textSecondary(),
                            lineHeight = 18.sp,
                        )
                    }
                    if (summary.todos.isNotEmpty()) {
                        Text(
                            text = "还没完成",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = WxPalette.textSecondary(),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                        summary.todos.forEach { todo ->
                            Text(
                                text = "· $todo",
                                fontSize = 12.sp,
                                color = WxPalette.textSecondary(),
                                lineHeight = 18.sp,
                            )
                        }
                    }
                    Text(
                        text = todayFooter(summary),
                        fontSize = 10.sp,
                        color = WxPalette.textTertiary(),
                    )
                } else if (loading) {
                    Text(
                        text = "正在整理今天的内容…",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                } else {
                    Text(
                        text = "今天还没有记录。",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
            }
        }
    }
}

/** 标题行右侧的文字操作：不带背景，靠颜色与间距区分，避免在窄面板里出现一排按钮。 */
@Composable
private fun SummaryAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = WxPalette.textSecondary(),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 展开 / 收起：向上箭头 = 收起，向下箭头 = 展开。
 *
 * 标题行本来只有 300dp 宽，两个字的中文按钮和「整理」并排会把标题挤到省略号；
 * 换成箭头只占一个图标位，方向本身就是含义。点击区域仍按 20dp 图标 + 6dp 内边距算。
 */
@Composable
private fun ExpandToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    /** 箭头颜色跟所在标题行一致：分区标题用次级色，「未开启自动回复」用三级色。 */
    tint: Color = WxPalette.textSecondary(),
) {
    Icon(
        imageVector = if (expanded) {
            OutlinedIcons.Expand_less
        } else {
            OutlinedIcons.Expand_more
        },
        contentDescription = if (expanded) "收起" else "展开",
        tint = if (enabled) tint else WxPalette.textTertiary(),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .size(20.dp),
    )
}

/** 「3 位好友 · 2 个群 · 14:20」：为 0 的一段不出现，避免「0 个群」这种噪声。 */
private fun todayFooter(summary: TodaySummary): String = buildList {
    if (summary.friendCount > 0) add("${summary.friendCount} 位好友")
    if (summary.groupCount > 0) add("${summary.groupCount} 个群")
    if (summary.at > 0) add(AiDates.hhmm(summary.at))
}.joinToString(" · ")

/**
 * 一行好友：只带渲染需要的三个值，避免在 Composable 里反复读 store。
 *
 * [idle] = 档案已建好、但没开自动回复。这类人在总览里收进折叠组：
 * 他们没有事件可看，平铺着只会把真正有约定的好友往下推。
 */
private data class FriendRow(
    val summary: AiProfileSummary,
    val lastEvent: OverviewEvent?,
    val unread: Int,
) {
    val idle: Boolean
        get() = summary.state == AiProfileState.READY && !summary.autoReply
}

/**
 * 分区标题（好友 / 群聊）：整行可点，右侧「展开 / 收起」。
 *
 * 排版与今日总结、工作汇报用的是同一套 —— 左边小字标签、右边两个小字操作，
 * 页面里所有能折叠的标题长得一样，不用记哪里能点。
 */
@Composable
private fun SectionHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.textTertiary(),
        )
        Spacer(Modifier.weight(1f))
        ExpandToggle(expanded = expanded, onToggle = onToggle)
    }
}

/**
 * 折叠组标题（未开启自动回复）。
 *
 * 后面挂条数：收起时也能看出里面还有几位好友，不会以为被删掉了；
 * 组名不带「好友」二字，因为它后面一行的顺序已经说明这是好友。
 */
@Composable
private fun FoldHeader(
    text: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count > 0) "$text · $count" else text,
            fontSize = 12.sp,
            color = WxPalette.textTertiary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 用 weight 占满中间，不再叠一个 Spacer(weight)：两个 weight 并存时
            // 文字只拿到一半宽，字号调大后「未开启自动回复 · 12」会被提前截断。
            modifier = Modifier.weight(1f),
        )
        // 组名是三级色，箭头跟着它走：这一行是「占位的次级分组」，比好友/群聊标题再淡一档。
        ExpandToggle(
            expanded = expanded,
            onToggle = onToggle,
            tint = WxPalette.textTertiary(),
        )
    }
}

@Composable
private fun PanelFriendRow(friend: FriendRow, onClick: () -> Unit) {
    val summary = friend.summary
    val name = summary.displayNameOrId
    // 档案还在整理时先报进度：「未开启自动回复」会让人以为开关丢了。
    val subtitle = when {
        summary.state != AiProfileState.READY -> summary.subtitle
        !summary.autoReply -> "未开启自动回复"
        friend.lastEvent != null -> timeOf(friend.lastEvent.at) + " · " + friend.lastEvent.title
        else -> "暂无重要事件"
    }
    val dim = friend.idle

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarWithUnread(
                avatarUrl = summary.avatarUrl,
                name = name,
                unread = friend.unread,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (dim) WxPalette.textTertiary() else WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        AiDivider(startPadding = 68.dp)
    }
}

/**
 * 好友详情：重要事件按最新在上编号。
 *
 * 编号只表示阅读顺序，不表示优先级 —— 模型不判断轻重，硬排优先级会让用户以为
 * 第 1 条更急。时间写在标题下面，细节接在时间后面，一条一段。
 */
@Composable
private fun FriendEventsPane(
    friend: FriendRow?,
    events: List<OverviewEvent>,
    onClear: () -> Unit,
) {
    if (friend == null) {
        Text(
            text = "载入中…",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    if (!friend.summary.autoReply) {
        Text(
            text = "这位好友没有开启自动回复。\n开启后，聊天里聊定的事会自动记到这里。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            lineHeight = 20.sp,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    if (events.isEmpty()) {
        Text(
            text = "暂无重要事件。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // 条数 + 清空放顶部：不用滚到最底才找到入口，也避免误触（清空另有确认框）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${events.size} 条",
                fontSize = 11.sp,
                color = WxPalette.textTertiary(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "清空",
                fontSize = 12.sp,
                color = WxPalette.Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClear() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        events.asReversed().forEachIndexed { index, event ->
            Row(Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = "${index + 1}.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.Primary,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = event.title,
                        fontSize = 14.sp,
                        color = WxPalette.textPrimary(),
                        lineHeight = 20.sp,
                    )
                    Text(
                        text = timeOf(event.at),
                        fontSize = 11.sp,
                        color = WxPalette.textTertiary(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (event.detail.isNotBlank()) {
                        Text(
                            text = event.detail,
                            fontSize = 12.sp,
                            color = WxPalette.textSecondary(),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            AiDivider(startPadding = 22.dp)
        }
    }
}

@Composable
private fun PanelGroupRow(group: GroupSummary, onClick: () -> Unit) {
    val (subtitle, danger) = groupSubtitle(group, preferHeadline = true)
    GroupSummaryRow(
        group = group,
        subtitle = subtitle,
        danger = danger,
        onClick = onClick,
    )
}

/** 群行右侧的小字操作：没有底圈，与今日总结的「整理 / 展开」同一套。 */
@Composable
private fun RowAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = if (enabled) WxPalette.Primary else WxPalette.textTertiary(),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

private fun copyReport(context: Context, text: String) {
    if (copyToClipboard(context, text, "工作汇报")) {
        showToast("已复制汇报")
    } else {
        showToast("复制失败")
    }
}

@Composable
private fun HistoryPane(config: GroupDigestConfig?) {
    if (config == null) {
        Text(
            text = "载入中…",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    if (config.digests.isEmpty()) {
        Text(
            text = "还没有摘要。",
            fontSize = 13.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(config.groupId) { mutableStateOf(false) }
    var report by remember(config.groupId, config.report) { mutableStateOf(config.report) }
    var loading by remember(config.groupId) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // 工作群把当前任务钉在历史最上方：翻旧摘要时先看到的是「现在推进到哪」。
        // 「汇报 / 展开」也放在这一行——它是「看整个项目」的入口，属于摘要列表，不属于总览卡片。
        if (config.workGroup) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = taskLabel(config.currentTask, config.currentProgress).ifBlank { "工作群" },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RowAction(
                    text = if (loading) "整理中" else "汇报",
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = withContext(Dispatchers.IO) {
                                // 已有汇报时再点就是「重新汇报」：否则缓存命中、界面一个像素都不变，
                                // 看起来像按钮点不动。第一次仍然先吃缓存，避免白花一次请求。
                                GroupReport.ensure(config.groupId, force = report.isNotBlank())
                            }
                            loading = false
                            val text = result.report
                            if (text.isNullOrBlank()) {
                                showToast(result.error ?: "汇报生成失败")
                            } else {
                                val updated = report.isNotBlank()
                                report = text
                                // 生成完直接展开：按钮点下去就是为了看内容。
                                expanded = true
                                // 重算后文字可能一模一样，给一句反馈，避免看起来像没点上。
                                if (updated) showToast("汇报已更新")
                            }
                        }
                    },
                )
                Spacer(Modifier.width(2.dp))
                ExpandToggle(
                    expanded = expanded,
                    enabled = report.isNotBlank(),
                    onToggle = { expanded = !expanded },
                )
            }
            if (expanded && report.isNotBlank()) {
                // 正文接在任务行下面、历史列表上面；与今日总结同一套：无卡片、次要文字色。
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        text = report,
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                        lineHeight = 18.sp,
                    )
                    Text(
                        text = "复制",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.Primary,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { copyReport(context, report) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        config.digests.asReversed().forEach { digest ->
            Column(Modifier.padding(vertical = 10.dp)) {
                DigestContent(digest = digest)
                AiDivider(modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
