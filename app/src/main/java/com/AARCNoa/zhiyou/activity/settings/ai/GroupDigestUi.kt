package com.AARCNoa.zhiyou.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.ai.AiDates.timeOf
import com.AARCNoa.zhiyou.ai.group.GroupDigest
import com.AARCNoa.zhiyou.ai.group.GroupDigestStatus
import com.AARCNoa.zhiyou.ai.group.GroupSummary
import com.AARCNoa.zhiyou.ai.group.nextRunAtOf
import com.AARCNoa.zhiyou.ui.style.WxPalette

/**
 * AI 页「群组」Tab：群总结列表。
 *
 * 数据只读 [com.AARCNoa.zhiyou.ai.group.GroupDigestStore] 的索引缓存，页面不查库、不读单群文件。
 */
@Composable
internal fun GroupDigestList(
    groups: List<GroupSummary>,
    onOpen: (String) -> Unit,
    onLongPress: (GroupSummary) -> Unit,
    onAdd: () -> Unit,
) {
    if (groups.isEmpty()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            AiEmptyCard(
                title = "还没有群总结",
                body = "选一个群，每隔一段时间自动整理成一条摘要。",
                action = "添加群聊",
                onAction = onAdd,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(groups, key = { it.groupId }) { group ->
            GroupRow(
                group = group,
                onOpen = { onOpen(group.groupId) },
                onLongPress = { onLongPress(group) },
            )
        }
    }
}

@Composable
private fun GroupRow(
    group: GroupSummary,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (subtitle, danger) = groupSubtitle(group, preferHeadline = false)
    GroupSummaryRow(
        group = group,
        subtitle = subtitle,
        danger = danger,
        onClick = onOpen,
        onLongClick = onLongPress,
        background = WxPalette.cardBackground(),
    )
}

/**
 * 群行：头像 + 群名（含工作群任务）+ 副标题，底部一条 0.5dp 线。
 *
 * 总览面板与 AI 页群组列表共用这一份。两处原先各写一遍（约 40 行），副标题口径还漂了：
 * 面板优先显示摘要，于是「已暂停 / 上次失败」被摘要盖住；列表只显示状态。
 * 现在由 [groupSubtitle] 统一决定，页面只传「要不要优先摘要」。
 */
@Composable
internal fun GroupSummaryRow(
    group: GroupSummary,
    subtitle: String,
    danger: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    background: Color = Color.Transparent,
    startPadding: Dp = 16.dp,
    endPadding: Dp = 16.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    // 只认「工作群开着」的任务：关闭后即使盘里还留着旧值也不显示。
    val task = if (group.workGroup) group.currentTask else ""
    Box(Modifier.fillMaxWidth().background(background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onLongClick == null) {
                        Modifier.clickable { onClick() }
                    } else {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                )
                .padding(start = startPadding, end = endPadding, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarWithUnread(
                avatarUrl = group.avatarUrl,
                name = group.name,
                unread = group.unread,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = groupTitle(group.name.ifBlank { group.groupId }, task, group.currentProgress),
                    fontSize = 15.sp,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (danger) WxPalette.Danger else WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        AiDivider(
            startPadding = 68.dp,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * 群行副标题。
 *
 * 失败 / 暂停 / 关闭 / 未配置一律先说状态 —— 摘要再好看也不该盖住「这个群已经停了」。
 * 其余情况按调用方偏好：总览要看摘要，设置页要看运行节奏。
 * 第二项表示是否用警示色，避免各调用点自己判一遍状态。
 */
internal fun groupSubtitle(group: GroupSummary, preferHeadline: Boolean): Pair<String, Boolean> {
    val status = group.lastStatus
    val danger = status == GroupDigestStatus.FAILED || status == GroupDigestStatus.PAUSED
    if (danger || !group.enabled || status == GroupDigestStatus.UNCONFIGURED) {
        return statusLine(group) to danger
    }
    if (preferHeadline && group.lastHeadline.isNotBlank()) return group.lastHeadline to false
    return statusLine(group) to false
}

/**
 * 一条摘要的正文：时间 · 手动标记 ·（可选）条数 → 当轮任务 → 标题 → 要点 / 待办。
 *
 * 总览的历史面板与设置页的摘要行共用；两处只有容器不同（平铺 vs 卡片）和是否折叠。
 */
@Composable
internal fun DigestContent(
    digest: GroupDigest,
    expanded: Boolean = true,
    showMessageCount: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = timeOf(digest.createdAt),
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
        )
        if (digest.manual) {
            Spacer(Modifier.width(6.dp))
            Text("手动", fontSize = 10.sp, color = WxPalette.Primary)
        }
        if (showMessageCount) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "${digest.messageCount} 条",
                fontSize = 10.sp,
                color = WxPalette.textTertiary(),
            )
        }
    }
    // 任务与摘要同级展示：看历史时能直接看出进度是从哪一轮开始变的。
    if (digest.task.isNotBlank()) {
        Text(
            text = taskLabel(digest.task, digest.progress),
            fontSize = 11.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    Text(
        text = digest.headline,
        fontSize = 14.sp,
        color = WxPalette.textPrimary(),
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 3.dp),
    )
    if (expanded) {
        digest.points.forEach { point ->
            Text(
                text = "· $point",
                fontSize = 13.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 19.sp,
            )
        }
        if (digest.todos.isNotEmpty()) {
            Text(
                text = "待办",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textSecondary(),
                modifier = Modifier.padding(top = 4.dp),
            )
            digest.todos.forEach { todo ->
                Text(
                    text = "· $todo",
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

/**
 * 头像 + 未读角标。
 *
 * 角标压左上角、向外探出 6dp：这一侧只有面板边距，不会顶到名字，也不会被行尾内容挤住；
 * 压在头像内部又会遮住头像本身。
 */
@Composable
internal fun AvatarWithUnread(
    avatarUrl: String,
    name: String,
    unread: Int,
    size: Int = 40,
) {
    Box {
        AiAvatar(model = avatarUrl, name = name, size = size)
        if (unread > 0) {
            UnreadBadge(
                count = unread,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp),
            )
        }
    }
}

/**
 * 未读角标：正圆、数字居中。
 *
 * 首页入口图标（22dp）与列表头像（40dp）共用这一个，观感统一。
 * 不靠横向 padding 拉成胶囊：直径按位数走（1 位 / 2 位 / 99+），位数多了只加宽 2~4dp，
 * 始终保持圆形。`includeFontPadding = false` 去掉字体自带上下留白，否则数字会偏低。
 */
@Composable
internal fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 16.dp,
) {
    if (count <= 0) return
    val label = if (count > 99) "99+" else count.toString()
    val size = when (label.length) {
        1 -> diameter
        2 -> diameter + 2.dp
        else -> diameter + 4.dp
    }
    val fontSize = when (label.length) {
        1 -> 10.sp
        2 -> 9.sp
        else -> 8.sp
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(WxPalette.Danger),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            // 自己给完整样式，不继承 LocalTextStyle：Material3 默认 lineHeight 24sp，
            // 10sp 的字放进 24sp 行高会整体往下沉，数字贴着圆的底边。
            style = TextStyle(
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

/**
 * 群名 + 工作群任务。
 *
 * 只用一个间隔点把两者分开：名字保持主文字色，任务用主色、百分比再淡一档。
 * 不加「当前群工作任务」这类前缀 —— 群行里只有这一处绿色文字，含义已经足够清楚，
 * 前缀只会把群名挤到省略号。
 */
@Composable
internal fun groupTitle(name: String, task: String, progress: Int): AnnotatedString {
    if (task.isBlank()) return AnnotatedString(name)
    // 群名 + 任务必须落在同一行：任务太长时折到第二行，看起来就像「标题下面又冒出一行」，
    // 再叠上副标题就是三行。这里先按字数截断，行内再交给 maxLines = 1 兜底。
    val short = if (task.length > TITLE_TASK_MAX) task.take(TITLE_TASK_MAX) + "…" else task
    // 任务与群名同色，只靠字号和间隔点分开：绿色在群行里只此一处时像链接，
    // 会和未读角标、状态文字抢注意力。
    return buildAnnotatedString {
        append(name)
        withStyle(SpanStyle(color = WxPalette.textPrimary(), fontSize = 12.sp)) {
            append(" · ")
            append(short)
            if (progress in 0..100) {
                append(" ")
                withStyle(SpanStyle(color = WxPalette.textPrimary().copy(alpha = 0.55f))) {
                    append("$progress%")
                }
            }
        }
    }
}

/** 标题行里任务最多显示的字数：再长就会把群名挤掉，完整任务在群详情页里。 */
private const val TITLE_TASK_MAX = 12

/** 「任务 60%」；没有任务时返回空串，进度缺失只显示任务。 */
internal fun taskLabel(task: String, progress: Int): String {
    if (task.isBlank()) return ""
    val percent = if (progress in 0..100) " $progress%" else ""
    return task + percent
}

/** 列表副标题：优先未读，其次下次时间；失败 / 暂停显示原因。 */
internal fun statusLine(group: GroupSummary): String {
    if (!group.enabled) {
        return if (group.lastStatus == GroupDigestStatus.PAUSED) {
            "已暂停：${group.lastError.ifBlank { "连续失败" }}"
        } else {
            "已关闭"
        }
    }
    if (group.lastStatus == GroupDigestStatus.BUSY) return "正在总结…"
    if (group.unread > 0) {
        return "${group.unread} 条未读 · 最近 ${timeOf(group.lastRunAt)}"
    }
    val next = nextRunAtOf(group.anchorAt, group.intervalMinutes, System.currentTimeMillis())
    return when (group.lastStatus) {
        GroupDigestStatus.EMPTY -> group.lastError.ifBlank { "最近没有值得总结的内容" }
        GroupDigestStatus.FAILED -> "上次失败：${group.lastError.ifBlank { "未知原因" }}"
        GroupDigestStatus.UNCONFIGURED -> "未配置 AI 接口"
        else -> "${intervalText(group.intervalMinutes)}一次 · 下次 ${timeOf(next)}"
    }
}

internal fun intervalText(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}
