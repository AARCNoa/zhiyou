package com.AARCNoa.zhiyou.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 注入到微信内的 Compose 内容用的**基础** Material 主题（不带模块彩色方案）。
 *
 * 与 [com.AARCNoa.zhiyou.ui.utils.InjectedUiTheme] 的分工：
 * - 本函数：只需要「M3 默认配色 + 跟随深浅色」，用于侧边栏这类自绘为主的界面；
 * - `ui.utils.InjectedUiTheme`：需要套用模块主题（自定义色 / 应用到微信）时用那个。
 *
 * [darkTheme] 传 null 时读系统深色状态。**不能忽略这个参数** —— 曾经函数体里写死
 * `MaterialTheme { }`（等价于恒用浅色），导致传入 `darkTheme = true` 的界面仍是浅色。
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
