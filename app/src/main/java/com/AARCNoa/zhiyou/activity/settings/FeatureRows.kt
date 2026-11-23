package com.AARCNoa.zhiyou.activity.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.BaseFeature
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.FeaturesProvider
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Settings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

// ---------------------------------------------------------------------------
//  功能清单的两行：分类行（CategoryRow）与功能开关行（CatRow）
//
//  主页搜索结果、分类半屏、已启用半屏共用这两个组件，保证同一个功能在哪里都长得一样。
//  文件原名叫 FeaturesPager.kt —— 「功能」标签页删除后它已经不再是 pager，
//  只剩这两行，故改名。
// ---------------------------------------------------------------------------

/**
 * 用户可见的功能清单，也是所有计数与清单的唯一口径。
 *
 * 两类条目不进 UI：
 * - [ApiFeature]：模块内部能力层（消息发送、数据库、菜单注册等），只被功能代码调用，
 *   没有开关、也不属于任何分类。它们在 [FeaturesProvider.ALL_HOOK_ITEMS] 里，
 *   直接展示会被当成「已关闭的功能」。
 * - `unavailableReason != null`：当前微信版本用不上（例如只在 8.0.80+ 生效的崩溃防御）。
 *   搜索与分类二级早已按此隐藏，此前「已启用 / 全部功能 / 统计卡」漏了，
 *   于是出现「显示已启用却搜不到」。
 */
internal val userVisibleFeatures: List<BaseFeature> =
    FeaturesProvider.ALL_HOOK_ITEMS.filter { it !is ApiFeature && it.unavailableReason == null }

/** [userVisibleFeatures] 里真正带开关的项；统计与「已启用」清单都以此为准。 */
internal val toggleableFeatures: List<SwitchFeature> =
    userVisibleFeatures.filterIsInstance<SwitchFeature>()

/**
 * 分类专属色：让每个分类在列表里一眼可辨（不是靠文字读出来的）。
 *
 * 这里全部写死字面量，**故意不引用 [WxPalette]**：分类是配色方案，不是状态语义，
 * 之前借用 Ok/Warning 导致「改状态绿 → 分类色跟着变」。
 */
internal val CATEGORY_COLORS = listOf(
    Color(0xFF3D7EFF),
    Color(0xFF00D4A0),
    Color(0xFF8B5CF6),
    Color(0xFFFF8A3D),
    Color(0xFF00B8D9),
    Color(0xFFFF5B8A),
    Color(0xFF12D18E),
    Color(0xFFFFB020),
)

/** 分类行：圆角图标 + 分类名 + 启用概况 + 小进度条 + 右箭头（主页分类半屏卡片复用）。 */
@Composable
internal fun CategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = WxPalette.Primary,
    progress: Float = 0f,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
            )
        }
        if (progress > 0f) {
            // 小进度条：不用读数字也能看出这个分类开了多少
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(WxPalette.cardBorder()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "\u203A",
            fontSize = 20.sp,
            color = WxPalette.textTertiary(),
        )
    }
}

/**
 * 功能开关行（自绘，替代 miuix BasicComponent/SwitchPreference 的高行）：
 * 标题 + 描述两行内完成，开关右贴齐；带配置面板的功能显示齿轮图标。
 *
 * 主页搜索结果与半屏分类清单共用它，保证同一个开关在哪里都长得一样。
 */
@Composable
internal fun CatRow(
    item: BaseFeature,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalComponentActivity.current
    val configKey = item.name

    // 非 SwitchFeature：纯信息行（API 服务/常驻项），无开关
    if (item !is SwitchFeature) {
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(
                text = item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WxPalette.textPrimary(),
            )
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = WxPalette.textSecondary(),
                )
            }
        }
        return
    }

    DisposableEffect(configKey) {
        item.setToggleCompletionCallback { onCheckedChange(item.isEnabled) }
        // 功能是单例，页面销毁时必须摘掉回调，否则会一直持有这个 Composable 的 lambda。
        onDispose { item.setToggleCompletionCallback(null) }
    }

    fun toggle(requested: Boolean) {
        val unavailable = if (requested) item.unavailableReason else null
        if (unavailable != null) {
            WeLogger.w("CatRow", "拒绝开启 ${item.name}：$unavailable")
            showToast(unavailable)
            return
        }
        if (item.onBeforeToggle(requested, context)) {
            WePrefs.putBool(configKey, requested)
            item.isEnabled = requested
            onCheckedChange(requested)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (item is ClickableFeature) {
                    runCatching { item.onClick(context) }
                        .onFailure { WeLogger.e("CatRow", "onClick failed: ${item.name}", it) }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WxPalette.textPrimary(),
                )
                if (item is ClickableFeature) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Settings,
                        contentDescription = "可配置",
                        modifier = Modifier.size(15.dp),
                        tint = WxPalette.textSecondary(),
                    )
                }
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = WxPalette.textSecondary(),
                )
            }
        }
        if (!(item is ClickableFeature && item.noSwitchWidget)) {
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = { toggle(it) })
        }
    }
}
