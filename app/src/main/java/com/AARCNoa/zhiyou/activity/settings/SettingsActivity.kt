package com.AARCNoa.zhiyou.activity.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.activity.settings.ai.AiAddFriendSheet
import com.AARCNoa.zhiyou.activity.settings.ai.AiPager
import com.AARCNoa.zhiyou.ui.content.FloatingBottomBar
import com.AARCNoa.zhiyou.ui.content.FloatingBottomBarDefaults
import com.AARCNoa.zhiyou.ui.content.FloatingBottomBarItem
import com.AARCNoa.zhiyou.ui.content.MiuixStackNavigator
import com.AARCNoa.zhiyou.ui.icons.FilledIcons
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.ui.content.liquid.vibrancy
import com.AARCNoa.zhiyou.ui.utils.CommonContextWrapper
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.utils.theme.ModuleTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text as M3Text
import androidx.compose.ui.draw.clipToBounds

val LocalComponentActivity = staticCompositionLocalOf<ComponentActivity> { error("not provided") }

@Keep
private const val TAG = "SettingsActivity"

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 本页面只能跑在微信进程：所有配置与功能都依赖微信侧的 MMKV / 数据库，
        // 正常入口是 WeSettingsInjector → 微信 LauncherUI → ActivityProxy 代理启动。
        // SettingsActivity 必须 exported 才能被代理路由到，因此第三方或 adb 也能直接拉起它；
        // 那种情况下会落在模块自己的进程，读不到初始化过的 MMKV（FeaturesProvider 静态块里
        // 经 ThemeStore → WePrefs 直接抛 IllegalStateException），与其闪退不如立刻退出。
        if (HostInfo.isModule) {
            WeLogger.w(TAG, "在模块进程被启动，已退出（请从微信内进入设置）")
            finish()
            return
        }

        enableEdgeToEdge()

        val context = CommonContextWrapper(this)
        val resources = context.resources

        setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalResources provides resources,
                LocalActivity provides this,
                LocalComponentActivity provides this
            ) {
                ModuleTheme {
                    SettingsRoot(onFinish = { finish() })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Feature categories (name -> icon)
// ---------------------------------------------------------------------------

val FEATURE_CATEGORIES = listOf(
    "聊天" to OutlinedIcons.Chat,
    "联系人与群组" to OutlinedIcons.Contacts,
    "红包与支付" to OutlinedIcons.Payments,
    "朋友圈" to OutlinedIcons.Camera,
    "系统与隐私" to OutlinedIcons.Wand_stars,
    "音视频通话" to OutlinedIcons.Call,
    "界面美化" to OutlinedIcons.Imagesearch_roller,
    "公众号" to OutlinedIcons.Newspaper,
    "小程序" to OutlinedIcons.Package_2,
    "视频号" to OutlinedIcons.Movie,
    "个人资料" to OutlinedIcons.Account_circle,
    "调试" to OutlinedIcons.Bug_report,
    "批量操作" to OutlinedIcons.Checklist,
    "首页右上角菜单" to OutlinedIcons.Add_circle,
    // 「密友功能」刻意放在列表**最后**（隐蔽入口，不显眼）；搜索页会排除该分类。
    "密友功能" to OutlinedIcons.Contacts,
)

// ---------------------------------------------------------------------------
//  Root: three-tab pager + floating bottom bar, with category drill-down
// ---------------------------------------------------------------------------

/** Navigation targets for the Settings activity's stack. */
internal sealed interface SettingsNavTarget {
    data object Main : SettingsNavTarget
    data object License : SettingsNavTarget
    data object ModuleHealth : SettingsNavTarget
}

@Composable
private fun SettingsRoot(onFinish: () -> Unit) {
    val stack = remember { mutableStateListOf<SettingsNavTarget>(SettingsNavTarget.Main) }

    // 全局返回链：任何子页面按返回都逐级回退，而不是直接退到微信主界面。
    // 1) 停在子页面（分类详情/自检/日志等）→ 逐级 pop；
    // 2) 已在主页 → 交给 MainPagerScreen 内层的 BackHandler（先回 pager 第 0 页再退出）。
    androidx.activity.compose.BackHandler {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            onFinish()
        }
    }

    MiuixStackNavigator(
        stack = stack,
        onExitRoot = onFinish,
    ) { screen, push, pop ->
        when (screen) {
            SettingsNavTarget.Main -> MainPagerScreen(
                onOpenLicense = { push(SettingsNavTarget.License) },
                onOpenModuleHealth = { push(SettingsNavTarget.ModuleHealth) },
                onFinish = onFinish,
                // 只有主界面位于栈顶时才由主界面处理返回（先回 pager 第 0 页/单页模式退出）；
                // 一旦上面盖了子页面，返回必须由栈导航器逐级 pop，否则侧滑会直接 finish 掉
                // 整个设置 Activity（表现为「一步退回微信主界面」）。
                backEnabled = stack.size == 1,
            )

            SettingsNavTarget.License -> LicenseScreen(
                onBack = pop,
            )

            SettingsNavTarget.ModuleHealth -> ModuleHealthScreen(
                onBack = pop,
            )
        }
    }
}

@Composable
private fun MainPagerScreen(
    onOpenLicense: () -> Unit,
    onOpenModuleHealth: () -> Unit,
    onFinish: () -> Unit,
    backEnabled: Boolean = true,
) {
    val pagerState = rememberPagerState(pageCount = { TAB_ITEMS.size })
    // 主页半屏卡片的状态放在这一层：卡片必须在底部导航**之后**绘制才能盖住导航栏，
    // 而 HomePager 渲染在 pager 内部（先于导航栏），所以外壳留在这里。
    var homeSheet by remember { mutableStateOf<String?>(null) }
    // 「添加好友」同理：放在这里才能在导航条之后渲染（否则确认按钮会被导航条压住）。
    var aiAddSheet by remember { mutableStateOf(false) }
    // 浮层确认添加后，让 AI 页重读索引。
    var aiRefresh by remember { mutableIntStateOf(0) }
    // 开关的即时状态：主页搜索列表与半屏清单共用一份。放在这一层而不是文件级全局，
    // Activity 重建时随之丢弃，不会把上一轮的临时值带进新实例。
    val switchStates = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    // 切到别的 Tab 时收起半屏，避免遮罩留在其它页面上。
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) homeSheet = null
        if (pagerState.currentPage != 1) aiAddSheet = false
    }
    // 返回键兜底：半屏开着先收半屏；否则任意 Tab 都先回到主页（第 0 页），已在主页才退出设置。
    // 之前没有这层处理，按返回会直接 finish 掉代理 Activity，用户看到的是「退回微信主界面」。
    androidx.activity.compose.BackHandler(enabled = backEnabled) {
        if (aiAddSheet) {
            aiAddSheet = false
        } else if (homeSheet != null) {
            homeSheet = null
        } else if (pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
        } else {
            onFinish()
        }
    }
    val barBottomPadding = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackground()),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
            // 半屏浮层打开时禁掉横滑：遮罩只吞点击，拖动仍可能被下面的 pager 接走，
            // 表现是「拖着浮层，页面跟着滑走了」。
            userScrollEnabled = homeSheet == null && !aiAddSheet,
            key = { it },
        ) { page ->
            when (page) {
                0 -> HomePager(
                    onOpenModuleHealth = onOpenModuleHealth,
                    switchStates = switchStates,
                    onOpenSheet = { homeSheet = it },
                    sheetOpen = homeSheet != null,
                )
                1 -> AiPager(
                    onRequestAdd = { aiAddSheet = true },
                    externalRefresh = aiRefresh,
                )
                2 -> LogsPager()
                else -> SettingsPager(onOpenLicense = onOpenLicense)
            }
        }

        val haptic = LocalHapticFeedback.current

        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(bottom = barBottomPadding),
            selectedIndex = { pagerState.targetPage },
            // Track the pager's fractional scroll 1:1 during a finger swipe; a tab *tap*
            // (programmatic animateScrollToPage, isDragged == false) springs the pill across
            // with the press/glass bulge instead of a flat translate.
            progress = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            isTracking = { isDragged },
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            backdrop = backdrop,
            tabsCount = TAB_ITEMS.size,
            isBlurEnabled = true,
            colors = FloatingBottomBarDefaults.colors(
                containerColor = MiuixTheme.colorScheme.surfaceContainer,
                // 选中态：与微信原生 Tab 一致的绿色（实心图标 + 文字），不再用主题蓝。
                indicatorColor = WxPalette.Primary,
                contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                activeContentColor = WxPalette.Primary,
            ),
        ) {
            // Key the fill crossfade to targetPage (same driver as the pill), not
            // settledPage — settledPage only updates when animateScrollToPage fully
            // finishes, so the icon would fill a beat after the pill has arrived.
            val target = pagerState.targetPage
            TAB_ITEMS.forEachIndexed { index, item ->
                FloatingBottomBarItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                ) {
                    Crossfade(
                        targetState = index == target,
                        animationSpec = tween(200),
                        label = "navIcon",
                    ) { selected ->
                        M3Icon(
                            imageVector = if (selected) item.filled else item.outlined,
                            contentDescription = item.label,
                        )
                    }
                    M3Text(
                        text = item.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }

        // 画在 FloatingBottomBar 之后 → 遮罩与卡片都盖在导航栏之上（之前被导航栏挡住一截）。
        // 注意：必须写在 FloatingBottomBar 的 content lambda **之外**。它内部是
        // `Box(width(IntrinsicSize.Min))`，会把子树的固有尺寸量一遍；而本卡片里有 LazyColumn，
        // 对 SubcomposeLayout 求固有尺寸会直接抛 IllegalStateException（实测点「分类」必崩）。
        HomeSheetHost(
            kind = homeSheet,
            onDismiss = { homeSheet = null },
            switchStates = switchStates,
        )

        // 添加好友：与上面三个半屏同一套外壳（BottomSheetHost），因此遮罩与卡片都盖在
        // 底部导航条之上，确认按钮不需要任何留白补偿。
        if (aiAddSheet) {
            AiAddFriendSheet(
                onDismiss = { aiAddSheet = false },
                onConfirmed = {
                    aiAddSheet = false
                    aiRefresh++
                },
            )
        }
    }
}

