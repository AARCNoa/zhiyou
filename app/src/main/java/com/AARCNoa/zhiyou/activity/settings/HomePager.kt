package com.AARCNoa.zhiyou.activity.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.ui.style.SoftDivider
import com.AARCNoa.zhiyou.ui.content.BottomSheetHost
import com.AARCNoa.zhiyou.ui.content.SearchField
import com.AARCNoa.zhiyou.ui.content.aiStatusSubtitle
import com.AARCNoa.zhiyou.ui.content.showAiConfigDialog
import com.AARCNoa.zhiyou.ui.content.SheetTextAction
import com.AARCNoa.zhiyou.ui.style.cardSurface
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.style.rememberGrowNumber
import com.AARCNoa.zhiyou.utils.HostFramework
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.update.UpdateChecker
import com.AARCNoa.zhiyou.update.UpdateManifest
import com.AARCNoa.zhiyou.update.UpdateResult
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.AARCNoa.zhiyou.features.core.BaseFeature
import com.AARCNoa.zhiyou.features.core.FeaturesLoader
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.utils.android.showToast
import kotlinx.coroutines.delay
import org.json.JSONArray
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt


// ---------------------------------------------------------------------------
//  Page 0 — Home（品牌头 + 搜索 + 模块状态 + 分类半屏）
// ---------------------------------------------------------------------------

/** 安全判断当前是否运行在微信宿主进程内 */
private fun safeIsHost(): Boolean {
    return runCatching { HostInfo.isHost }.getOrDefault(false)
}

/**
 * 安全获取微信版本信息。
 * - 主体App进程：通过 PackageManager 查询（需 Android 11+ queries 声明）
 * - 微信进程内：直接从 HostInfo 读取当前宿主版本
 * 返回 null 表示未检测到微信。
 */
private fun safeGetWeChatVersionInfo(context: Context): String? {
    // 微信进程内：直接从 HostInfo 读取
    if (safeIsHost()) {
        return runCatching {
            val name = HostInfo.versionName.ifEmpty { return null }
            val code = HostInfo.versionCode
            "微信 $name ($code)"
        }.getOrNull()
    }

    // 主体App进程：通过 PackageManager 查询
    return runCatching {
        val pm = context.packageManager ?: return null
        val info = pm.getPackageInfo(PackageNames.WECHAT, 0)
        val name = info.versionName?.ifEmpty { null } ?: return null
        val code = info.longVersionCode
        "微信 $name ($code)"
    }.getOrNull()
}

/** 主页半屏卡片种类。 */
internal const val HOME_SHEET_ENABLED = "enabled"
internal const val HOME_SHEET_ALL = "all"
internal const val HOME_SHEET_CATEGORIES = "categories"

private const val SEARCH_HISTORY_KEY = "home_feature_search_history"
private const val SEARCH_HISTORY_LIMIT = 10

private fun loadSearchHistory(): List<String> = runCatching {
    val json = JSONArray(WePrefs.getStringOrDef(SEARCH_HISTORY_KEY, "[]"))
    List(json.length()) { json.getString(it) }.filter { it.isNotBlank() }
}.getOrDefault(emptyList())

private fun saveSearchHistory(history: List<String>) {
    val json = JSONArray()
    history.forEach(json::put)
    WePrefs.putString(SEARCH_HISTORY_KEY, json.toString())
}

/** 主页卡片间距：品牌头 → 激活 → 统计 → 信息，四段共用这一个值。 */
private val HOME_CARD_GAP = 14.dp

/**
 * 主页的一个卡片位：只在卡片上方留 [HOME_CARD_GAP]。
 *
 * 三张卡全部从这里发出，间距只有一处定义；以后调整主页节奏改这一个常量即可。
 */
