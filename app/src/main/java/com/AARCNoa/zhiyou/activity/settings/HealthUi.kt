package com.AARCNoa.zhiyou.activity.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.ui.style.WxPalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块设置 / 自检页共用的页面骨架：标题栏 + 返回 + 状态栏内边距。
 *
 * 分割线统一走 [com.AARCNoa.zhiyou.ui.style.SoftDivider]。
 */

@Composable
internal fun PageScaffold(
    title: String = "",
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackground())
            // 关键：之前没有状态栏内边距，标题直接顶进状态栏，被时间/信号图标压住。
            .statusBarsPadding(),
    ) {
        val hasHeader = onBack != null || title.isNotEmpty() || !subtitle.isNullOrBlank()
        if (hasHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onBack)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(text = "返回", color = MiuixTheme.colorScheme.primary, fontSize = 15.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    if (title.isNotEmpty()) {
                        Text(
                            text = title,
                            color = WxPalette.textPrimary(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                        )
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = WxPalette.textSecondary(),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        content()
    }
}