private data class NavItem(val label: String, val outlined: ImageVector, val filled: ImageVector)

/**
 * 底部导航。原来的「功能」页已删除：分类列表改由主页「分类」半屏卡片承载，
 * 搜索框迁到主页顶部，所以只剩主页 / AI / 日志 / 设置四页。
 */
private val TAB_ITEMS = listOf(
    NavItem("主页", OutlinedIcons.Home, FilledIcons.Home),
    NavItem("AI", OutlinedIcons.Auto_awesome, FilledIcons.Auto_awesome),
    NavItem("日志", OutlinedIcons.Article, FilledIcons.Article),
    NavItem("设置", OutlinedIcons.Settings, FilledIcons.Settings),
)

/** Bottom padding so scrollable content clears the floating bar. */
val CONTENT_BOTTOM_INSET = 88.dp

// ---------------------------------------------------------------------------
//  Shared scaffold: miuix Scaffold + collapsing TopAppBar + scrollable column
// ---------------------------------------------------------------------------

@Composable
fun MiuixListScaffold(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
    // 传 false 时完全不创建顶栏：页面不再有一条横跨全宽的色带压在内容上方，
    // 标题改为由调用方自己放进列表首项。状态栏高度改由 contentPadding 补，内容不会被状态栏挡住。
    showTopBar: Boolean = true,
    // 关闭后顶栏使用不透明底色、列表不再注册为模糊源，用于规避特定页面（内容为单个超高 item）
    // 在滚动时 backdrop 采样越界导致的顶部灰块。
    blurTopBar: Boolean = true,
    // 内容左右内边距；内层组件自带边距时传 0，避免双重内边距。
    horizontalPadding: Dp = 12.dp,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    // The LazyColumn is registered as the blur source; the top bar samples that captured
    // layer through drawBackdrop and paints itself transparent, so scrolled content shows
    // through blurred behind the collapsed bar (InstallerX's useBlur pattern, on miuix-blur glass).
    val barBackdrop = rememberLayerBackdrop()
    // 只有「保留顶栏 + 有标题」时才值得开毛玻璃：空标题没有内容可供 backdrop 采样，
    // blur 会退化成一条纯黑色带；无顶栏模式下更不需要。
    val useBlur = showTopBar && blurTopBar && title.isNotEmpty()
    // 原来是 0.67 透明的毛玻璃：在 #F2F2F7 灰底上滚动时会变成一块灰糊，
    // 压在文字上很难看。改成 0.94 近乎不透明，保留一点模糊质感但不糊字。
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.94f)
    Scaffold(
        // 背景统一为氛围渐变底：之前用 miuix 默认白色，分类详情/设置页与主页观感割裂
        // 分类详情与主页保持同一底色，不出现整片白底。
        modifier = Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackground()),
        containerColor = Color.Transparent,
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    modifier = if (useBlur) {
                        Modifier.drawBackdrop(
                            backdrop = barBackdrop,
                            shape = { RectangleShape },
                            effects = {
                                vibrancy()
                                blur(24.dp.toPx(), 24.dp.toPx())
                            },
                            onDrawSurface = { drawRect(barTint) },
                        )
                    } else {
                        Modifier
                    },
                    // Transparent so the miuix bar's own opaque surface doesn't hide the blur.
                    // 不用 MiuixTheme.surface：深色下它可能本身就是近黑，会留下一条黑带。
                    // 直接用页面底色，顶栏与渐变背景同色，肉眼看不出边界。
                    color = if (useBlur) Color.Transparent else WxPalette.pageBackground(),
                    title = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { navigationIcon?.invoke() },
                )
            }
        },
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                // 修复「列表页顶部出现异常灰块」：内容超出/回弹时 backdrop 采样会越界绘制。
                .then(if (useBlur) Modifier.clipToBounds().layerBackdrop(barBackdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                // 顶栏不存在时不要挂 scrollBehavior 的嵌套滚动：它专门用于折叠顶栏，
                // 没有 TopAppBar 消费 offset 时会把滚动手势吞掉，表现为整页滚不动。
                .then(
                    if (showTopBar) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    else Modifier
                )
                .padding(horizontal = horizontalPadding),
            // 无顶栏时 innerPadding.top 不一定含状态栏高度（取决于宿主 insets 配置），
            // 这里显式补一次，避免首项被状态栏压住。
            contentPadding = if (showTopBar) {
                innerPadding
            } else {
                PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                )
            },
            overscrollEffect = null,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
//  Grouped card: 每行一个 LazyColumn item（保持虚拟化），首尾行各自圆角，
//  中间行直角 —— 视觉上仍是一张连续的白卡。
// ---------------------------------------------------------------------------

/**
 * 功能清单的分组行：**一张分组卡只画一圈外轮廓**，行间只画一条内缩细线。
 *
 * 为什么不用 [cardSurface]：那是「独立一张卡」，四边都有 1dp 描边。把它套在每一行上时，
 * 相邻两行的描边会贴在一起变成 2dp 的双线，再叠加行间分割线就成了三根线（看起来「有两条线」）。
 *
 * 这里沿可见轮廓描一条开放路径：中间行只有左右两条竖边，首行带上圆角，末行带下圆角，
 * 圆角用 `arcTo` 补上（否则四个角会缺一段边）；行间细线由**上面那一行**画，横向留
 * [dividerStart] 与行内文字对齐。
 *
 * 之所以一行一个 LazyColumn item（而不是整段放进一个 Column），是为了长清单保持虚拟化。
 */
@Composable
fun Modifier.groupedCardItem(
    index: Int,
    count: Int,
    dividerStart: Dp = 0.dp,
): Modifier {
    val radius = WxPalette.CardRadius
    val isFirst = index == 0
    val isLast = index == count - 1
    val shape = RoundedCornerShape(
        topStart = if (isFirst) radius else 0.dp,
        topEnd = if (isFirst) radius else 0.dp,
        bottomStart = if (isLast) radius else 0.dp,
        bottomEnd = if (isLast) radius else 0.dp,
    )
    val background = WxPalette.cardBackground()
    val strokeColor = WxPalette.cardBorder()
    val dividerColor = WxPalette.divider()
    // 行间分隔线由「上面那一行」画，否则相邻两行各画一条又会叠成双线。
    val hasDivider = !isLast && dividerStart > 0.dp

    return fillMaxWidth()
        .background(background, shape)
        .drawBehind {
            val w = 1.dp.toPx()
            val half = w / 2f
            val r = radius.toPx()
            // 路径内缩半个描边宽度，描边才不会被父容器的圆角裁掉一半。
            val l = half
            val t = half
            val rt = size.width - half
            val b = size.height - half
            val path = Path()
            when {
                // 单行成卡：整圈圆角
                isFirst && isLast -> path.addRoundRect(
                    RoundRect(Rect(l, t, rt, b), CornerRadius(r))
                )
                // 首行：左下 → 左边 → 左上圆角 → 上边 → 右上圆角 → 右边 → 右下
                isFirst -> {
                    path.moveTo(l, b)
                    path.lineTo(l, t + r)
                    path.arcTo(Rect(l, t, l + r * 2, t + r * 2), 180f, 90f, false)
                    path.lineTo(rt - r, t)
                    path.arcTo(Rect(rt - r * 2, t, rt, t + r * 2), 270f, 90f, false)
                    path.lineTo(rt, b)
                }
                // 末行：上 → 左边 → 左下圆角 → 下边 → 右下圆角 → 右边
                isLast -> {
                    path.moveTo(l, t)
                    path.lineTo(l, b - r)
                    path.arcTo(Rect(l, b - r * 2, l + r * 2, b), 180f, -90f, false)
                    path.lineTo(rt - r, b)
                    path.arcTo(Rect(rt - r * 2, b - r * 2, rt, b), 90f, -90f, false)
                    path.lineTo(rt, t)
                }
                // 中间行：只有左右两条竖边
                else -> {
                    path.moveTo(l, t)
                    path.lineTo(l, b)
                    path.moveTo(rt, t)
                    path.lineTo(rt, b)
                }
            }
            drawPath(path, strokeColor, style = Stroke(width = w))
            if (hasDivider) {
                val x = dividerStart.toPx()
                drawLine(dividerColor, Offset(x, b), Offset(rt, b), w)
            }
        }
}