private fun LazyListScope.homeCard(key: String, content: @Composable () -> Unit) {
    item(key = key) {
        Column {
            Spacer(Modifier.height(HOME_CARD_GAP))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomePager(
    onOpenModuleHealth: () -> Unit,
    // 开关的即时状态（主页搜索列表与半屏清单共用一份，由 SettingsActivity 持有）：
    // 点完 Switch 只改功能对象/偏好，没有这份 Compose 状态界面不会立刻动。
    switchStates: SnapshotStateMap<String, Boolean>,
    // 半屏卡片由 MainPagerScreen 绘制：它必须画在底部导航**之后**（即盖住导航栏），
    // 所以分类跳转也交给那边处理，主页只负责「点哪一格」。
    onOpenSheet: (String) -> Unit = {},
    // 半屏是否展开。开关只在半屏里改，收起时重算一次计数即可：
    // 之前用 remember{} 缓存，改完开关回到主页数字还是旧的。
    sheetOpen: Boolean = false,
) {
    val context = LocalContext.current
    // 分子分母同源：都不含内部服务与当前版本不适用项，否则分母会凭空多出几十项。
    val toggleable = toggleableFeatures
    val enabledCount = remember(sheetOpen) {
        toggleable.count { it.effectiveEnabled }
    }
    val totalCount = remember { toggleable.size }

    // 搜索默认收起（右上角一个图标）：点开才展开输入框并自动弹键盘。
    var searchExpanded by remember { mutableStateOf(false) }
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searching = query.isNotBlank()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var searchHistory by remember { mutableStateOf(loadSearchHistory()) }

    fun recordSearch(rawQuery: String) {
        val term = rawQuery.trim()
        if (term.isEmpty()) return
        searchHistory = (listOf(term) + searchHistory.filterNot { it.equals(term, ignoreCase = true) })
            .take(SEARCH_HISTORY_LIMIT)
        saveSearchHistory(searchHistory)
    }

    val searchableItems = remember {
        // 搜索**显式排除「密友功能」分类**：避免随便搜一个词就把密友入口暴露出来。
        // 内部 API 与当前版本不适用项已由 toggleableFeatures 排除。
        toggleable.filter { item -> "密友功能" !in item.categories }
    }
    val filteredItems = remember(query) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
    }

    // 展开后请求焦点 → 键盘自动弹出。
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // 返回键：有内容先清空，空了再收起搜索框。
    BackHandler(enabled = searchExpanded) {
        if (searching) {
            recordSearch(query)
            queryState.clearText()
        } else {
            searchExpanded = false
            keyboard?.hide()
        }
    }

    // 设备信息：微信版本与「激活框架」在重组间保持稳定。
    // 框架名来自跨进程缓存（模块在微信进程加载时写入），不再依赖模块 App 是否打开过。
    val wechatVersion = remember { safeGetWeChatVersionInfo(context) }
    val framework = remember { HostFramework.display() }

    // 更新提示：先读缓存（上次检查的结果），再按 24 小时节流静默查一次。
    // 失败不弹不改界面 —— 这里只是顺带提醒，报错交给设置页的手动检查。
    var availableUpdate by remember { mutableStateOf(UpdateChecker.cachedAvailable()) }
    var updateDialog by remember { mutableStateOf<UpdateDialogState>(UpdateDialogState.Hidden) }
    LaunchedEffect(Unit) {
        // 直接用 check() 的返回值：再读一次 cachedAvailable() 是本该一致的第二条来源，
        // 真出现「弹窗说有新版、行上没提示」时反而不容易查。
        availableUpdate = UpdateChecker.check() as? UpdateResult.Available
    }

    MiuixListScaffold(title = "", showTopBar = false) {
        // ---- 品牌头：左「知友」+ 右搜索图标 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchExpanded) {
                    SearchField(
                        state = queryState,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        label = "搜索功能",
                        closeDescription = "收起搜索",
                        onClose = {
                            if (searching) {
                                recordSearch(query)
                                queryState.clearText()
                            } else {
                                searchExpanded = false
                                keyboard?.hide()
                            }
                        },
                        onSearch = {
                            recordSearch(query)
                            keyboard?.hide()
                        },
                    )
                } else {
                    Text(
                        text = BuildConfig.BRAND,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = WxPalette.textPrimary(),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { searchExpanded = true }) {
                        Icon(
                            imageVector = OutlinedIcons.Search,
                            contentDescription = "搜索功能",
                            tint = WxPalette.textPrimary(),
                        )
                    }
                }
            }
        }

        if (!searchExpanded) {
            // ---- 三张卡共用同一套模板与同一个间距 ----
            // 原来三段间距是 10 / 16 / 26，看起来像三块互不相干的内容；现在等距排列。
            homeCard("activation") { ActivationCard(onOpenHealth = onOpenModuleHealth) }
            // AI 配置是模块级共享能力：紧贴「模块已激活」放，位置固定、只此一处。
            homeCard("ai") { AiActivationCard() }
            homeCard("stats") {
                StatsCard(
                    enabled = enabledCount,
                    total = totalCount,
                    categories = FEATURE_CATEGORIES.size,
                    onOpen = onOpenSheet,
                )
            }
            homeCard("info") {
                InfoCard(
                    wechatVersion = wechatVersion,
                    framework = framework,
                    update = availableUpdate?.manifest,
                    onOpenUpdate = {
                        availableUpdate?.let {
                            updateDialog = UpdateDialogState.Available(it.manifest, it.asset)
                        }
                    },
                )
            }
        } else {
            // ---- 搜索历史 / 搜索结果：直接替换主页内容 ----
            when {
                !searching && searchHistory.isNotEmpty() -> {
                    item(key = "search_history_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = OutlinedIcons.History,
                                contentDescription = null,
                                tint = WxPalette.textSecondary(),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "搜索历史",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = WxPalette.textPrimary(),
                            )
                            Spacer(Modifier.weight(1f))
                            SheetTextAction(
                                text = "清空记录",
                                onClick = {
                                    searchHistory = emptyList()
                                    WePrefs.remove(SEARCH_HISTORY_KEY)
                                },
                            )
                        }
                    }
                    item(key = "search_history_chips") {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        ) {
                            searchHistory.forEach { term ->
                                Text(
                                    text = term,
                                    fontSize = 13.sp,
                                    color = WxPalette.textPrimary(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(end = 8.dp, bottom = 8.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(WxPalette.cardBackground())
                                        .clickable {
                                            queryState.edit { replace(0, length, term) }
                                            recordSearch(term)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                filteredItems.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (searching) "未匹配到任何相关功能" else "输入关键词搜索功能",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                else -> {
                    itemsIndexed(filteredItems, key = { _, item -> item.name }) { index, item ->
                        CatRow(
                            item = item,
                            checked = switchStates[item.name]
                                ?: (item.effectiveEnabled == true),
                            onCheckedChange = { switchStates[item.name] = it },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                                .groupedCardItem(index, filteredItems.size, dividerStart = 14.dp),
                        )
                    }
                }
            }
        }

        // ---- 底部留白 ----
        item {
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }

    // 首页只从「模块版本」那行进弹窗，不会出现「失败」态，因此不给重试。
    UpdateDialogHost(
        state = updateDialog,
        onDismiss = { updateDialog = UpdateDialogState.Hidden },
    )
}

/**
 * 半屏卡片宿主：由 MainPagerScreen 在底部导航**之后**调用，所以遮罩与卡片都盖在导航栏之上。
 *
 * 两级内容共用**同一张**卡片，不再二次跳页：
 * - 一级：「已启用 / 全部功能 / 分类」三份清单；
 * - 二级：某个分类的功能清单（可直接开关），返回即回到一级列表。
 */
@Composable
internal fun HomeSheetHost(
    kind: String?,
    onDismiss: () -> Unit,
    switchStates: SnapshotStateMap<String, Boolean>,
) {
    if (kind == null) return

    // 与主页、搜索共用同一份清单：不含内部 API 服务与当前版本不适用项。
    val allItems = userVisibleFeatures
    // null = 一级列表；非空 = 已进入该分类（二级）。kind 变化时复位。
    var category by remember(kind) { mutableStateOf<String?>(null) }
    var pendingFeatureName by remember(kind) { mutableStateOf<String?>(null) }

    // 二级页面存在时优先消费返回键：先回分类列表，而不是整个收起。
    BackHandler(enabled = category != null) {
        category = null
        pendingFeatureName = null
    }

    val listState = rememberLazyListState()
    LaunchedEffect(category, pendingFeatureName) {
        val opened = category
        val target = pendingFeatureName
        if (opened == null || target == null) {
            listState.scrollToItem(0)
        } else {
            val items = allItems.filter { opened in it.categories }
            val index = items.indexOfFirst { it.name == target }
            if (index >= 0) {
                val masterSwitchOffset = if (opened == SwitchFeature.CATEGORY_WITH_MASTER_SWITCH) 1 else 0
                listState.scrollToItem(index + masterSwitchOffset)
            }
        }
    }

    // 分类统计：进入/退出二级分类时重算。
    // key 里必须带 category —— 只写 kind 的话，在二级改完开关退回一级，
    // 「已启用 x/y」还是进二级之前那个数字（半屏收起重开才更新）。
    val categoryStats = remember(kind, category) {
        if (kind != HOME_SHEET_CATEGORIES) {
            emptyMap()
        } else {
            FEATURE_CATEGORIES.associate { entry ->
                val ofCat = allItems.filter { entry.first in it.categories }
                entry.first to (
                    ofCat.count { (it as? SwitchFeature)?.effectiveEnabled == true } to ofCat.size
                    )
            }
        }
    }

    BottomSheetHost(
        onDismiss = onDismiss,
        header = {
            val opened = category
            if (opened == null) {
                val title = when (kind) {
                    HOME_SHEET_ENABLED -> "已启用功能"
                    HOME_SHEET_ALL -> "全部功能"
                    else -> "分类"
                }
                val count = when (kind) {
                    // allItems 已排除内部 API 与当前版本不适用项，分子分母同源。
                    HOME_SHEET_ENABLED ->
                        allItems.count { (it as? SwitchFeature)?.effectiveEnabled == true }
                    HOME_SHEET_ALL -> allItems.size
                    else -> FEATURE_CATEGORIES.size
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = WxPalette.textPrimary(),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = count.toString() + " 项",
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                    Spacer(Modifier.width(12.dp))
                    SheetTextAction("关闭", onDismiss)
                }
            } else {
                // 二级：只留标题（放大），没有任何图标/进度条/概述卡。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetTextAction(
                        text = "\u2039 分类",
                        onClick = {
                            category = null
                            pendingFeatureName = null
                        },
                        fontSize = 14,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = opened,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = WxPalette.textPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SheetTextAction("关闭", onDismiss)
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            // 间距由各段自己给：分组卡内部必须紧贴（有间隙就断成一张张小卡），
            // 分类列表则保留 6dp 间隔（它是没有卡片背景的裸行）。
            verticalArrangement = Arrangement.Top,
        ) {
            val current = category
            when {
                current != null -> CategoryFeatureItems(
                    category = current,
                    allItems = allItems,
                    switchStates = switchStates,
                )

                kind == HOME_SHEET_CATEGORIES ->
                    itemsIndexed(FEATURE_CATEGORIES, key = { _, entry -> entry.first }) { index, entry ->
                        val name = entry.first
                        val stat = categoryStats[name]
                        val on = stat?.first ?: 0
                        val total = stat?.second ?: 0
                        Column(
                            modifier = Modifier.padding(
                                top = if (index == 0) 4.dp else 0.dp,
                                bottom = if (index == FEATURE_CATEGORIES.lastIndex) 0.dp else 6.dp,
                            ),
                        ) {
                            CategoryRow(
                                icon = entry.second,
                                title = name,
                                subtitle = if (total > 0) "已启用 $on/$total" else "暂无功能",
                                // 图标统一微信绿：分类靠名字区分，不再用八种彩色各说各话。
                                tint = WxPalette.Primary,
                                progress = rememberGrowNumber(
                                    if (total > 0) on.toFloat() / total else 0f,
                                    durationMs = 600 + index * 60,
                                ),
                                onClick = {
                                    pendingFeatureName = null
                                    category = name
                                },
                            )
                        }
                    }

                else -> FeatureSheetItems(
                    items = if (kind == HOME_SHEET_ENABLED) {
                        allItems.filter { (it as? SwitchFeature)?.effectiveEnabled == true }
                    } else {
                        allItems
                    },
                    onPick = { feature ->
                        pendingFeatureName = feature.name
                        category = feature.categories.firstOrNull() ?: "未分类"
                    },
                )
            }
        }
    }
}

/**
 * 二级：某个分类的功能清单，直接开关。
 *
 * 密友分类额外补一行总开关——原来它只存在于已删除的分类详情页里，
 * 不补的话用户在模块里就无法关闭这个总开关。
 */
private fun LazyListScope.CategoryFeatureItems(
    category: String,
    allItems: List<BaseFeature>,
    switchStates: SnapshotStateMap<String, Boolean>,
) {
    // allItems 已排除内部 API 与当前版本不适用项，这里只按分类筛。
    val items = allItems.filter { category in it.categories }
    if (items.isEmpty()) {
        item {
            Text(
                text = "本分类下暂无功能",
                fontSize = 14.sp,
                color = WxPalette.textSecondary(),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
        return
    }
    if (category == SwitchFeature.CATEGORY_WITH_MASTER_SWITCH) {
        item(key = "__master_switch__") {
            MasterSwitchRow(modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    itemsIndexed(items, key = { _, feature -> feature.name }) { index, feature ->
        // 行间分隔线由 groupedCardItem 自己画（一张卡只一条），这里不再叠加 SoftDivider。
        CatRow(
            item = feature,
            checked = switchStates[feature.name]
                ?: ((feature as? SwitchFeature)?.effectiveEnabled == true),
            onCheckedChange = { switchStates[feature.name] = it },
            modifier = Modifier.groupedCardItem(index, items.size, dividerStart = 14.dp),
        )
    }
    // 密友分类额外挂状态诊断：以前它是一张独立页，从模块自检里二级进入，
    // 用户要先跑完整个自检才能看到密友状态。诊断本质是「密友设置的一部分」，
    // 直接并进这张清单，展开也只在本半屏内，不再跳页。
    if (category == SwitchFeature.CATEGORY_WITH_MASTER_SWITCH) {
        item(key = "__diag__") {
            HiddenContactsDiagSection(modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 密友功能分类总开关（与功能自身开关状态互不影响）。 */
@Composable
private fun MasterSwitchRow(modifier: Modifier = Modifier) {
    var enabled by remember { mutableStateOf(Preferences.hiddenContactsMasterEnabled) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .groupedCardItem(0, 1)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "密友功能总开关",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = WxPalette.textPrimary(),
            )
            Text(
                text = "关闭后本分类下所有功能都不生效（各开关状态保留）",
                fontSize = 11.sp,
                color = WxPalette.textSecondary(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            Preferences.hiddenContactsMasterEnabled = it
        })
    }
}

/** 半屏卡片里的功能清单（点一行进入它所属分类的二级清单）。 */
private fun LazyListScope.FeatureSheetItems(
    items: List<BaseFeature>,
    onPick: (BaseFeature) -> Unit,
) {
    itemsIndexed(items, key = { _, feature -> feature.name }) { index, feature ->
        val on = (feature as? SwitchFeature)?.effectiveEnabled == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 与分类二级清单同一套：一张卡一圈轮廓 + 行间一条线，不再每行一张小卡。
                .groupedCardItem(index, items.size, dividerStart = 14.dp)
                .clickable { onPick(feature) }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) WxPalette.Ok else WxPalette.DotOff),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = feature.name,
                    fontSize = 14.sp,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = feature.categories.firstOrNull() ?: "未分类",
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                )
            }
            Text(
                text = if (on) "已启用" else "关闭",
                fontSize = 11.sp,
                color = if (on) WxPalette.Ok else WxPalette.textSecondary(),
            )
            Text(
                text = "\u203A",
                fontSize = 18.sp,
                color = WxPalette.textTertiary(),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 主页统一卡片模板：白底 + 1dp 描边 + 20dp 圆角 + 统一内边距。
 *
 * 激活卡、统计卡、信息卡都套这一层——圆角、描边、左右内边距只在这里定义一次，
 * 之前三张卡各写一套数值，改一处就会跟另外两张对不齐。
 */
@Composable
private fun HomeCard(
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .cardSurface(RoundedCornerShape(WxPalette.CardRadius))
            .padding(contentPadding),
        content = content,
    )
}

@Composable
private fun ActivationCard(onOpenHealth: () -> Unit) {
    var resolving by remember { mutableStateOf(FeaturesLoader.isResolving) }
    var progress by remember { mutableStateOf(FeaturesLoader.resolutionProgress) }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var wasResolving = FeaturesLoader.isResolving
        while (true) {
            delay(500)
            val nowResolving = FeaturesLoader.isResolving
            resolving = nowResolving
            progress = FeaturesLoader.resolutionProgress
            if (wasResolving && !nowResolving) refreshKey++
            wasResolving = nowResolving
        }
    }
    // 自检数据读的是 DexKit 缓存，不会触发重新扫描，因此可以在组合期直接取；
    // 适配结束后 refreshKey 变化，重新读一次最新锚点。
    val anchors = remember(refreshKey) { ModuleDiagnostics.anchors() }
    val (okCount, totalCount) = ModuleDiagnostics.anchorStats(anchors)
    // 与自检页同一口径：解析不到、且没有「已由新路径替代 / 微信已移除」说明的，才算待适配。
    val pending = anchors.count { it.needsAdaptation() }
    val healthy = pending == 0 && !resolving
    val accent = when {
        resolving -> WxPalette.Warning
        healthy -> WxPalette.Ok
        else -> WxPalette.Warning
    }

    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OutlinedIcons.Check_circle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "模块已激活",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WxPalette.textPrimary(),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when {
                        resolving -> {
                            val p = progress
                            if (p != null) "正在适配微信版本 · ${p.first}/${p.second}"
                            else "正在适配微信版本…"
                        }
                        healthy -> "自检正常 · 已解析 $okCount/$totalCount"
                        else -> "自检：$pending 项待适配 · 已解析 $okCount/$totalCount"
                    },
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 自检入口做成独立按钮：整卡可点会让用户不知道点哪里、也不知道点下去是做什么。
            // 纯文字入口：不再画胶囊底，与卡片里其它文字同一层级，只靠绿色提示可点。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        if (FeaturesLoader.isResolving) {
                            val p = FeaturesLoader.resolutionProgress
                            showToast(
                                if (p != null) "正在适配中（${p.first}/${p.second}），请稍候…"
                                else "正在适配中，请稍候…"
                            )
                        } else {
                            onOpenHealth()
                        }
                    }
                    .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "自检",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.Primary,
                )
                Icon(
                    imageVector = OutlinedIcons.Chevron_right,
                    contentDescription = "查看自检详情",
                    tint = WxPalette.Primary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/**
 * AI 激活卡：与「模块已激活」同一套模板（[HomeCard] + 46dp 图标底 + 胶囊按钮），
 * 放在它正下方——AI 配置是模块级共享能力，入口只保留这一个。
 *
 * 状态口径见 [AiConfig.Status.verified]：配置齐全 **且** 最近一次真实请求成功才算已激活。
 * 任何 AI 功能调用失败都会把状态改成未激活，所以这里在每次 Activity 回到前台时重新读一次。
 */
@Composable
private fun AiActivationCard() {
    val context = LocalContext.current
    val status = AiConfig.status
    val active = status.verified
    val accent = if (active) WxPalette.Ok else WxPalette.Warning

    // AI 调用发生在聊天页，回到设置页时未必同进程同状态：resume 时重读一次偏好。
    DisposableEffect(Unit) {
        val app = context.applicationContext as? Application
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                AiConfig.refreshStatus()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app?.registerActivityLifecycleCallbacks(callback)
        onDispose { app?.unregisterActivityLifecycleCallbacks(callback) }
    }

    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OutlinedIcons.Auto_awesome,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (active) "AI 已激活" else "AI 未激活",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WxPalette.textPrimary(),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = aiStatusSubtitle(status),
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 与激活卡的「自检」同形：纯文字 + 箭头，无底圈。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { showAiConfigDialog(context) }
                    .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "配置",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.Primary,
                )
                Icon(
                    imageVector = OutlinedIcons.Chevron_right,
                    contentDescription = "配置 AI 接口",
                    tint = WxPalette.Primary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/**
 * 三格统计卡：与信息卡同样是白底卡片，三格用竖线分隔。
 *
 * 以前是一整块蓝底 + 白字，和上下两张白卡完全不是一套语言，深色模式下还刺眼；
 * 现在数字/文字统一走 [WxPalette] 的深浅色令牌，每格末尾一个「›」表示可点。
 */
@Composable
private fun StatsCard(
    enabled: Int,
    total: Int,
    categories: Int,
    onOpen: (String) -> Unit,
) {
    // 左右留 4：卡片内边距 4 + 格子内边距 14 = 18，与信息卡的 18dp 左对齐。
    HomeCard(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatTile(Modifier.weight(1f), enabled, "已启用") { onOpen(HOME_SHEET_ENABLED) }
            TileDivider()
            StatTile(Modifier.weight(1f), total, "全部功能") { onOpen(HOME_SHEET_ALL) }
            TileDivider()
            StatTile(Modifier.weight(1f), categories, "分类") { onOpen(HOME_SHEET_CATEGORIES) }
        }
    }
}

/** 统计格之间的竖分割线。 */
@Composable
private fun TileDivider() {
    Box(
        modifier = Modifier
            .height(26.dp)
            .width(1.dp)
            .background(WxPalette.divider()),
    )
}

@Composable
private fun StatTile(
    modifier: Modifier,
    value: Int,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "tilePress",
    )
    // 数字从 0 滚到目标值（不再有「动效开关」，见 AnimFx.kt）。
    val shown = rememberGrowNumber(value.toFloat()).roundToInt()
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            // 平时完全透明（白卡底色），只在按下时浮出一层浅灰，按压反馈不靠常驻色块。
            .background(if (pressed) WxPalette.insetSurface() else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = shown.toString(),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = WxPalette.textPrimary(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "\u203A",
                fontSize = 14.sp,
                color = WxPalette.textTertiary(),
            )
        }
    }
}

/**
 * 模块信息卡：只放用户真正会看的三项——激活框架、微信版本、模块版本。
 * 构建时间、设备型号、系统架构这些只在反馈问题时偶尔需要，不占位置。
 *
 * 「模块版本」那行兼任更新提醒：只有真有新版时才多出一截绿色文字并可点，
 * 没有更新时与平时一致——常驻一个「已是最新」只是噪声。
 */
@Composable
private fun InfoCard(
    wechatVersion: String?,
    framework: String,
    update: UpdateManifest?,
    onOpenUpdate: () -> Unit,
) {
    HomeCard(contentPadding = PaddingValues(0.dp)) {
        InfoRow(
            icon = OutlinedIcons.Extension,
            title = "激活框架",
            content = framework,
            showDivider = true,
        )
        InfoRow(
            icon = OutlinedIcons.Smartphone,
            title = "微信版本",
            content = wechatVersion ?: "未检测到微信",
            showDivider = true,
        )
        InfoRow(
            icon = OutlinedIcons.Info,
            title = "模块版本",
            content = "${BuildConfig.BRAND} ${BuildConfig.VERSION_NAME}",
            accent = update?.let { "发现新版本 ${it.versionName}" },
            onClick = if (update != null) onOpenUpdate else null,
            showDivider = false,
        )
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    content: String,
    showDivider: Boolean,
    /** 副行末尾追加的一截主色文字（目前只有「发现新版本 x.x」）。 */
    accent: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 与分类图标同一套：装饰性图标统一微信绿；颜色只留给状态（激活卡的绿/橙）。
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WxPalette.Primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildAnnotatedString {
                        append(content)
                        if (accent != null) {
                            append(" · ")
                            withStyle(SpanStyle(color = WxPalette.Primary)) { append(accent) }
                        }
                    },
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = OutlinedIcons.Chevron_right,
                    contentDescription = null,
                    tint = WxPalette.textTertiary(),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (showDivider) {
            SoftDivider(start = 54.dp, end = 18.dp, thickness = 0.5.dp, verticalPadding = 0.dp)
        }
    }
}
