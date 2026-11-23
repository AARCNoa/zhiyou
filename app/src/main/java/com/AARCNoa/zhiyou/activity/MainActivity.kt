package com.AARCNoa.zhiyou.activity

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.edit
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Build
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.More_vert
import com.composables.icons.materialsymbols.outlinedfilled.Warning
import com.topjohnwu.superuser.Shell
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.DefaultColumn
import com.AARCNoa.zhiyou.ui.content.IconButton
import com.AARCNoa.zhiyou.ui.content.ScrimDropdownMenu
import com.AARCNoa.zhiyou.ui.content.ScrimMenuItem
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.GitHubIcon
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings
import com.AARCNoa.zhiyou.ui.utils.theme.darkScheme
import com.AARCNoa.zhiyou.ui.utils.theme.lightScheme
import com.AARCNoa.zhiyou.utils.android.getEnabled
import com.AARCNoa.zhiyou.utils.android.setEnabled
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.HostFramework
import com.AARCNoa.zhiyou.utils.hook_status.HookStatus
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.style.cardSurface
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.AARCNoa.zhiyou.ui.style.SoftDivider

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var isLaunchingWeChat = false

    override fun onStop() {
        super.onStop()
        if (isLaunchingWeChat) {
            isLaunchingWeChat = false
            finishAndRemoveTask()
        }
    }

    /**
     * App 端主题：跟随「设置 → 界面 → 主题模式」。
     *
     * 原来默认值是 `isSystemInDarkTheme()`，于是 App 主页只认系统开关：用户在模块里选
     * 「深色模式」而系统仍是浅色时，主页卡片不会变深，而微信内的设置页已经变深 —— 两处观感不一致。
     */
    @Composable
    private fun ModuleTheme(
        darkTheme: Boolean = ThemeSettings.themeMode.resolve(),
        content: @Composable () -> Unit
    ) {
        val colorScheme = if (darkTheme) darkScheme else lightScheme
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
            runCatching { HookStatus.init(this) }
        }

        Shell.getShell()

        setContent {
            ModuleTheme {
                AppContent()
            }
        }
    }

    private data class ActivationState(
        val isActivated: Boolean,
        val title: String,
        val desc: String,
        val color: Color
    )

    @Composable
    private fun AppContent() {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        var showMenu by remember { mutableStateOf(false) }
        var showAboutDialog by remember { mutableStateOf(false) }
        var showHelpDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteTinkerDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteModuleDataDialog by remember { mutableStateOf(false) }
        var showNoRootDialog by remember { mutableStateOf(false) }
        var showModifyHostPkgNameDialog by remember { mutableStateOf(false) }
        var shortcutError by remember { mutableStateOf<String?>(null) }

        var isLauncherIconEnabled by remember {
            mutableStateOf(
                ComponentName(
                    this,
                    "${PackageNames.MODULE}.activity.MainActivityAlias"
                ).getEnabled(this)
            )
        }

        // 宿主包名：改完立即反映到「快捷操作」的副标题上，不用退出重进。
        var hostPkg by remember {
            mutableStateOf(prefs.getString("host_pkg_name", PackageNames.WECHAT)!!)
        }

        @Composable
        fun rememberActivationState(): ActivationState {
            val xposedService by HookStatus.xposedService.collectAsState()
            val isHookEnabled = remember(xposedService) {
                xposedService?.scope?.contains(PackageNames.WECHAT) == true
            }
            // 框架服务（libxposed service）绑定只发生在模块进程，绑定失败不代表模块没注入。
            // 微信进程加载模块时会把「框架 · API」写进跨进程缓存，读它来区分
            // 「本页连不上服务」与「模块根本没被加载」两种情况——原来一律显示「未检测到
            // Xposed 框架」，模块其实在工作时会误导用户。
            // 依赖 xposedService：服务绑定后 HostFramework 会写入更准的版本号，
            // 否则这里会一直停在绑定前读到的旧值。
            val recordedFramework = remember(xposedService) { HostFramework.display() }

            return remember(isHookEnabled, xposedService, recordedFramework) {
                when {
                    isHookEnabled -> ActivationState(
                        isActivated = true,
                        title = "已激活",
                        desc = "${xposedService?.frameworkName} ${xposedService?.frameworkVersion} " +
                                "(${xposedService?.frameworkVersionCode}), API ${xposedService?.apiVersion}",
                        color = WxPalette.Ok
                    )

                    recordedFramework != "未知" -> ActivationState(
                        isActivated = true,
                        title = "已注入",
                        desc = "微信进程内已由 $recordedFramework 加载；本页未能连接框架服务，" +
                                "不影响微信内功能。若微信内功能异常，请确认作用域已勾选微信并重启微信。",
                        color = WxPalette.Warning
                    )

                    else -> ActivationState(
                        isActivated = false,
                        title = "未激活",
                        desc = "未检测到 Xposed 框架。请确认：① 模块已在管理器中启用 " +
                                "② 作用域已勾选微信 ③ 安装或更新后重启微信",
                        color = WxPalette.Danger
                    )
                }
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(WxPalette.pageBackground()),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = BuildConfig.BRAND,
                                fontSize = 28.sp,
                                color = WxPalette.textPrimary(),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "微信增强模块 · ${BuildConfig.VERSION_NAME}",
                                fontSize = 12.sp,
                                color = WxPalette.textSecondary()
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                            .copy(alpha = 0.9f),
                    ),
                    actions = {
                        // 与设置页下拉同一个外壳：全屏遮罩 + 锚定菜单。
                        ScrimDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            alignEnd = true,
                            anchor = {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        MaterialSymbols.OutlinedFilled.More_vert,
                                        contentDescription = "Menu"
                                    )
                                }
                            },
                        ) {
                            ScrimMenuItem("帮助") {
                                showMenu = false
                                showHelpDialog = true
                            }
                            ScrimMenuItem("关于") {
                                showMenu = false
                                showAboutDialog = true
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ① 主状态卡（仿仪表盘：状态胶囊 + 图标 + 主标题 + 底部分栏）
                if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
                    val activationState = rememberActivationState()
                    StatusCard(state = activationState)
                }

                // ② 快捷操作：只剩「打开模块设置」——强行停止并启动微信已移除，
                // 那一步会打断用户正在用的微信，收益远不如风险。
                SectionTitle("快捷操作")
                WhiteCard {
                    ListRow(
                        icon = MaterialSymbols.Outlined.Settings,
                        title = "打开模块设置",
                        subtitle = "打开微信内的模块设置",
                        onClick = {
                            try {
                                startActivity(com.AARCNoa.zhiyou.utils.android.Intent {
                                    setClassName(hostPkg, "${PackageNames.WECHAT}.ui.LauncherUI")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    putExtra(BuildConfig.TAG, "1")
                                })
                                isLaunchingWeChat = true
                            } catch (e: Exception) {
                                shortcutError = e.message ?: "无法打开微信 (包名: $hostPkg)"
                            }
                        }
                    )
                }

                // ③ 模块管理：原来全藏在右上角三点菜单里，现在直接摆在主页。
                SectionTitle("模块管理")
                WhiteCard {
                    ListRow(
                        icon = MaterialSymbols.Outlined.Build,
                        title = "修复模块加载",
                        subtitle = "清除微信热更新缓存，修复模块不加载",
                        onClick = { showConfirmDeleteTinkerDialog = true },
                    )
                    SoftDivider()
                    ListRow(
                        icon = MaterialSymbols.Outlined.Delete,
                        title = "清除模块数据",
                        subtitle = "删除模块功能配置，用于解决闪退",
                        onClick = { showConfirmDeleteModuleDataDialog = true },
                    )
                    SoftDivider()
                    ListRow(
                        icon = if (isLauncherIconEnabled) {
                            MaterialSymbols.Outlined.Visibility_off
                        } else {
                            MaterialSymbols.Outlined.Visibility
                        },
                        title = if (isLauncherIconEnabled) "隐藏桌面图标" else "显示桌面图标",
                        // 主 Activity 声明了 xposed 的 MODULE_SETTINGS，桌面图标关掉后
                        // 仍能从框架管理器（LSPosed「模块设置」）回到这一页，不是单向门。
                        subtitle = "隐藏后仍可从框架管理器打开本页",
                        onClick = {
                            val componentName = ComponentName(
                                this@MainActivity,
                                "${PackageNames.MODULE}.activity.MainActivityAlias"
                            )
                            val newState = !isLauncherIconEnabled
                            componentName.setEnabled(this@MainActivity, newState)
                            isLauncherIconEnabled = newState
                        },
                    )
                    SoftDivider()
                    ListRow(
                        icon = MaterialSymbols.Outlined.Edit,
                        title = "设置宿主包名",
                        subtitle = hostPkg,
                        onClick = { showModifyHostPkgNameDialog = true },
                    )
                }

                if (showModifyHostPkgNameDialog) {
                    var pkgName by remember { mutableStateOf(hostPkg) }

                    AlertDialog(
                        onDismissRequest = { showModifyHostPkgNameDialog = false },
                        title = { Text("设置宿主包名") },
                        text = {
                            DefaultColumn {
                                Text("控制模块首页「打开模块设置」打开的目标微信包名, 适用于通过修改包名而非多用户实现多微信共存的用户")

                                TextField(
                                    label = { Text("宿主包名 (默认为 com.tencent.mm)") },
                                    value = pkgName,
                                    onValueChange = { pkgName = it }
                                )
                            }

                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showModifyHostPkgNameDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showModifyHostPkgNameDialog = false
                                prefs.edit {
                                    putString("host_pkg_name", pkgName)
                                }
                                hostPkg = pkgName
                            }) { Text("确定") }
                        })
                }

                if (showConfirmDeleteTinkerDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/tinker",
                            "/data/data/${PackageNames.WECHAT}/tinker_server",
                            "/data/data/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/0/${PackageNames.WECHAT}/tinker",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/999/${PackageNames.WECHAT}/tinker",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_temp"
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteTinkerDialog = false },
                        title = { Text("修复模块加载") },
                        text = {
                            Text(
                                "本操作将尝试修复微信热更新导致的模块不加载, 执行后请重启微信\n" +
                                        "将清空以下路径的内容, 并将其权限递归设置为 000 以阻止微信重读写, 请确认无误后再继续!\n${
                                            paths.joinToString("\n") { "- $it" }
                                        }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteTinkerDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteTinkerDialog = false
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    paths.forEach { path ->
                                        ProcessBuilder(
                                            "su", "-mm", "-c",
                                            "if [ -d '$path' ]; then " +
                                                    "find '$path' -mindepth 1 -exec rm -rf {} + ; " +
                                                    "chmod -R 000 '$path' ; " +
                                                    "fi"
                                        )
                                            .redirectErrorStream(true)
                                            .start()
                                    }
                                    showToast(this@MainActivity, "操作成功!")
                                }
                            }) { Text("确定") }
                        })
                }

                if (showConfirmDeleteModuleDataDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs",
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs.crc",
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteModuleDataDialog = false },
                        title = { Text("清除模块数据") },
                        text = {
                            Text(
                                "本操作将删除模块的功能配置, 用于解决部分功能导致微信无限闪退, 删除后请重启微信\n将删除以下路径的文件, 请确认无误后再删除!\n${
                                    paths.joinToString("\n") { "- $it" }
                                }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                                if (!(Shell.isAppGrantedRoot() ?: false)) {
                                    showNoRootDialog = true
                                } else {
                                    // if using Shell.cmd or su -c without -mm, the view of /data/user/0 is restricted
                                    paths.forEach { path ->
                                        ProcessBuilder("su", "-mm", "-c", "rm -rf $path")
                                            .redirectErrorStream(true)
                                            .start()
                                    }
                                    showToast(this@MainActivity, "删除成功!")
                                }
                            }) { Text("确定") }
                        })
                }

                if (showNoRootDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoRootDialog = false },
                        title = { Text("未授予 Root 权限") },
                        text = { Text("请授予 Root 权限以执行此操作") },
                        confirmButton = {
                            Button(onClick = {
                                showNoRootDialog = false
                            }) { Text("确定") }
                        })
                }

                shortcutError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { shortcutError = null },
                        title = { Text("操作失败") },
                        text = { Text(error) },
                        confirmButton = {
                            Button(onClick = {
                                shortcutError = null
                            }) { Text("确定") }
                        })
                }

                SectionTitle("相关链接")
                WhiteCard {
                    ListRow(
                        icon = MaterialSymbols.Outlined.Chat,
                        title = "交流群组",
                        subtitle = GROUP_URL.removePrefix("https://"),
                        onClick = {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GROUP_URL)))
                            }
                        },
                    )
                    SoftDivider()
                    ListRow(
                        icon = GitHubIcon,
                        title = "项目地址",
                        subtitle = PROJECT_URL.removePrefix("https://"),
                        onClick = {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
                            }
                        },
                    )
                }
            }

            if (showHelpDialog) {
                AlertDialog(
                    onDismissRequest = { showHelpDialog = false },
                    title = { Text(text = "帮助") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("模块入口", fontWeight = FontWeight.SemiBold)
                            Text("微信 → 我 → 设置 → ${BuildConfig.BRAND} 设置")
                            Text("功能开关、日志与设置都在这里；本应用只是启动器。", fontSize = 13.sp)
                            Text("备用入口", fontWeight = FontWeight.SemiBold)
                            Text("微信首页「＋」→ ${BuildConfig.BRAND}（需开启「首页右上角菜单」）", fontSize = 13.sp)
                            Text("找不到入口", fontWeight = FontWeight.SemiBold)
                            Text("确认框架已启用且作用域包含微信，重启后仍不出现可点「修复模块加载」。", fontSize = 13.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showHelpDialog = false }) {
                            Text("知道了")
                        }
                    },
                )
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = "关于") },
                    text = {
                        Column {
                            Text("${BuildConfig.BRAND} 是一款基于 Xposed 框架的免费微信增强模块")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("版本: ${BuildConfig.VERSION_NAME}")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("确定")
                        }
                    },
                )
            }
        }
    }

    /** 分区标题：灰色小字裸排。 */
    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(start = 6.dp, top = 8.dp),
        )
    }

    /** 分组卡片容器：冷调玻璃底 + 渐变高光描边，与设置页/自检页同一套视觉。 */
    @Composable
    private fun WhiteCard(content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(WxPalette.CardRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cardSurface(shape),
            content = content,
        )
    }

    /** 卡片内一行：圆角图标 + 标题/副标题 + 右箭头。 */
    @Composable
    private fun ListRow(
        icon: ImageVector,
        title: String,
        subtitle: String? = null,
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
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(WxPalette.primarySoft()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = WxPalette.Primary,
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
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
            }
            Text(
                text = "\u203A",
                fontSize = 20.sp,
                color = WxPalette.textTertiary(),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }

    /** 主状态卡：仿「仪表盘」顶部大卡 —— 状态胶囊 + 图标 + 主标题 + 底部分栏。 */
    @Composable
    private fun StatusCard(state: ActivationState) {
        val shape = RoundedCornerShape(WxPalette.CardRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cardSurface(shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "状态",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.textSecondary(),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .background(state.color.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = state.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = state.color,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(state.color.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.isActivated) {
                                MaterialSymbols.OutlinedFilled.Check_circle
                            } else {
                                MaterialSymbols.OutlinedFilled.Warning
                            },
                            contentDescription = null,
                            tint = state.color,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = WxPalette.textPrimary(),
                        )
                        Text(
                            text = state.desc,
                            fontSize = 11.sp,
                            color = WxPalette.textSecondary(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(WxPalette.cardBorder())
                )
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("版本", fontSize = 11.sp, color = WxPalette.textSecondary())
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WxPalette.textPrimary(),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("激活框架", fontSize = 11.sp, color = WxPalette.textSecondary())
                        Text(
                            text = HostFramework.display(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WxPalette.textPrimary(),
                        )
                    }
                }
        }
    }
}

private const val PROJECT_URL = "https://github.com/AARCNoa/zhiyou"
private const val GROUP_URL = "https://t.me/zhiyou9"
