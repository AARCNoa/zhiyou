package com.AARCNoa.zhiyou.activity.settings


import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons
import com.AARCNoa.zhiyou.utils.openInSystem
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.aboutlibraries.AboutLibrariesProvider
import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.features.api.core.WeApi
import com.AARCNoa.zhiyou.features.items.beautify.ThemeStore
import com.AARCNoa.zhiyou.features.items.debug.ResetDexCache
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.preferences.exportConfig
import com.AARCNoa.zhiyou.preferences.importConfig
import com.AARCNoa.zhiyou.update.UpdateChecker
import com.AARCNoa.zhiyou.update.UpdateResult
import com.AARCNoa.zhiyou.ui.style.SoftCard
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.content.DropdownField
import com.AARCNoa.zhiyou.ui.content.MiuixSmallTitle
import com.AARCNoa.zhiyou.ui.content.ScrimDialog
import com.AARCNoa.zhiyou.ui.content.SearchField
import com.AARCNoa.zhiyou.ui.utils.theme.AppColorSpec
import com.AARCNoa.zhiyou.ui.utils.theme.AppPaletteStyle
import com.AARCNoa.zhiyou.ui.utils.theme.AppThemeMode
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.fillMaxSize
import com.AARCNoa.zhiyou.ui.style.fxReveal

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

/**
 * 设置页的一组设置：小标题 + 白卡。
 *
 * 原先每组各写一遍 `MiuixSmallTitle + SoftCard(contentPadding = 0, fillMaxWidth())`，
 * 只有「界面」那组带 [fxReveal] 动效——同一屏一半有动效一半没有。现在统一从这里发出，
 * 动效按 [order] 依次错开。
 */
private fun LazyListScope.settingsGroup(
    order: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    item {
        MiuixSmallTitle(text = title, modifier = Modifier.padding(top = 12.dp))
        SoftCard(contentPadding = 0, modifier = Modifier.fxReveal(order), content = content)
    }
}

