package com.AARCNoa.zhiyou.activity.settings

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete_sweep
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Filter_list
import com.composables.icons.materialsymbols.outlined.More_vert
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Share
import com.composables.icons.materialsymbols.outlined.Vertical_align_bottom
import com.composables.icons.materialsymbols.outlined.Vertical_align_top
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.activity.TransparentActivity
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import com.AARCNoa.zhiyou.utils.crash.CrashLogsManager
import com.AARCNoa.zhiyou.utils.formatBytesSize
import com.AARCNoa.zhiyou.ui.content.BottomSheetHost
import com.AARCNoa.zhiyou.ui.content.ScrimDropdownMenu
import com.AARCNoa.zhiyou.ui.content.ScrimMenuItem
import com.AARCNoa.zhiyou.ui.content.SheetTextAction
import com.AARCNoa.zhiyou.ui.style.SoftCard
import com.AARCNoa.zhiyou.utils.android.copyToClipboard
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.ui.style.WxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.io.path.readText
import androidx.compose.animation.core.tween as animTween

private const val LOGS_TAG = "日志页"

/** Which log kind a page is showing. */
private enum class LogKind { RUN, CRASH }

// ---------------------------------------------------------------------------
//  Parsed models
// ---------------------------------------------------------------------------

/** One run-log entry: a header line plus any continuation (stack-trace) lines folded into [message]. */
private data class RunLogEntry(
    val time: String?,
    val level: Char?,
    val tag: String?,
    val message: String,
)

/** One crash-report section: a "==== Title ====" block and the body lines beneath it. */
private data class CrashSection(
    val title: String,
    val body: String,
)

// WeLogger writes each entry as: "$ts $level/$TAG $tag: $msg"
//   ts    = yyyy-MM-dd HH:mm:ss.SSS
//   level = one of V D I W E A
//   $TAG  = BuildConfig.TAG (the module tag), $tag = caller tag
// e.g. "2026-07-05 14:30:22.123 E/WeKit AggregateChats: something failed"
// Groups: 1=date 2=time(+ms) 3=level 4=moduleTag 5=callerTag 6=message
private val RUN_LOG_REGEX = Regex(
    """^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEAF])/(\S+)\s+([^:]*): (.*)$""",
)

/**
 * Parses raw run-log lines into [RunLogEntry] cards. A line matching [RUN_LOG_REGEX] starts a new
 * card; any other line (stack-trace continuation, multi-line message) folds into the previous card
 * so multi-line entries stay together. Leading orphan lines become metadata-less cards.
 */
private fun parseRunLog(text: String): List<RunLogEntry> {
    val out = ArrayList<RunLogEntry>()
    for (line in text.lineSequence()) {
        if (line.isEmpty() && out.isEmpty()) continue
        val m = RUN_LOG_REGEX.matchEntire(line)
        when {
            m != null -> {
                val (_, time, level, _, tag, msg) = m.destructured
                out.add(
                    RunLogEntry(
                        time = time,
                        level = level.firstOrNull(),
                        tag = tag.trim().ifEmpty { null },
                        message = msg,
                    ),
                )
            }

            out.isNotEmpty() -> {
                val prev = out.removeAt(out.size - 1)
                out.add(prev.copy(message = if (prev.message.isEmpty()) line else prev.message + "\n" + line))
            }

            else -> out.add(RunLogEntry(time = null, level = null, tag = null, message = line))
        }
    }
    return out
}

/**
 * Splits a crash report into [CrashSection] cards. The report format (see CrashInfoCollector) is a
 * sequence of `"===="` fenced blocks: a fence line, a title line, a fence line, then the body up to
 * the next fence. Any preamble before the first section becomes its own untitled card.
 */
private fun parseCrashLog(text: String): List<CrashSection> {
    val lines = text.lines()
    val fence = "========================================"
    val out = ArrayList<CrashSection>()

    var i = 0
    val preamble = StringBuilder()
    // Collect anything before the first fenced title as a preamble card.
    while (i < lines.size && !(lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence)) {
        preamble.appendLine(lines[i]); i++
    }
    val pre = preamble.toString().trim()
    if (pre.isNotEmpty()) out.add(CrashSection(title = "", body = pre))

    while (i < lines.size) {
        // Expect: fence / title / fence / body...
        if (lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence) {
            val title = lines[i + 1].trim()
            i += 3
            val body = StringBuilder()
            while (i < lines.size && !(lines[i] == fence && i + 2 < lines.size && lines[i + 2] == fence)) {
                body.appendLine(lines[i]); i++
            }
            out.add(CrashSection(title = title, body = body.toString().trim()))
        } else {
            i++
        }
    }
    return out
}

