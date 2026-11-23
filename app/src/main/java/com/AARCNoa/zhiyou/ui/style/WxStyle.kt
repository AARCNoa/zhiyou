package com.AARCNoa.zhiyou.ui.style

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings
import androidx.compose.ui.unit.sp

/**
 * 知友视觉规范（iOS 分组卡片风格）。
 *
 * 设计基调：浅灰底（#F2F2F7）+ 纯白大圆角卡片 + 分区标题裸排 + 胶囊标签 + 品牌绿点缀。
 * 层次靠「灰底衬托白卡」而不是阴影，圆角统一 18/14/50 三档。
 */
object WxPalette {

    /**
     * 全模块唯一的「是否深色」判断。
     *
     * 必须走 [ThemeSettings.themeMode]（跟随系统 / 强制浅色 / 强制深色），**不能直接用
     * `isSystemInDarkTheme()`** —— 后者只反映系统开关，用户在设置里选「深色模式」而系统仍是
     * 浅色时，WxPalette 驱动的页面不会变，而 MiuixTheme 驱动的组件（日志页背景、设置页标题）
     * 已经变深了，于是出现「一半深一半浅」的割裂。
     */
    @Composable
    private fun isDarkTheme(): Boolean = ThemeSettings.themeMode.resolve()

    /** 页面底色 */
    val Background = Color(0xFFF2F2F7)

    /**
     * 主色（微信绿 #07C160）。
     *
     * 与微信原生 Tab 选中态同色：底部导航选中项、模块内图标、强调文字都用它，
     * 全库只保留这一个绿色令牌（不要再另加一个「微信绿」）。
     */
    val Primary = Color(0xFF07C160)
    val PrimarySoft = Color(0xFFE6F5EC)

    /**
     * 状态语义色：正常 / 待处理 / 异常。
     *
     * 全库唯一来源——设置页、自检页、日志页、功能卡片一律用这三个，
     * 不再在 UI 里直接写 `Color(0xFF12D18E)` 之类（同样的绿曾经有 13 处硬编码）。
     *
     * 其中「正常」与品牌绿同色：模块里只允许存在一个绿（微信绿），
     * 激活勾号、自检按钮、底部导航选中态、状态圆点必须完全一致。
     */
    val Ok = Primary
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5B5B)

    /**
     * 语义色的浅底（胶囊、徽标底色）。**只在浅色模式用**。
     *
     * 深色模式下对应的是 `xxxSoft()` 函数（同色 16% 透明度），不要直接引用这几个常量当背景，
     * 否则会出现浅色块压在深色卡片上。
     */
    val DangerSoft = Color(0xFFFFEBEE)
    val WarningSoft = Color(0xFFFFF8E1)
    val Info = Color(0xFF1E88E5)
    val InfoSoft = Color(0xFFE3F2FD)

    /** 未激活/关闭状态的灰点（原硬编码 0x33999999） */
    val DotOff = Color(0x33999999)

    /** 文字（浅色） */
    val TextPrimary = Color(0xFF1C1C1E)
    val TextSecondary = Color(0xFF8E8E93)
    val TextTertiary = Color(0xFFC7C7CC)

    /** 文字（深色）：主/次/三级依次变暗，保持与浅色模式相同的层次方向。 */
    val TextPrimaryDark = Color(0xFFF2F2F7)
    val TextSecondaryDark = Color(0xFF98989F)
    val TextTertiaryDark = Color(0xFF6B6B72)

    /** 卡片圆角 */
    val CardRadius = 20.dp
    val ChipRadius = 14.dp
    val PillRadius = 50.dp

    /** 页面底色（跟随深浅色）。深色用冷调近黑，不用死黑。 */
    @Composable
    fun pageBackground(): Color =
        if (isDarkTheme()) DarkBackground else Background

    /** 卡片底色（跟随深浅色）。深色比底板亮一档，靠明度差拉出层次。 */
    @Composable
    fun cardBackground(): Color =
        if (isDarkTheme()) Color(0xFF1A1D24) else Color.White

    /**
     * 卡片内的次级小块（统计格、内嵌按钮底）：浅色用页面灰，深色比卡片再亮一档。
     * 只用于「卡片内部还需要再分一层」的场景，不要拿它当页面或卡片底色。
     */
    @Composable
    fun insetSurface(): Color =
        if (isDarkTheme()) DarkSurfaceHigh else Background

    // ── 文字：三级层次在深色下同比例反转 ────────────────────────────────────
    // 浅色是「越次要越淡」，深色必须「越次要越暗」，不能共用同一组色值。

    /** 主文字色（跟随深浅色） */
    @Composable
    fun textPrimary(): Color = if (isDarkTheme()) TextPrimaryDark else TextPrimary

    /** 次文字色（跟随深浅色）。曾经固定返回浅色值，导致深色下 37 处文字发暗。 */
    @Composable
    fun textSecondary(): Color = if (isDarkTheme()) TextSecondaryDark else TextSecondary

    /** 三级文字色（跟随深浅色）：提示、占位、不可用状态。 */
    @Composable
    fun textTertiary(): Color = if (isDarkTheme()) TextTertiaryDark else TextTertiary

    // ── 强调色 ──────────────────────────────────────────────────────────────
    /**
     * 统一强调色（电光蓝）。UI 统一纯色后，强调文字、进度、选中描边、主按钮一律用它；
     * 不再有渐变令牌，也不再按方向插值取色。
     */
    val Accent = Color(0xFF3D7EFF)

    /** 深色模式底板（比纯黑略亮、带冷调，避免死黑）。 */
    val DarkBackground = Color(0xFF0A0B0F)
    val DarkSurface = Color(0xFF161A21)
    val DarkSurfaceHigh = Color(0xFF1E222B)

    /** 卡片描边：浅色下几乎不可见，深色下一层极淡白边 */
    @Composable
    fun cardBorder(): Color =
        if (isDarkTheme()) Color(0x1AFFFFFF) else Color(0x0D000000)

    /** 分割线：比描边再明显一点，用于卡片内的列表/栏位分隔。 */
    @Composable
    fun divider(): Color =
        if (isDarkTheme()) Color(0x1FFFFFFF) else Color(0x14000000)

    // ── 语义色浅底：浅色用固定色，深色用同色低透明度 ────────────────────────
    // 固定浅色常量（PrimarySoft 等）在深色模式下是近白色块，必须走这几个函数。

    /** 品牌绿浅底（选中行、徽标、胶囊）。 */
    @Composable
    fun primarySoft(): Color =
        if (isDarkTheme()) Primary.copy(alpha = 0.16f) else PrimarySoft

    @Composable
    fun dangerSoft(): Color =
        if (isDarkTheme()) Danger.copy(alpha = 0.16f) else DangerSoft

    @Composable
    fun warningSoft(): Color =
        if (isDarkTheme()) Warning.copy(alpha = 0.16f) else WarningSoft

    @Composable
    fun infoSoft(): Color =
        if (isDarkTheme()) Info.copy(alpha = 0.16f) else InfoSoft
}

