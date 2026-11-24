package com.AARCNoa.zhiyou.ui.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import com.AARCNoa.zhiyou.ui.utils.theme.SeedResolver
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings
import com.AARCNoa.zhiyou.ui.utils.theme.darkScheme
import com.AARCNoa.zhiyou.ui.utils.theme.lightScheme
import com.AARCNoa.zhiyou.utils.HostInfo

/**
 * Theme for the module's UI injected INTO WeChat (the module's own settings page uses `ModuleTheme`).
 *
 * The seed is [SeedResolver.injectedSeed]: WeChat green by default, or the user's custom color when
 * they opted it into WeChat ([ThemeSettings.applyToWechat]). This is read once when the composition
 * enters — it does NOT re-theme live (the user must restart WeChat for a change to apply).
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalConfiguration provides HostInfo.application.resources.configuration
    ) {
        val dark = darkTheme ?: isSystemInDarkTheme()
        val applyCustom = ThemeSettings.applyToWechat && ThemeSettings.customColor
        val seed = SeedResolver.injectedSeed(HostInfo.application, dark)

        // ---- Material 3 ----
        val materialScheme = if (!applyCustom) {
            if (dark) darkScheme else lightScheme
        } else {
            SeedResolver.materialScheme(seed, dark)
        }

        // 注入到微信里的 UI 只套 Material 3：miuix 主题只在模块自己的设置页提供
        // （见 ui/utils/theme/ModuleTheme.kt），注入侧没有 miuix 组件，不需要重复包一层。
        MaterialExpressiveTheme(
            colorScheme = materialScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