// ---------------------------------------------------------------------------
//  File operations: share (FileProvider) + save (SAF)
// ---------------------------------------------------------------------------

/**
 * Shares a log file as a text/plain attachment. Uses WeChat's built-in FileProvider authority
 * (`<host>.external.fileprovider`, whose paths cover external + root storage) since this activity
 * runs inside the host process. Falls back to sharing the file's text inline if the provider throws.
 */
private fun shareLogFile(context: Context, file: Path) {
    val f = file.toFile()
    val authority = "${HostInfo.packageName}.external.fileprovider"
    val sendIntent = runCatching {
        val uri = FileProvider.getUriForFile(context, authority, f)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrElse {
        WeLogger.w(LOGS_TAG, "FileProvider share failed, falling back to inline text", it)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            putExtra(Intent.EXTRA_TEXT, runCatching { file.readText() }.getOrDefault(""))
        }
    }
    val chooser = Intent.createChooser(sendIntent, "分享日志")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }
        .onFailure { WeLogger.e(LOGS_TAG, "failed to launch share chooser", it) }
}

/**
 * Opens the system document creator so the user can save a copy of [file] wherever they choose,
 * mirroring the config-export flow in SettingsActivity.
 */
private fun saveLogFile(context: Context, file: Path) {
    TransparentActivity.launch(context) {
        val launcher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri == null) {
                finish(); return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        file.toFile().inputStream().use { it.copyTo(out) }
                    }
                }.onFailure {
                    WeLogger.e(LOGS_TAG, "failed to save log", it)
                    showToastSuspend("保存失败!")
                }.onSuccess { showToastSuspend("保存成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        launcher.launch(file.name)
    }
}

private val LOG_TABS = listOf("运行日志" to LogKind.RUN, "崩溃日志" to LogKind.CRASH)

// ---------------------------------------------------------------------------
//  Page 2 — Logs
// ---------------------------------------------------------------------------