@Composable
fun SettingsPager(
    onOpenLicense: () -> Unit,
) {
    val context = LocalComponentActivity.current

    var showClearConfirm by remember { mutableStateOf(false) }

    // 更新：这里只读缓存（主页那次自动检查已经写好结果），点「检查更新」才联网。
    // 缓存值放进 state 而不是每次重组现读：检查完成后副标题必须跟着变，
    // 不能指望「弹窗状态变了顺带重组到这一行」。
    var available by remember { mutableStateOf(UpdateChecker.cachedAvailable()) }
    var updateDialog by remember { mutableStateOf<UpdateDialogState>(UpdateDialogState.Hidden) }
    var checking by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()

    fun runUpdateCheck() {
        if (checking) return
        checking = true
        updateDialog = UpdateDialogState.Checking
        updateScope.launch {
            val result = UpdateChecker.check(force = true)
            available = result as? UpdateResult.Available
            updateDialog = when (result) {
                is UpdateResult.Available ->
                    UpdateDialogState.Available(result.manifest, result.asset)
                UpdateResult.UpToDate -> UpdateDialogState.UpToDate
                is UpdateResult.Failed -> UpdateDialogState.Failed(result.reason)
            }
            checking = false
        }
    }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })

    // 顶部标题交给底部导航表达（「设置」已在导航栏里），这里整页只有内容。
    PageScaffold {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp),
      ) {
        // Account info card — shown at top of Settings tab.
        item {
            Spacer(Modifier.height(12.dp))
            ProfileCard()
        }

        // 各分组全部走 settingsGroup：小标题 + 白卡同一套模板，动效按序错开。
        settingsGroup(1, "界面") { ThemeSection() }

        settingsGroup(2, "微信主题") {
            PrefArrow(
                title = "主题工坊",
                summary = "内置 6 款主题：消息主页、通讯录、发现、我与聊天背景五页壁纸 + 对应气泡，支持透明度调节与备份恢复",
                icon = OutlinedIcons.Palette,
                onClick = { ThemeStore.onClick(context) },
            )
        }

        settingsGroup(3, "调试") {
            PrefSwitch(
                key = Preferences.VERBOSE_LOG,
                title = "详细日志",
                summary = "输出高频日志 (这可能会暴露你的隐私信息）",
                icon = OutlinedIcons.Frame_bug,
            )
            PrefSwitch(
                key = Preferences.SHOW_STARTUP_TOAST,
                title = "显示加载完成 Toast",
                summary = "加载完成后提示（仅前台启动）",
                icon = OutlinedIcons.Notifications,
            )
            PrefSwitch(
                key = Preferences.MATCH_GENERIC_WXID_EXP,
                title = "清理消息内容微信 ID 前缀时允许非标准 ID",
                summary = "允许处理不带 'wxid_' 前缀的微信 ID, 可能导致误伤消息原始内容 (实验性)",
                icon = OutlinedIcons.Rule_settings,
            )
        }

        settingsGroup(4, "兼容") {
            PrefSwitch(
                key = Preferences.NO_DEX_RESOLVE,
                title = "禁用版本适配",
                summary = "不弹出 DEX 查找对话框，未适配功能将不会被加载",
                icon = OutlinedIcons.Block,
            )
            PrefArrow(
                title = "重置适配信息",
                summary = "清除 DEX 缓存, 等待下次启动时重新适配",
                icon = OutlinedIcons.Build_circle,
                onClick = { ResetDexCache.onClick(context) },
            )
            PrefSwitch(
                key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                title = "宿主热更新时重新适配",
                summary = "宿主热更新时是否重置 DEX 缓存, 可能导致频繁重新适配 (实验性)",
                icon = OutlinedIcons.Auto_delete,
            )
        }

        settingsGroup(5, "配置") {
            PrefArrow(
                title = "导出配置",
                summary = "将模块配置导出为 JSON",
                icon = OutlinedIcons.Upload,
                onClick = { exportConfig(context) },
            )
            PrefArrow(
                title = "导入配置",
                summary = "从 JSON 导入模块配置; JSON 中的配置将会与现有配置合并, 覆盖所有已存在的配置",
                icon = OutlinedIcons.Download,
                onClick = { importConfig(context) },
            )
            PrefArrow(
                title = "清除配置",
                summary = "清除全部模块配置 (警告: 此操作不可逆!)",
                icon = OutlinedIcons.Delete_forever,
                onClick = { showClearConfirm = true },
            )
        }

        // 更新放「配置」之后、「关于」之前：它既不是配置，也不属于关于。
        settingsGroup(6, "更新") {
            PrefArrow(
                title = "检查更新",
                summary = updateSummary(available),
                icon = OutlinedIcons.Update,
                onClick = { runUpdateCheck() },
            )
        }

        // 「版本 / 激活框架」已从设置里移除：主页底部的模块信息卡已经在展示，
        // 设置页再列一遍属于同一信息两处维护。
        settingsGroup(7, "关于") {
            PrefArrow(
                title = "项目地址",
                summary = PROJECT_URL.removePrefix("https://"),
                icon = OutlinedIcons.Language,
                onClick = {
                    runCatching { Uri.parse(PROJECT_URL).openInSystem(context, useCustomTabs = true) }
                },
            )
            PrefArrow(
                title = "交流群",
                summary = "Telegram 群组：使用问题与更新通知",
                icon = OutlinedIcons.Groups,
                onClick = {
                    runCatching { Uri.parse(GROUP_URL).openInSystem(context, useCustomTabs = true) }
                },
            )
            PrefArrow(
                title = "开源许可",
                summary = "本项目使用的开源库许可",
                icon = OutlinedIcons.License,
                onClick = onOpenLicense,
            )
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
      }
    }

    UpdateDialogHost(
        state = updateDialog,
        onDismiss = { updateDialog = UpdateDialogState.Hidden },
        onRetry = { runUpdateCheck() },
    )
}

/** 设置页「检查更新」的副标题：当前版本在前，有新版才追加后半句。 */
private fun updateSummary(available: UpdateResult.Available?): String {
    val current = "当前 ${BuildConfig.VERSION_NAME}"
    return if (available == null) current else "$current · 有新版本 ${available.manifest.versionName}"
}

// ---------------------------------------------------------------------------
//  Profile card — account info at the top of the Settings tab
// ---------------------------------------------------------------------------

/**
 * 查找微信本地头像缓存文件。
 *
 * 微信把头像缓存在 `<dataDir>/MicroMsg/avatar/` 下：每个账号一个目录，
 * 目录名与文件名均为 md5(username)，文件名形如 `user_<md5>.png`。
 * 命中就直接交给 Coil 加载本地文件，避免每次进设置页都走网络。
 */
