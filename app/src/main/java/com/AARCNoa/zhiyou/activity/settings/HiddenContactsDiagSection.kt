package com.AARCNoa.zhiyou.activity.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.utils.android.copyToClipboard
import com.AARCNoa.zhiyou.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 「分类 → 密友功能」半屏末尾的状态诊断卡。
 *
 * 数据采集在 [hiddenContactsDiag]（只读、不含 Compose），这里只负责展示：
 * 折叠时一行汇总（全部正常 / N 项异常），有异常默认展开——诊断的意义就是让异常第一眼可见。
 * 视觉与上面的功能清单一致：同一套 [groupedCardItem]（一张卡一圈外轮廓 + 行间一条细线）。
 *
 * 为什么并进半屏而不是独立页：诊断本质是密友设置的一部分，不该再让用户先跑一遍模块自检。
 */
@Composable
internal fun HiddenContactsDiagSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(hiddenContactsDiag()) }
    // 有异常就默认展开：全绿时不必占掉十几行。
    var expanded by remember(items) { mutableStateOf(items.any { !it.ok }) }
    val bad = items.count { !it.ok }
    val rows = if (expanded) items else emptyList()
    // 汇总 1 行 + 明细 N 行 + 展开时的两个操作行，一起算分角圆角。
    val total = 1 + rows.size + if (expanded) 2 else 0
    val statusColor = if (bad == 0) WxPalette.Ok else WxPalette.Danger

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .groupedCardItem(0, total, dividerStart = 14.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(statusColor),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "状态诊断",
                fontSize = 14.sp,
                color = WxPalette.textPrimary(),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (bad == 0) "全部正常" else "$bad 项异常",
                fontSize = 11.sp,
                color = statusColor,
            )
            Icon(
                imageVector = OutlinedIcons.Expand_more,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WxPalette.textTertiary(),
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }

        rows.forEachIndexed { i, item ->
            DiagLine(item = item, modifier = Modifier.groupedCardItem(i + 1, total, dividerStart = 14.dp))
        }

        if (expanded) {
            DiagActionLine(
                title = "刷新会话列表",
                subtitle = "隐藏或恢复没有立刻生效时点这里",
                modifier = Modifier.groupedCardItem(rows.size + 1, total, dividerStart = 14.dp),
                onClick = {
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { refreshHiddenContactsConversations() }
                        showToast(msg)
                        items = withContext(Dispatchers.IO) { hiddenContactsDiag() }
                    }
                },
            )
            DiagActionLine(
                title = "复制诊断信息",
                subtitle = "反馈问题时贴给开发者",
                modifier = Modifier.groupedCardItem(rows.size + 2, total, dividerStart = 14.dp),
                onClick = {
                    val text = if (items.isEmpty()) "（尚无诊断结果）" else diagPlainText(items)
                    val ok = copyToClipboard(context, text, "${BuildConfig.BRAND} 密友诊断")
                    showToast(if (ok) "诊断信息已复制" else "复制失败")
                },
            )
        }
    }
}

/** 诊断明细行：名称 + 状态值 + 一句原因（异常项的原因标红）。 */
@Composable
private fun DiagLine(item: DiagItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(if (item.ok) WxPalette.Ok else WxPalette.Danger),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    fontSize = 13.sp,
                    color = WxPalette.textPrimary(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = item.value,
                    fontSize = 11.sp,
                    color = if (item.ok) WxPalette.textSecondary() else WxPalette.Danger,
                )
            }
            Text(
                text = item.detail,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = WxPalette.textSecondary(),
            )
        }
    }
}

/** 诊断区里的操作行：和明细同一套排版，右侧一个箭头表示可点。 */
@Composable
private fun DiagActionLine(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, color = WxPalette.textPrimary())
            Text(text = subtitle, fontSize = 11.sp, color = WxPalette.textSecondary())
        }
        Text(
            text = "\u203A",
            fontSize = 18.sp,
            color = WxPalette.textTertiary(),
        )
    }
}