@Composable
fun LogsPager() {
    val context = LocalComponentActivity.current
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val kind = LOG_TABS[selectedTab].second

    // One LazyListState per tab, retained across refreshes so scroll position survives a reload.
    val runListState = rememberLazyListState()
    val crashListState = rememberLazyListState()
    val listState = if (kind == LogKind.RUN) runListState else crashListState

    // Bumping this key forces the visible tab to re-list its files and re-read the selection.
    var refreshKey by remember { mutableIntStateOf(0) }
    // The file currently selected in the visible tab, hoisted so the toolbar can share/save it.
    var currentFile by remember { mutableStateOf<Path?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 文件列表与选择也提到这一层：选择器已经搬到顶部图标那一行，内容区不再自己管。
    // 按 kind 分别存：Crossfade 切换时旧 Tab 还会淡出一会儿，共用一份会让它闪一下空态或换文件。
    val filesByKind = remember { mutableStateMapOf<LogKind, List<Path>>() }
    val selectedByKind = remember { mutableStateMapOf<LogKind, Int>() }
    val selectedIndex = selectedByKind[kind] ?: 0

    // (Re)list files whenever the tab is shown or a refresh is requested.
    LaunchedEffect(kind, refreshKey) {
        val result = withContext(Dispatchers.IO) {
            when (kind) {
                LogKind.RUN -> WeLogger.allLogFiles
                LogKind.CRASH -> CrashLogsManager.allCrashLogs
            }
        }
        filesByKind[kind] = result
        if ((selectedByKind[kind] ?: 0) >= result.size) selectedByKind[kind] = 0
    }

    val fileNames = remember(filesByKind[kind]) { filesByKind[kind].orEmpty().map { it.name } }
    val fileLabels = remember(filesByKind[kind]) {
        filesByKind[kind].orEmpty().map {
            "${it.name}  ·  ${formatBytesSize(runCatching { it.fileSize() }.getOrDefault(0))}"
        }
    }

    // 顶栏已删除，这个 behavior 只被 PullToRefresh 用于计算指示器偏移。
    // 关键在 canScroll = { false }：PullToRefresh 内部会无条件调用
    // scrollBehavior.nestedScrollConnection.onPreScroll，把纵向手势交给它处理；
    // 这里返回 false 后该连接直接返回 Zero，滚动手势才会继续传给 LazyColumn。
    // （只把 heightOffsetLimit 设为 0 不够 —— 它仍会被 onPostScroll 的回落逻辑消耗一部分。）
    val scrollBehavior = MiuixScrollBehavior(
        state = remember { TopAppBarState(0f, 0f, 0f) },
        canScroll = { false },
    )
    val barBackdrop = rememberLayerBackdrop()

    // 顶栏删除后，原来挂在顶栏右侧的三点菜单（刷新/转到顶部/转到底部/清空）搬到这里。
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {},
        popupHost = {},
    ) { innerPadding ->
        // 顶栏已删除：标题、分享/保存/三点、以及日志类型 Tab 全部下移到内容区顶部，
        // 页面最上方不再有一条横跨全宽的色带。状态栏高度由 padding 补。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 文件选择器在最左，和右侧的图标同一行：顶部只占一行。
                FilePill(
                    names = fileNames,
                    labels = fileLabels,
                    selectedIndex = selectedIndex.coerceIn(
                        0,
                        (filesByKind[kind].orEmpty().size - 1).coerceAtLeast(0),
                    ),
                    onSelected = { selectedByKind[kind] = it },
                )
                Spacer(Modifier.weight(1f))

                // 日志类型（运行 / 崩溃）原来是一整条 TabRow，占了整整一行高度；
                // 两项的切换不值得这么重的位置，收进右上角一个筛选图标。
                var kindMenuOpen by remember { mutableStateOf(false) }
                ScrimDropdownMenu(
                    expanded = kindMenuOpen,
                    onDismissRequest = { kindMenuOpen = false },
                    alignEnd = false,
                    anchor = {
                        IconButton(onClick = { kindMenuOpen = true }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Filter_list,
                                contentDescription = "日志类型",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                ) {
                    LOG_TABS.forEachIndexed { index, entry ->
                        val isSelected = index == selectedTab
                        ScrimMenuItem(
                            text = entry.first,
                            selected = isSelected,
                            trailing = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Check,
                                        contentDescription = "当前显示",
                                        tint = WxPalette.Primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        ) {
                            kindMenuOpen = false
                            selectedTab = index
                        }
                    }
                }
                IconButton(onClick = {
                    currentFile?.let { shareLogFile(context, it) }
                        ?: scope.launch { showToastSuspend("暂无可分享的日志") }
                }) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Share,
                        contentDescription = "分享",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
                IconButton(onClick = {
                    currentFile?.let { saveLogFile(context, it) }
                        ?: scope.launch { showToastSuspend("暂无可保存的日志") }
                }) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Save,
                        contentDescription = "保存",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
                ScrimDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    alignEnd = true,
                    anchor = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.More_vert,
                                contentDescription = "菜单",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                ) {
                    ScrimMenuItem("刷新", MaterialSymbols.Outlined.Refresh) {
                        menuOpen = false
                        refreshKey++
                    }
                    ScrimMenuItem("转到顶部", MaterialSymbols.Outlined.Vertical_align_top) {
                        menuOpen = false
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                    ScrimMenuItem("转到底部", MaterialSymbols.Outlined.Vertical_align_bottom) {
                        menuOpen = false
                        scope.launch {
                            val end = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(end)
                        }
                    }
                    ScrimMenuItem("清空", MaterialSymbols.Outlined.Delete_sweep) {
                        menuOpen = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                when (kind) {
                                    LogKind.RUN -> WeLogger.clearRunLogs()
                                    LogKind.CRASH -> CrashLogsManager.deleteAllCrashLogs()
                                }
                            }
                            refreshKey++
                        }
                    }
                }
            }
            Crossfade(
                targetState = kind,
                animationSpec = tween(200),
                label = "logKind",
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
            ) { k ->
                LogTabContent(
                    kind = k,
                    listState = if (k == LogKind.RUN) runListState else crashListState,
                    barBackdrop = barBackdrop,
                    scrollBehavior = scrollBehavior,
                    innerPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                    refreshKey = refreshKey,
                    isRefreshing = isRefreshing,
                    files = filesByKind[k].orEmpty(),
                    selectedIndex = selectedByKind[k] ?: 0,
                    listed = filesByKind.containsKey(k),
                    onRefreshingChange = { isRefreshing = it },
                    onRefreshRequested = { refreshKey++ },
                    onCurrentFileChange = { if (k == kind) currentFile = it },
                )
            }
        }
    }
}

