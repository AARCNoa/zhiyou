package com.AARCNoa.zhiyou.ui.content

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.AARCNoa.zhiyou.ui.style.WxPalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页的分组小标题。
 *
 * [textColor] 传 null 时走 [WxPalette.textSecondary]（跟随深浅色）。
 * 默认值不能写成 `WxPalette.TextSecondary` 常量 —— 那是浅色专用值，深色下会发暗。
 */
@Composable
fun MiuixSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    insideMargin: PaddingValues = PaddingValues(start = 6.dp, top = 12.dp, bottom = 4.dp),
) {
    Text(
        modifier = modifier.padding(insideMargin),
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = textColor ?: WxPalette.textSecondary(),
    )
}