private fun findLocalAvatar(wxid: String): java.io.File? {
    val dataDir = runCatching { HostInfo.application.applicationInfo.dataDir }.getOrNull() ?: return null
    val root = java.io.File(dataDir, "MicroMsg/avatar")
    if (!root.isDirectory) return null
    val md5 = runCatching {
        java.security.MessageDigest.getInstance("MD5")
            .digest(wxid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrNull() ?: return null
    java.io.File(root, "$md5/user_$md5.png").takeIf { it.isFile }?.let { return it }
    val dir = java.io.File(root, md5)
    if (dir.isDirectory) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?.let { return it }
    }
    return null
}

@Composable
private fun ProfileCard() {
    val wxId = remember { WeApi.selfWxId }

    // WeChat identity — loaded once from the local DB; doesn't change mid-session.
    /** [avatar] 是 Coil 的 model：优先本地微信头像缓存文件，取不到才回落到网络 URL。 */
    data class WechatIdentity(val nickname: String, val avatar: Any?)

    val identity by produceState(WechatIdentity("", null)) {
        withContext(Dispatchers.IO) {
            val db = com.AARCNoa.zhiyou.features.api.core.WeDatabaseApi
            val nickname = if (db.isReady) {
                db.getSelfProfileField(com.AARCNoa.zhiyou.features.api.core.models.SelfProfileField.NAME, "")
                    ?.toString().orEmpty()
            } else ""
            // 优先本地缓存：进设置页拉网络头像既慢又可能失败（弱网时卡片一片空白）。
            val local = if (wxId.isNotEmpty()) findLocalAvatar(wxId) else null
            val remote = if (local == null && db.isReady && wxId.isNotEmpty()) db.getAvatarUrl(wxId) else ""
            value = WechatIdentity(nickname, local ?: remote.takeIf { it.isNotEmpty() })
        }
    }

    SoftCard(
        contentPadding = 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (identity.avatar != null) {
                AsyncImage(
                    model = identity.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                )
            } else {
                AvatarPlaceholder()
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = identity.nickname.ifEmpty { wxId.ifEmpty { "—" } },
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = WxPalette.textPrimary(),
                )
                if (wxId.isNotEmpty()) {
                    Text(
                        text = wxId,
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(WxPalette.cardBackground()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = OutlinedIcons.Account_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** A miuix dropdown bound to an enum's entries. */
/**
 * 枚举下拉：把「第几个选项」翻译成具体枚举值。
 *
 * 真正的下拉实现（无遮罩）在 [DropdownField]，全模块共用一份。
 */
@Composable
private fun <T> EnumDropdown(
    title: String,
    entries: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    DropdownField(
        title = title,
        items = entries.map(labelOf),
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        onSelectedIndexChange = { onSelected(entries[it]) },
        summary = summary,
        enabled = enabled,
        startAction = icon?.let { { PrefIcon(it) } },
    )
}

@Composable
private fun ThemeSection() {
    EnumDropdown(
        title = "主题模式",
        entries = AppThemeMode.entries,
        selected = ThemeSettings.themeMode,
        labelOf = { it.displayName },
        onSelected = { ThemeSettings.updateThemeMode(it) },
        icon = OutlinedIcons.Brightness_medium,
    )

    var customColor by remember { mutableStateOf(ThemeSettings.customColor) }
    SwitchPreference(
        title = "自定义颜色",
        summary = "使用调色板样式生成配色, 而非 Miuix 默认蓝",
        startAction = { PrefIcon(OutlinedIcons.Palette) },
        checked = customColor,
        onCheckedChange = {
            customColor = it
            ThemeSettings.updateCustomColor(it)
        },
    )

    var showColorPicker by remember { mutableStateOf(false) }
    SeedColorPickerDialog(show = showColorPicker, onDismiss = { showColorPicker = false })

    AnimatedVisibility(visible = customColor) {
        Column {
            var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
            SwitchPreference(
                title = "动态壁纸取色",
                summary = "使用系统壁纸的强调色作为种子\n需系统 Android SDK >= 31",
                startAction = { PrefIcon(OutlinedIcons.Wallpaper) },
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
            AnimatedVisibility(visible = !dynamicWallpaper) {
                BasicComponent(
                    title = "种子颜色",
                    summary = "点击选择配色的种子颜色",
                    startAction = { PrefIcon(OutlinedIcons.Colorize) },
                    onClick = { showColorPicker = true },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(ThemeSettings.seedColor)),
                        )
                    },
                )
            }
            EnumDropdown(
                title = "调色板样式",
                entries = AppPaletteStyle.entries,
                selected = ThemeSettings.paletteStyle,
                labelOf = { it.displayName },
                onSelected = {
                    ThemeSettings.updatePaletteStyle(it)
                    // Keep the stored spec valid for the new style.
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = OutlinedIcons.Style,
            )
            val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
            EnumDropdown(
                title = "颜色规格",
                entries = if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021),
                selected = ThemeSettings.effectiveColorSpec,
                labelOf = { it.displayName },
                onSelected = { ThemeSettings.updateColorSpec(it) },
                enabled = spec2025Supported,
                summary = if (!spec2025Supported) "当前调色板样式仅支持 Material 3 (2021)" else null,
                icon = OutlinedIcons.Contrast,
            )

            var applyToWechat by remember { mutableStateOf(ThemeSettings.applyToWechat) }
            SwitchPreference(
                title = "同时对微信生效",
                summary = "将自定义配色应用到微信本身",
                startAction = { PrefIcon(OutlinedIcons.Sync) },
                checked = applyToWechat,
                onCheckedChange = {
                    applyToWechat = it
                    ThemeSettings.updateApplyToWechat(it)
                    CoroutineScope(Dispatchers.Main).launch { showToastSuspend("重启微信生效") }
                },
            )
        }
    }
}

/** miuix color-picker dialog for the custom seed color; commits to ThemeSettings on confirm. */
@Composable
private fun SeedColorPickerDialog(show: Boolean, onDismiss: () -> Unit) {
    var picked by remember(show) { mutableStateOf(Color(ThemeSettings.seedColor)) }

    ScrimDialog(show = show, title = "自定义颜色", onDismissRequest = onDismiss) {
        ColorPicker(
            color = picked,
            onColorChanged = { picked = it },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "重置",
                onClick = { picked = Color(ThemeSettings.DEFAULT_SEED_COLOR) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "确定",
                onClick = {
                    ThemeSettings.updateSeedColor(picked.toArgb())
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  Preference helper composables
// ---------------------------------------------------------------------------

@Composable
private fun PrefSwitch(
    key: String,
    title: String,
    summary: String,
    icon: ImageVector,
) {
    var checked by remember { mutableStateOf(WePrefs.getBoolOrFalse(key)) }
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = { PrefIcon(icon) },
        checked = checked,
        onCheckedChange = {
            checked = it
            WePrefs.putBool(key, it)
        },
    )
}

@Composable
private fun PrefArrow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        // Informational row: no trailing arrow, no ripple.
        BasicComponent(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
            onClick = onClick,
        )
    }
}

@Composable
private fun PrefIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

// ---------------------------------------------------------------------------
//  Dialogs（全屏遮罩，见 ScrimDropdown.kt）
// ---------------------------------------------------------------------------

@Composable
private fun ClearConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    ScrimDialog(show = show, title = "清除模块配置", onDismissRequest = onDismiss) {
        Text(
            text = "确定清除配置？此操作不可逆。",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = "清除",
                onClick = {
                    onDismiss()
                    CoroutineScope(Dispatchers.IO).launch {
                        showToastSuspend("正在清除...")
                        WePrefs.default.clear()
                        showToastSuspend("清除成功!")
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}


// ---------------------------------------------------------------------------
//  Open-source license screen
// ---------------------------------------------------------------------------

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val libraries = remember {
        Libs.Builder().withJson(AboutLibrariesProvider.ABOUT_LIBRARIES_JSON).build().libraries
    }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val filtered = remember(query, libraries) {
        if (query.isBlank()) libraries
        else libraries.filter { lib ->
            lib.name.contains(query, ignoreCase = true) ||
                    lib.developers.any { it.name?.contains(query, ignoreCase = true) == true } ||
                    lib.description?.contains(query, ignoreCase = true) == true
        }
    }

    PageScaffold(title = "开源许可", onBack = onBack) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp),
      ) {
        item {
            // 先交代本项目的许可，再列依赖：只有一串库名时，看不出「这是个 GPL 二改项目」。
            SoftCard(modifier = Modifier.padding(top = 12.dp), contentPadding = 0) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "知友（zhiyou）",
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "GPL-3.0",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "本项目以 GPL-3.0 发布。下列为构建时依赖的第三方库（含 Gradle 传递依赖）。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            SearchField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索库",
                onClose = { queryState.clearText() },
            )
        }

        item {
            MiuixSmallTitle(
                text = if (query.isBlank()) "${libraries.size} 个库" else "${filtered.size}/${libraries.size} 个库",
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "找不到「$query」的结果",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(filtered, key = { it.uniqueId }) { library ->
                LibraryRow(library, modifier = Modifier.padding(top = 12.dp))
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
      }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryRow(library: Library, modifier: Modifier = Modifier) {
    // 与其他卡片同一套：白底 + 1dp 描边 + 20dp 圆角（原先用 miuix Card，圆角/描边都不同）。
    SoftCard(modifier = modifier, contentPadding = 0) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = library.name,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                library.artifactVersion?.let { version ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = version,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            val author = library.developers.firstOrNull()?.name ?: library.organization?.name
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            library.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (library.licenses.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    library.licenses.forEach { license ->
                        Text(
                            text = license.name,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(WxPalette.cardBackground())
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val PROJECT_URL = "https://github.com/AARCNoa/zhiyou"
private const val GROUP_URL = "https://t.me/zhiyou9"