@Composable
private fun LogTabContent(
    kind: LogKind,
    listState: LazyListState,
    barBackdrop: LayerBackdrop,
    scrollBehavior: ScrollBehavior,
    innerPadding: PaddingValues,
    refreshKey: Int,
    isRefreshing: Boolean,
    /** 该 Tab 的日志文件（列表与选中项都在 [LogsPager] 里，顶部选择器共用同一份）。 */
    files: List<Path>,
    selectedIndex: Int,
    /** 列表是否已经列过一次：空态要等它，避免「正在读」和「没有日志」之间闪一下。 */
    listed: Boolean,
    onRefreshingChange: (Boolean) -> Unit,
    onRefreshRequested: () -> Unit,
    onCurrentFileChange: (Path?) -> Unit,
) {
    // Parsed content of the selected file (type depends on kind).
    var runEntries by remember(kind) { mutableStateOf<List<RunLogEntry>>(emptyList()) }
    var crashSections by remember(kind) { mutableStateOf<List<CrashSection>>(emptyList()) }
    // 点统计卡上的错误/警告时弹出的级别清单（可单条复制、一键复制、点条目跳转定位）
    var levelSheet by remember(kind) { mutableStateOf<Char?>(null) }
    val scope = rememberCoroutineScope()
    var loading by remember(kind) { mutableStateOf(true) }

    val selectedFile = files.getOrNull(selectedIndex)
    LaunchedEffect(selectedFile) { onCurrentFileChange(selectedFile) }

    // Read + parse the selected file off the main thread. refreshKey re-reads the same file;
    // `listed` gates the empty case so the spinner doesn't flash off before listing finishes.
    LaunchedEffect(selectedFile, refreshKey, listed) {
        loading = true
        if (selectedFile == null) {
            runEntries = emptyList(); crashSections = emptyList()
            // Only settle to "not loading" once listing is done and there is genuinely no file.
            if (listed) {
                loading = false
                onRefreshingChange(false)
            }
            return@LaunchedEffect
        }
        val text = withContext(Dispatchers.IO) { readLog(selectedFile) }
        when (kind) {
            LogKind.RUN -> runEntries = withContext(Dispatchers.Default) { parseRunLog(text) }
            LogKind.CRASH -> crashSections = withContext(Dispatchers.Default) { parseCrashLog(text) }
        }
        loading = false
        onRefreshingChange(false)
    }

    val pullState = rememberPullToRefreshState()
    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefresh(
            // Show the refresh indicator both for user pulls and while a file is being read/parsed,
            // so opening or switching to a large log surfaces the same loading affordance.
            isRefreshing = isRefreshing || loading,
            onRefresh = { onRefreshingChange(true); onRefreshRequested() },
            pullToRefreshState = pullState,
            contentPadding = innerPadding,
            topAppBarScrollBehavior = scrollBehavior,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(barBackdrop)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "picker-gap") { Spacer(Modifier.height(4.dp)) }

                if (files.isEmpty()) {
                    // Only announce "no logs" once listing has finished, so it doesn't flash under the spinner.
                    if (listed) {
                        item(key = "empty-files") { LogsEmpty(if (kind == LogKind.RUN) "暂无运行日志" else "暂无崩溃日志") }
                    }
                } else when (kind) {
                    LogKind.RUN -> {
                        if (runEntries.isEmpty() && !loading) {
                            item(key = "empty-run") { LogsEmpty("此日志文件为空") }
                        }
                        if (runEntries.isNotEmpty()) {
                            // 文件级统计：错误/警告一眼可见，不用滚动逐条找红黄标
                            item(key = "run-stats") {
                                val errCount = runEntries.count { it.level == 'E' }
                                val warnCount = runEntries.count { it.level == 'W' }
                                SoftCard(contentPadding = 14, glow = errCount == 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "本文件共 " + runEntries.size + " 条",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = WxPalette.textPrimary(),
                                            )
                                            Text(
                                                text = "下拉刷新 · 点错误/警告可定位到原文",
                                                fontSize = 11.sp,
                                                color = WxPalette.textSecondary(),
                                            )
                                        }
                                        MiniStat("错误", errCount, WxPalette.Danger, enabled = errCount > 0, onClick = { levelSheet = 'E' })
                                        Spacer(Modifier.width(8.dp))
                                        MiniStat("警告", warnCount, WxPalette.Warning, enabled = warnCount > 0, onClick = { levelSheet = 'W' })
                                    }
                                }
                            }
                        }
                        items(runEntries.size, key = { "run-$it" }) { i -> RunLogCard(runEntries[i]) }
                    }

                    LogKind.CRASH -> {
                        if (crashSections.isEmpty() && !loading) {
                            item(key = "empty-crash") { LogsEmpty("此日志文件为空") }
                        }
                        items(crashSections.size, key = { "crash-$it" }) { i -> CrashSectionCard(crashSections[i]) }
                    }
                }

                item(key = "bottom-inset") { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
            }
        }
        // 级别筛选只在「打开了某个级别」或日志内容变化时重算，避免每次重组都扫一遍全文件。
        val hits = remember(levelSheet, runEntries) {
            levelSheet?.let { lvl -> runEntries.withIndex().filter { it.value.level == lvl } }
        }
        if (hits != null && hits.isNotEmpty()) {
            LogLevelSheet(
                level = levelSheet!!,
                hits = hits,
                onDismiss = { levelSheet = null },
                onJump = { entryIdx ->
                    levelSheet = null
                    // run 条目在 LazyColumn 里的真实 index = 文件选择器(0) + 统计卡(1) + 条目序号
                    scope.launch { listState.animateScrollToItem(entryIdx + 2) }
                },
            )
        }
    }
}