/**
 * 主卡片（页面的基本容器）。
 *
 * 视觉：纯色底 + 1dp 描边 + 20dp 圆角，层次靠「底色差 + 描边」而不是阴影。
 * 可点击时自带 0.972 按压回弹（spring），全局统一手感。
 * [glow] = true 时描边换成品牌绿并加粗 0.2dp，用于「全部正常 / 当前状态」卡片。
 * [background] 传入时使用该纯色（用于状态区分），null 则用主题卡片色。
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    background: Color? = null,
    contentPadding: Int = 16,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.972f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "cardPress",
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    val shape = RoundedCornerShape(WxPalette.CardRadius)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(clickModifier),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(background ?: WxPalette.cardBackground(), shape)
                .border(
                    width = if (glow) 1.2.dp else 1.dp,
                    // 描边用品牌绿而不是 Accent(蓝)：主页/自检页已经统一到微信绿，
                    // 这里再出现一圈蓝边就又多一种强调色。
                    color = if (glow) WxPalette.Primary else WxPalette.cardBorder(),
                    shape = shape,
                )
                .padding(contentPadding.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * 卡片表面：底色 + 1dp 描边 + 指定圆角。
 *
 * 全库「画一张卡」只有两种写法：
 * - 内容直接放进卡里 → 用 [SoftCard]；
 * - 需要自己控制布局（列表行虚拟化、按压缩放、自定义 padding）→ 用本函数。
 * 之前第三种「手写 clip + background + border」散落在多处，圆角和描边数值各不相同。
 */
@Composable
fun Modifier.cardSurface(
    shape: Shape = RoundedCornerShape(WxPalette.CardRadius),
    background: Color? = null,
): Modifier = this
    .background(background ?: WxPalette.cardBackground(), shape)
    .border(1.dp, WxPalette.cardBorder(), shape)

/** 圆形图标容器：浅底 + 彩色图标，直径默认 40dp。仅供 [StyleRow] 使用。 */
@Composable
private fun RoundIconBox(
    icon: ImageVector,
    tint: Color,
    background: Color,
    size: Int = 40,
    iconSize: Int = 20,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** 分区标题：灰色小字裸排，位于卡片组上方（iOS 分组列表观感）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        modifier = modifier.padding(start = 6.dp, top = 2.dp, bottom = 6.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color ?: WxPalette.textSecondary(),
    )
}

/** 通用行：圆形图标 + 标题/副标题 + 右侧值或箭头区。 */
@Composable
fun StyleRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    iconTint: Color = WxPalette.Primary,
    iconBackground: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RoundIconBox(
                icon = icon,
                tint = iconTint,
                background = iconBackground ?: WxPalette.cardBackground(),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 8.dp)) { trailing() }
        }
    }
}

/** 风险提示横幅：红灯色底 + 红字，置于页面顶部。底色与描边跟随深浅色。 */
@Composable
fun DangerBanner(title: String, message: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(WxPalette.CardRadius)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .background(WxPalette.dangerSoft(), shape)
                .border(1.dp, WxPalette.Danger.copy(alpha = 0.18f), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = WxPalette.Danger,
            )
            Text(
                text = message,
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 18.sp,
            )
        }
    }
}

/** 状态小胶囊。 */
@Composable
fun StatusPill(text: String, color: Color = WxPalette.Primary, background: Color? = null) {
    Surface(shape = RoundedCornerShape(WxPalette.PillRadius), color = background ?: WxPalette.primarySoft()) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/**
 * 卡片内列表项分割线。
 *
 * 全模块唯一的分割线实现：左右留白、粗细、垂直留白都可调，因此「卡片内分隔」
 * 「信息卡里缩进 54dp 的细分隔」「自检页贴边分隔」共用这一个，不再各写一个 Box。
 */
@Composable
fun SoftDivider(
    modifier: Modifier = Modifier,
    start: Dp = 12.dp,
    end: Dp = 12.dp,
    thickness: Dp = 1.dp,
    verticalPadding: Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = start, end = end, top = verticalPadding, bottom = verticalPadding)
            .height(thickness)
            .background(WxPalette.divider()),
    )
}

/** 页面主标题：黑色大号粗体（iOS 大标题观感）。 */
@Composable
fun StyleTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = WxPalette.textPrimary(),
    )
}