/** 日志级别清单浮层：单条复制 / 一键复制全部 / 点条目滚动定位到原文。 */
@Composable
private fun LogLevelSheet(
    level: Char,
    hits: List<IndexedValue<RunLogEntry>>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val context = LocalContext.current
    val levelName = if (level == 'E') "错误" else if (level == 'W') "警告" else level.toString()
    val levelColor = logLevelColor(level)
    fun copyText(text: String) {
        val ok = copyToClipboard(context, text, "${BuildConfig.BRAND} 日志 $levelName")
        showToast(context, if (ok) "已复制" else "复制失败")
    }
    // overlay = true：日志页内容区在 Crossfade 里、带 padding，就地渲染的 fillMaxSize()
    // 只能填满那一块，状态栏与导航栏盖不到。挂到 decorView 后遮罩才是真正整屏。
    BottomSheetHost(
        onDismiss = onDismiss,
        overlay = true,
        header = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = levelName + " " + hits.size + " 条",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                    modifier = Modifier.weight(1f),
                )
                SheetTextAction(
                    text = "复制全部",
                    onClick = {
                        copyText(
                            hits.joinToString("\n\n") { (idx, e) ->
                                "[" + idx + "] " + (e.time ?: "") + " " + (e.tag ?: "") + "\n" + e.message
                            },
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
                SheetTextAction(text = "关闭", onClick = onDismiss)
            }
        },
    ) {
        Text(
            text = "点条目跳到日志原文位置；「复制」只复制该条完整内容",
            fontSize = 11.sp,
            color = WxPalette.textSecondary(),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            // 分组卡内部必须紧贴，间距由行本身负责（原来是每行一张小卡 + 6dp 间隙）。
            verticalArrangement = Arrangement.Top,
        ) {
            items(hits.size, key = { hits[it].index }) { i ->
                val (entryIdx, entry) = hits[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与设置页清单同一套：一张卡一圈轮廓 + 行间一条线。
                        .groupedCardItem(i, hits.size, dividerStart = 14.dp)
                        .clickable { onJump(entryIdx) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            entry.tag?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WxPalette.textPrimary(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            entry.time?.let {
                                Text(
                                    text = " · " + it,
                                    fontSize = 10.sp,
                                    color = WxPalette.textSecondary(),
                                    maxLines = 1,
                                )
                            }
                        }
                        Text(
                            text = entry.message,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WxPalette.textSecondary(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SheetTextAction(
                        text = "复制",
                        onClick = { copyText((entry.time ?: "") + " " + (entry.tag ?: "") + "\n" + entry.message) },
                        fontSize = 12,
                    )
                    Text(
                        text = "\u203A",
                        fontSize = 18.sp,
                        color = WxPalette.textTertiary(),
                    )
                }
            }
        }
    }
}

/** Reads the full log file (modern devices handle multi-MB logs fine). */
private fun readLog(file: Path): String =
    runCatching { file.readText() }.getOrElse { "读取日志失败: ${it.message}" }
// ---------------------------------------------------------------------------
//  File selector + cards + empty state
// ---------------------------------------------------------------------------

/**
 * 顶部左侧的文件选择器：一个胶囊，显示当前文件名，点开是同一套下拉。
 *
 * 原来是内容区顶部的整张卡（`DropdownField` + 标题 + 摘要），占掉一行高度；
 * 顶部本来就只有几个图标，挪上来之后列表首屏多出约 60dp。
 */
@Composable
private fun FilePill(
    names: List<String>,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    if (labels.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    ScrimDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        alignEnd = false,
        anchor = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(WxPalette.insetSurface())
                    .clickable { expanded = true }
                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = names.getOrNull(selectedIndex) ?: "选择日志文件",
                    fontSize = 12.sp,
                    color = WxPalette.textPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 148.dp),
                )
                Icon(
                    imageVector = MaterialSymbols.Outlined.Expand_more,
                    contentDescription = "选择日志文件",
                    tint = WxPalette.textTertiary(),
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    ) {
        labels.forEachIndexed { index, label ->
            ScrimMenuItem(
                text = label,
                selected = index == selectedIndex,
                trailing = if (index == selectedIndex) {
                    {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check,
                            contentDescription = "当前显示",
                            tint = WxPalette.Primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                },
            ) {
                expanded = false
                onSelected(index)
            }
        }
    }
}

/** Long messages (over this many lines) collapse to a preview with an expand toggle. */
private const val RUN_LOG_COLLAPSE_LINES = 5

@Composable
private fun RunLogCard(entry: RunLogEntry) {
    // Split once; if the message runs past the threshold, show a 5-line preview + expand toggle.
    val lines = remember(entry.message) { entry.message.split("\n") }
    val isLong = lines.size > RUN_LOG_COLLAPSE_LINES
    val head = remember(lines) { lines.take(RUN_LOG_COLLAPSE_LINES).joinToString("\n") }
    val rest = remember(lines) { lines.drop(RUN_LOG_COLLAPSE_LINES).joinToString("\n") }
    var expanded by remember(entry) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = animTween(250),
        label = "chevron",
    )

    SoftCard(
        contentPadding = 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.level?.let { level ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(logLevelColor(level))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(level.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    entry.tag?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WxPalette.textPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    entry.time?.let {
                        Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (isLong) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Expand_more,
                            contentDescription = if (expanded) "折叠" else "展开",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
            if (entry.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Column {
                        Text(
                            text = if (isLong) head else entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (isLong) {
                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Text(
                                    text = rest,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashSectionCard(section: CrashSection) {
    SoftCard(
        contentPadding = 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (section.title.isNotEmpty()) {
                Text(
                    text = section.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
            SelectionContainer {
                Text(
                    text = section.body,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LogsEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 日志统计小格：数量 + 标签，颜色随严重度。 */
@Composable
private fun MiniStat(
    label: String,
    count: Int,
    color: Color,
    enabled: Boolean = false,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = count.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Text(text = label, fontSize = 10.sp, color = WxPalette.textSecondary())
        }
    }
}

/** 日志级别 → 语义色：错误用红灯色，警告用橙，其余走信息/次要文字色（不再自带一套色值）。 */
private fun logLevelColor(level: Char): Color = when (level) {
    'E', 'F', 'A' -> WxPalette.Danger
    'W' -> WxPalette.Warning
    'I' -> WxPalette.Ok
    'D' -> WxPalette.Info
    else -> WxPalette.TextSecondary
}


