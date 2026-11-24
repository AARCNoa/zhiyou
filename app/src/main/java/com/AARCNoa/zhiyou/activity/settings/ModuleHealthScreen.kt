package com.AARCNoa.zhiyou.activity.settings

import android.app.AlertDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.AARCNoa.zhiyou.ui.content.Scrim
import com.AARCNoa.zhiyou.ui.style.SoftDivider
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.style.SoftCard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.AARCNoa.zhiyou.features.api.core.WeConversationApi
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.cache.DexCacheManager
import com.AARCNoa.zhiyou.dexkit.resolution.resolveAllDex
import com.AARCNoa.zhiyou.features.core.BaseFeature
import com.AARCNoa.zhiyou.features.core.FeaturesProvider
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.features.items.contacts.HideContacts
import com.AARCNoa.zhiyou.features.items.moments.BatchDeleteMoments
import com.AARCNoa.zhiyou.utils.android.copyToClipboard
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.crash.ExitReasonRecorder
import com.AARCNoa.zhiyou.utils.TargetProcesses
import android.os.Build
import com.AARCNoa.zhiyou.utils.crash.ProcessTimeline
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import com.AARCNoa.zhiyou.utils.reflection.DexKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块自检（最外层 / 全量）：把整个模块的公共依赖逐项扫描出来；「一键修复」= 体检 → 修复 → 复检 → 前后对比。
 *
 * 覆盖：各功能分类启用概况 / 微信公共 API 层（含完整性自检）/ 脚本运行时全链路 /
 * Hook 安装任务逐项 / 关键类锚点 / 隐藏联系人状态。
 *
 * 每一项都可点开：弹出「状态 + 含义 + 恢复建议」，可修复的项还带一个**动作按钮**
 * （如待重试的 Hook 任务可直接「重试安装」、未解析的微信 API 可「一键修复」）。
 */
@Composable
fun ModuleHealthScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(listOf<HealthItem>()) }
    // 「检测范围」点开的清单浮层：Compose 实现，与页面同风格（系统 AlertDialog 太丑）
    var overlayTitle by remember { mutableStateOf<String?>(null) }
    var overlayItems by remember { mutableStateOf(listOf<HealthItem>()) }
    var lastScan by remember { mutableStateOf("尚未检测") }
    var scanDurationMs by remember { mutableStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var runTitle by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var runSubtitle by remember { mutableStateOf("") }
    var runLog by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }

    fun launchRun(
        title: String,
        stepsProvider: () -> List<HealthStep>,
        showOverlay: Boolean,
        reportTitle: String?,
        replaceItems: Boolean,
        afterRun: (() -> Unit)? = null,
    ) {
        if (running) return
        if (showOverlay) {
            running = true
            runTitle = title
            runProgress = 0f
            runSubtitle = "准备中…"
            runLog = emptyList()
        }
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val steps = runCatching { stepsProvider() }.getOrElse { emptyList() }
            val verbose = showOverlay || reportTitle != null
            if (verbose) SelfCheckLog.start(title, steps.size)
            val collected = mutableListOf<HealthItem>()
            steps.forEachIndexed { index, step ->
                if (showOverlay) runSubtitle = "正在执行：${step.title}"
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { step.run() }.getOrElse { error ->
                        listOf(
                            HealthItem(
                                label = step.title,
                                section = step.title,
                                value = "异常",
                                ok = false,
                                detail = "执行「${step.title}」时抛出异常：\n" + error.toString(),
                            )
                        )
                    }
                }
                collected += outcome
                if (verbose) {
                    outcome.forEach { item ->
                        // 未启用/提示类不是故障：日志统一记「通过」，避免导出日志满屏红叉误导排查
                        SelfCheckLog.step(item.ok || item.inactive, item.label, item.value)
                    }
                }
                if (showOverlay) {
                    runLog = runLog + (step.title to outcome.all { it.ok })
                    runProgress = (index + 1).toFloat() / steps.size.toFloat().coerceAtLeast(1f)
                    delay(120)
                }
            }
            if (replaceItems) items = collected
            SelfCheckLog.end(
                title,
                collected.count { it.ok },
                collected.size,
                skipped = collected.count { !it.ok && it.inactive },
            )
            running = false
            lastScan = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (replaceItems) scanDurationMs = System.currentTimeMillis() - startedAt
            if (reportTitle != null) showReportDialog(context, reportTitle, collected)
            // 运行结束（running 已置 false）后再执行收尾动作，如「修复后重新自检刷新列表」。
            afterRun?.invoke()
        }
    }

    fun rescan() = launchRun("模块自检", { moduleHealthSteps(context) }, showOverlay = true, reportTitle = null, replaceItems = true)

    LaunchedEffect(Unit) { rescan() }

    fun showFilteredList(title: String, list: List<HealthItem>) {
        if (list.isEmpty()) {
            showToast("没有符合条件的项")
            return
        }
        overlayTitle = title
        overlayItems = list
    }

    fun openDetail(item: HealthItem) {
        val detailText = "状态：" + item.value + "\n\n" + item.detail
        val builder = AlertDialog.Builder(context)
            .setTitle(item.label)
            .setMessage(detailText)
            .setNeutralButton("复制") { _, _ ->
                val ok = copyToClipboard(context, item.label + "\n" + detailText, item.label)
                showToast(if (ok) "已复制" else "复制失败")
            }
            .setNegativeButton("关闭", null)
        val act = item.action
        if (act != null) {
            builder.setPositiveButton(item.actionLabel ?: "执行") { _, _ ->
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching { act() }.getOrElse { "执行失败：" + (it.message ?: it.javaClass.simpleName) }
                    }
                    showToast(msg)
                    rescan()
                }
            }
        }
        builder.show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val failItems = items.filter { !it.ok && !it.inactive }
        val offItems = items.filter { !it.ok && it.inactive }
        val passItems = items.filter { it.ok }
        val segments = buildSegments(items)

        PageScaffold(
            title = "模块自检",
            onBack = onBack,
            subtitle = "最后检测 " + lastScan +
                if (scanDurationMs > 0L) " · 耗时 " + formatDuration(scanDurationMs) else "",
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 2.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    HealthHeroCard(
                        pass = passItems.size,
                        fail = failItems.size,
                        off = offItems.size,
                        total = items.size,
                        scanning = running,
                        lastScan = lastScan,
                        wechatVersion = runCatching {
                            com.AARCNoa.zhiyou.utils.HostInfo.versionName +
                                "(" + com.AARCNoa.zhiyou.utils.HostInfo.versionCode + ")"
                        }.getOrDefault("未识别"),
                        moduleVersion = BuildConfig.VERSION_NAME,
                    )
                }
                item {
                    HealthActionPanel(
                        onRescan = { rescan() },
                        onRepair = {
                            launchRun(
                                "一键修复",
                                { moduleRepairSteps(context) },
                                showOverlay = true,
                                reportTitle = "一键修复结果",
                                replaceItems = false,
                                afterRun = { rescan() },
                            )
                        },
                        onCopy = {
                            val text = if (items.isEmpty()) "（尚无检测结果）"
                            else items.joinToString("\n") { it.label + "：" + it.value }
                            val ok = copyToClipboard(context, text, "${BuildConfig.BRAND} 模块自检")
                            showToast(if (ok) "检测结果已复制" else "复制失败")
                        },
                    )
                }
                if (segments.isNotEmpty()) {
                    item {
                        HealthSegmentGrid(segments) { seg ->
                            showFilteredList(
                                "检测段·" + seg.name,
                                items.filter { it.section == seg.name },
                            )
                        }
                    }
                }
                if (items.isEmpty()) {
                    item {
                        SoftCard(contentPadding = 18) {
                            Text("尚未检测", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = WxPalette.textPrimary())
                            Text(
                                "点击上方「重新检测」开始。全部检测在后台线程执行，不会卡界面。",
                                fontSize = 12.sp, color = WxPalette.textSecondary(),
                            )
                        }
                    }
                } else {
                    item {
                        HealthFoldSection(
                            title = "异常项",
                            list = failItems,
                            defaultCollapsed = false,
                            accent = WxPalette.Danger,
                            onOpenAll = { showFilteredList("异常项", failItems) },
                            onOpenItem = { openDetail(it) },
                        )
                    }
                    item {
                        HealthFoldSection(
                            title = "通过项",
                            list = passItems,
                            defaultCollapsed = true,
                            accent = WxPalette.Ok,
                            onOpenAll = { showFilteredList("通过项", passItems) },
                            onOpenItem = { openDetail(it) },
                        )
                    }
                    if (offItems.isNotEmpty()) {
                        item {
                            // 这一类既不需要处理、也不是问题：未启用 / 未采集 / 锚点已由新路径替代，
                            // 外加缓存体积这类与开关无关的提示。放最下面避免干扰判断。
                            HealthFoldSection(
                                title = "无需处理（不是故障）",
                                list = offItems,
                                defaultCollapsed = true,
                                accent = WxPalette.Warning,
                                onOpenAll = { showFilteredList("无需处理项", offItems) },
                                onOpenItem = { openDetail(it) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = "本页覆盖：功能清单 / 关键类锚点 / DexKit 锚点 / 功能活体 / 网络与 CDN / " +
                            "模块冲突 / 隐藏联系人 / 运行状态。标红项通常就是对应功能失效的原因，点开可看含义、建议与可执行动作。",
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }
        }

        overlayTitle?.let { title ->
            HealthListOverlay(
                title = title,
                items = overlayItems,
                onDismiss = { overlayTitle = null },
                onOpenItem = { openDetail(it) },
                onCopyAll = {
                    val text = buildString {
                        append(title).append("（共 ").append(overlayItems.size).append(" 项）\n\n")
                        overlayItems.forEach { item ->
                            append(if (item.ok) "✅ " else if (item.inactive) "○ " else "❌ ")
                                .append(item.label).append('：').append(item.value).append('\n')
                        }
                    }
                    val ok = copyToClipboard(context, text, title)
                    showToast(if (ok) "已复制 ${overlayItems.size} 项" else "复制失败")
                },
            )
        }

        if (running) {
            // 运行中不允许关闭，但要点掉下层点击。
            Box(modifier = Modifier.fillMaxSize()) {
                Scrim(onDismiss = {}, dense = true, dismissible = false)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                        .background(WxPalette.cardBackground())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = runTitle,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = WxPalette.textPrimary(),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = runSubtitle,
                                fontSize = 12.sp,
                                color = WxPalette.textSecondary(),
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = (runProgress * 100).toInt().toString() + "%",
                            style = TextStyle(
                                color = WxPalette.Accent,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(WxPalette.cardBorder()),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(runProgress.coerceIn(0f, 1f))
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(WxPalette.Accent),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(WxPalette.cardBackground())
                            .border(1.dp, WxPalette.cardBorder(), RoundedCornerShape(14.dp))
                            .padding(10.dp),
                    ) {
                        Column {
                            runLog.takeLast(5).reversed().forEach { entry ->
                                Text(
                                    text = (if (entry.second) "✅ " else "❌ ") + entry.first,
                                    fontSize = 11.sp,
                                    color = if (entry.second) WxPalette.textSecondary() else WxPalette.Danger,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                            if (runLog.isEmpty()) {
                                Text(
                                    text = "正在准备检测项…",
                                    fontSize = 11.sp,
                                    color = WxPalette.textSecondary(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 把毫秒格式化为「1.2s」/「350ms」。 */
private fun formatDuration(ms: Long): String = when {
    ms <= 0L -> "—"
    ms < 1000L -> "${ms}ms"
    else -> String.format(Locale.getDefault(), "%.1fs", ms / 1000.0)
}

// ---------------------------------------------------------------------------
//  数据模型
// ---------------------------------------------------------------------------

/** 一个检测项。[section] 用于分组；[detail] 点开后的含义 + 建议；[action] 非空时详情弹窗带动作按钮。 */
private data class HealthItem(
    val label: String,
    val section: String,
    val value: String,
    val ok: Boolean,
    val detail: String,
    val actionLabel: String? = null,
    val action: (() -> String)? = null,
    /**
     * 该功能只是「未启用」，并非故障：不计入异常统计，界面也不标红。
     * 例如隐藏联系人开关关闭、插件总开关关闭、一个插件都没启用。
     */
    val inactive: Boolean = false,
    /**
     * 与「有没有启用」无关的提示：缓存体积、网络回退路径、注入方式未识别、子进程等。
     * 只是提示，报告里单独一档，不混进「无需处理」。
     */
    val hint: Boolean = false,
)

/** 一个检测/修复步骤：产出一批 [HealthItem]。 */
private class HealthStep(val title: String, val run: () -> List<HealthItem>)

/** 报告上限：避免出现「一万字」粘贴不完的情况。 */
private const val MAX_REPORT_CHARS = 3000

private fun showReportDialog(context: Context, title: String, items: List<HealthItem>) {
    val okCount = items.count { it.ok }
    val failItems = items.filter { !it.ok && !it.inactive }
    val offItems = items.filter { !it.ok && it.inactive && !it.hint }
    val hintItems = items.filter { !it.ok && it.inactive && it.hint }
    val sb = StringBuilder()
    sb.append("共 ").append(items.size).append(" 项：通过 ").append(okCount)
        .append("，异常 ").append(failItems.size)
        .append("，无需处理 ").append(offItems.size)
        .append("，提示 ").append(hintItems.size).append("。\n\n")
    if (failItems.isNotEmpty()) {
        sb.append("【异常】需要处理\n")
        failItems.forEach { item ->
            sb.append("❌ ").append(item.label).append('：').append(item.value).append('\n')
        }
        sb.append('\n')
    }
    if (offItems.isNotEmpty()) {
        sb.append("【无需处理】不是故障（未启用 / 未采集 / 锚点已由新路径替代）\n")
        offItems.forEach { item ->
            sb.append("○ ").append(item.label).append('：').append(item.value).append('\n')
        }
        sb.append('\n')
    }
    if (hintItems.isNotEmpty()) {
        sb.append("【提示】与功能开关无关\n")
        hintItems.forEach { item ->
            sb.append("· ").append(item.label).append('：').append(item.value).append('\n')
        }
        sb.append('\n')
    }
    if (failItems.isEmpty() && offItems.isEmpty() && hintItems.isEmpty()) {
        sb.append("全部通过。\n")
    }
    var body = sb.toString()
    if (body.length > MAX_REPORT_CHARS) {
        body = body.take(MAX_REPORT_CHARS) + "\n…（报告过长已截断，可在列表里逐项查看）"
    }
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(body)
            .setNeutralButton("复制") { _, _ ->
                val ok = copyToClipboard(context, body, title)
                showToast(if (ok) "已复制" else "复制失败")
            }
            .setPositiveButton("知道了", null)
            .show()
    }
}

// ---------------------------------------------------------------------------
//  自检步骤（全量）
// ---------------------------------------------------------------------------

private fun moduleHealthSteps(context: Context): List<HealthStep> = listOf(
    // 放第一步：模块更新后最先要回答的就是「这个微信版本上哪些功能失效了」
    HealthStep("失效功能汇总") { anchorImpactSection() },
    HealthStep("功能清单与分类启用概况") { featureOverview() },
    // 用户要的「真实质检」：不看开关开没开，看开了之后到底有没有生效
    HealthStep("功能回执") { featureReceiptSection() },
    HealthStep("关键类锚点") { anchors() },
    HealthStep("DexKit 锚点") { dexAnchorSection() },
    HealthStep("功能活体") { liveSection() },
    HealthStep("网络与 CDN") { networkSection(context) },
    HealthStep("模块冲突") { conflictSection(context) },
    HealthStep("运行环境") { environmentSection(context) },
    HealthStep("存储与缓存") { storageSection(context) },
    HealthStep("隐藏联系人") { hiddenContactsSection() },
    HealthStep("退出原因与主线程卡顿") { lastExitItems(context) },
)

/**
 * 「上次进程退出原因」——用来区分是系统内存回收、崩溃、ANR 还是用户强行停止。
 * 这段信息是判断「模块有没有主动杀微信」的直接依据。
 */
private fun lastExitItems(context: Context): List<HealthItem> {
    val out = mutableListOf<HealthItem>()

    val summary = runCatching { ExitReasonRecorder.summary(context) }.getOrNull()
    val diagnosis = runCatching { ExitReasonRecorder.diagnosis(context) }.getOrNull()
    out += HealthItem(
        "上次进程退出",
        "运行状态",
        summary ?: "无记录（需 Android 11 及以上）",
        true,
        diagnosis ?: ("Android 11 起系统会记录进程退出原因（内存不足回收 / Java 或原生崩溃 / ANR / 强行停止）。\n" +
            "当前还没有可用记录：可能是首次安装、系统版本过低，或系统未保留记录。"),
    )

    val starts = runCatching { ProcessTimeline.todayStartCount(context) }.getOrDefault(0)
    val pss = runCatching { ProcessTimeline.currentPssMb(context) }.getOrDefault(0)
    out += HealthItem(
        "主进程启动次数",
        "运行状态",
        if (starts > 0) "今日 " + starts + " 次" else "无记录",
        true,
        "每次微信进程启动会记 1 次。\n" +
            "若明显多于你手动打开微信的次数，说明进程在后台被反复杀掉或被系统反复拉起。\n" +
            "当前进程内存（PSS）：" + (if (pss > 0) pss.toString() + "MB" else "未知"),
    )

    val recent = runCatching { ProcessTimeline.recent(context, 5) }.getOrDefault(emptyList())
    if (recent.isNotEmpty()) {
        out += HealthItem(
            "最近启动记录",
            "运行状态",
            recent.first(),
            true,
            recent.joinToString("\n"),
        )
    }

    return out
}

private fun featureOverview(): List<HealthItem> {
    val section = "功能清单与分类启用概况"
    // 与功能列表保持同一口径：内部 API 服务（无开关）与当前微信版本不支持的条目都不计入。
    val items = userVisibleFeatures
    val out = mutableListOf<HealthItem>()
    val byCategory = items.groupBy { it.categories.firstOrNull() ?: "未分类" }.toSortedMap()
    val enabledTotal = items.count { (it as? SwitchFeature)?.effectiveEnabled == true }
    val activeCats = byCategory.filterValues { list -> list.any { (it as? SwitchFeature)?.effectiveEnabled == true } }

    // 只输出「总数 + 有启用的分类 + 其余合并一行」，
    // 否则两百多个功能按分类铺开会把自检页拉成十几行。
    out += HealthItem(
        "功能总数", section, items.size.toString() + " 个",
        true,
        buildString {
            append("模块共注册 ").append(items.size).append(" 个功能项，当前已启用 ")
                .append(enabledTotal).append(" 个，分布在 ")
                .append(activeCats.size).append(" / ").append(byCategory.size).append(" 个分类。\n\n")
            append("未启用的功能不逐个列项，其目标是否可用见「DexKit 锚点」。\n\n")
            append("各分类明细（功能数 / 已启用）：\n")
            byCategory.forEach { (cat, list) ->
                append("· ").append(cat).append('：').append(list.size).append(" / ")
                    .append(list.count { (it as? SwitchFeature)?.effectiveEnabled == true }).append('\n')
            }
        },
    )

    activeCats.forEach { (category, list) ->
        val on = list.count { (it as? SwitchFeature)?.effectiveEnabled == true }
        out += HealthItem(
            "分类·" + category, section,
            list.size.toString() + " 个，已启用 " + on,
            true,
            buildString {
                append("该分类共 ").append(list.size).append(" 个功能，已启用 ").append(on).append(" 个。\n\n")
                list.forEach { item ->
                    val e = (item as? SwitchFeature)?.effectiveEnabled == true
                    append(if (e) "✅ " else "⬜ ").append(item.name)
                    if (!e) append("（未启用）")
                    append('\n')
                }
            },
        )
    }

    val silent = byCategory.keys - activeCats.keys
    if (silent.isNotEmpty()) {
        out += HealthItem(
            // 这类不是「通过」：这些分类下一个功能都没开，没有任何东西被验证过。
            "其余 " + silent.size + " 个分类", section, "全部未启用", false,
            "以下分类当前没有任何功能被启用（只统计，不算异常）：\n" + silent.joinToString("、"),
            inactive = true,
        )
    }
    return out
}

/**
 * 锚点影响分析：把「功能 → 锚点」反转成「锚点 → 功能」。
 *
 * 回答两个问题：
 * 1. 哪个锚点失效了？它上面挂了几个功能、具体是哪些？
 * 2. 哪些锚点被多个功能共享（共享锚点失效 = 一串功能同时失效）。
 *
 * 数据全部来自 [ModuleDiagnostics.anchors] 的运行时缓存，不触发 DexKit 扫描。
 */
private fun anchorImpactSection(): List<HealthItem> {
    val section = "失效功能汇总"
    val all = ModuleDiagnostics.anchors()
    if (all.isEmpty()) {
        return listOf(
            HealthItem(
                "锚点清单", section, "无可检查项", false,
                "当前没有注册任何 Dex 委托——模块可能未正常加载，或当前进程不是微信主进程。",
            )
        )
    }

    class Group {
        val features = LinkedHashSet<String>()
        val activeFeatures = LinkedHashSet<String>()

        /** 这些功能在当前微信版本已被判定为「不再支持」，属于宿主改动，不是待修的 bug。 */
        val removedByHost = LinkedHashSet<String>()

        /** 这些功能声明了「该锚点已由新路径替代」，解析失败属于预期。 */
        val supersededFeatures = LinkedHashSet<String>()
        var resolvedAny = false
        var descriptor: String? = null
    }

    val groups = linkedMapOf<String, Group>()
    for (a in all) {
        val g = groups.getOrPut(a.key) { Group() }
        g.features += a.feature
        if (a.active) g.activeFeatures += a.feature
        if (a.unavailableReason != null) g.removedByHost += a.feature
        if (a.supersededNote != null) g.supersededFeatures += a.feature
        if (a.resolved) {
            g.resolvedAny = true
            if (g.descriptor == null) g.descriptor = a.descriptor
        }
    }

    val out = mutableListOf<HealthItem>()

    // 一个锚点只有在「至少一个使用它的功能没声明已替代」时才算真失效
    val broken = groups.filter { (_, g) ->
        !g.resolvedAny && g.features.any { it !in g.supersededFeatures }
    }
    // 统计时同样按功能逐个剔除，避免把已替代的也算进「受影响」
    val affectedFeatures = broken.values
        .flatMap { g -> g.features.filter { it !in g.supersededFeatures } }
        .toSortedSet()
    val removedFeatures = broken.values.flatMap { it.removedByHost }.toSortedSet()
    val needAdapt = affectedFeatures - removedFeatures
    // 「失效锚点 N 个」只算真的要处理的：挂在上面的功能全部已解释（微信移除 / 新路径替代）
    // 的锚点在明细里显示成 ✅，汇总再把它算进来就会出现「说 2 个、只列出 1 个」的对不上。
    val explainedKeys = broken.filterValues { g ->
        g.features.isNotEmpty() && g.features.all { it in g.removedByHost || it in g.supersededFeatures }
    }.keys
    val realBroken = broken.size - explainedKeys.size
    out += HealthItem(
        "影响总览", section,
        when {
            broken.isEmpty() -> "全部锚点已解析"
            realBroken == 0 -> "失效锚点 " + broken.size + " 个 · 均已解释（微信移除或新路径替代）"
            else -> "失效锚点 " + realBroken + " 个 · 待适配 " + needAdapt.size + " 个功能"
        },
        needAdapt.isEmpty(),
        if (broken.isEmpty()) {
            "当前微信版本下，所有已注册的 DexKit 锚点都匹配成功（按缓存结果）。\n\n" +
                "共享锚点 " + groups.count { it.value.features.size >= 2 } + " 个：这些锚点一旦在下次微信更新中漂移，会同时影响挂在其上的多个功能。"
        } else {
            "失效锚点：" + broken.keys.joinToString("、") +
                "\n\n受影响功能（去重 " + affectedFeatures.size + " 个，标★的为当前已开启）：\n" +
                affectedFeatures.joinToString("\n") { f ->
                    "· " + (if (broken.values.any { it.activeFeatures.contains(f) }) "★ " else "") + f +
                        (if (removedFeatures.contains(f)) "（微信版本已移除，非匹配问题）" else "")
                } +
                if (needAdapt.isEmpty()) "" else "\n\n需要重新适配：" + needAdapt.joinToString("、")
        },
    )

    broken.forEach { (key, g) ->
        // 挂在这个锚点上的功能全部是「微信已移除」或「已由新路径替代」→ 不算故障，只作信息展示
        val allExplained = key in explainedKeys
        out += HealthItem(
            "失效·" + key, section, "影响 " + g.features.size + " 个功能", false,
            "锚点 $key 在当前微信版本未解析成功。\n\n挂载功能（" + g.features.size + " 个）：\n" +
                g.features.joinToString("\n") { f ->
                    "· " + f +
                        (if (g.activeFeatures.contains(f)) "（已开启，子能力实际已失效）" else "（未开启）") +
                        (if (g.removedByHost.contains(f)) " · 微信已移除实现" else "")
                } +
                "\n\n定位描述：" + (g.descriptor ?: "未知") +
                "\n\n处理建议：等待特征包更新，或在「DexKit 锚点」检查项里核对该锚点的匹配条件（特征串/参数形状）是否需要针对当前微信版本重新适配。",
            inactive = allExplained,
        )
    }

    val shared = groups.filter { it.value.features.size >= 2 && it.value.resolvedAny }
    if (shared.isNotEmpty()) {
        out += HealthItem(
            "共享锚点", section, shared.size.toString() + " 个 · 挂载 ≥2 功能", true,
            "以下锚点被多个功能共用——失效时会同时影响全部挂载功能：\n\n" +
                shared.entries.joinToString("\n\n") { (key, g) ->
                    "· " + key + "（" + g.features.size + " 个功能）\n  " + g.features.joinToString(" / ")
                },
            inactive = true,
        )
    }

    return out
}

private fun anchors(): List<HealthItem> = listOf(
    anchorItem(
        "聊天页 ChattingUI", "com.tencent.mm.ui.chatting.ChattingUI",
        listOf("密友·禁止进入聊天", "密友·禁止查看资料", "防撤回(消息视图)", "消息已读/聊天注入类功能"),
    ),
    anchorItem(
        "资料页 ContactInfoUI", "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
        listOf("密友·禁止查看资料", "资料页注入类功能"),
    ),
    anchorItem(
        "通讯录 AddressLiveList", "com.tencent.mm.ui.contact.address.AddressLiveList",
        listOf("密友·通讯录隐藏", "隐藏联系人(通讯录)"),
    ),
    anchorItem(
        "朋友圈评论 SnsCommentFooter", "com.tencent.mm.plugin.sns.ui.SnsCommentFooter",
        listOf("隐藏朋友圈互动入口", "朋友圈评论相关功能"),
    ),
    anchorItem(
        "主界面 LauncherUI", "com.tencent.mm.ui.LauncherUI",
        listOf("主屏侧滑栏", "主页 Tab 美化", "主页顶部注入类功能"),
    ),
    anchorItem(
        "wcdb SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase",
        listOf("微信数据库 API(脚本/密友/会话归拢的公共底座)", "数据库监听服务"),
    ),
    anchorItem(
        "wcdb compat SQLiteDatabase", "com.tencent.wcdb.compat.SQLiteDatabase",
        listOf("微信数据库 API 兼容层(同上，二选一命中即可)"),
    ),
)

private fun anchorItem(name: String, className: String, features: List<String>): HealthItem {
    val found = runCatching { Class.forName(className, false, ClassLoaders.MODULE) }.isSuccess
    return HealthItem(
        "锚点·$name", "关键类锚点",
        if (found) "已找到 · 挂载 " + features.size + " 类功能" else "未找到 · 影响 " + features.size + " 类功能", found,
        "类 $className\n\n挂载功能：\n" + features.joinToString("\n") { "· $it" } + "\n\n" + if (found) {
            "在当前微信版本中存在，依赖它的功能有锚点可用。"
        } else {
            "未找到——以上功能在当前微信版本可能已漂移/失效，需要适配（无法自动修复）。"
        },
    )
}

/**
 * DexKit 锚点健康：每个功能用了哪些锚点、解析成功几个。
 *
 * 这是「微信更新后哪些功能要重新适配」的直接清单——功能已开启但锚点未解析，
 * 就等于该能力在当前版本上匹配不到目标类/方法。
 *
 * 只读缓存的解析结果，不会触发新一轮 DexKit 扫描。
 */
private fun dexAnchorSection(): List<HealthItem> {
    val section = "DexKit 锚点"
    val anchors = ModuleDiagnostics.anchors()
    if (anchors.isEmpty()) {
        return listOf(
            HealthItem(
                "锚点清单", section, "无可检查项", false,
                "当前没有注册任何 Dex 委托——模块可能未正常加载，或当前进程不是微信主进程。",
            )
        )
    }
    val out = mutableListOf<HealthItem>()
    val (okCount, total) = ModuleDiagnostics.anchorStats(anchors)
    // 只有「解析不到、且没人解释为什么」的锚点才算待适配。
    // supersededNote = 已由新路径替代，unavailableReason = 微信整个功能都移除了。
    val pendingTotal = anchors.count { it.needsAdaptation() }
    out += HealthItem(
        "锚点总览", section,
        if (pendingTotal == 0) "已解析 $okCount / $total · 无待适配"
        else "已解析 $okCount / $total · 待适配 $pendingTotal",
        pendingTotal == 0,
        "DexKit 的匹配结果会缓存复用。功能已开启但锚点未解析，说明该锚点在当前微信版本上" +
            "匹配不到目标——依赖它的功能会局部或整体失效。\n\n" +
            "已由新路径替代 / 微信整体移除的锚点不计入待适配，明细见各功能的条目。",
    )
    anchors.groupBy { it.feature }.toSortedMap().forEach { (feature, list) ->
        val ok = list.count { it.resolved }
        val active = list.any { it.active }
        val missing = list.filterNot { it.resolved }
        val unavailable = list.firstNotNullOfOrNull { it.unavailableReason }
        // 未解析的锚点全部被解释掉（新路径替代）时，不算故障。
        // 不能写成 `list.all { supersededNote != null }`：正常功能里多数锚点是解析成功的，
        // 那样条件永远不成立，只要有 1 个旧锚点就标红。
        val pendingAnchors = list.count { !it.resolved && it.supersededNote == null }
        val allSuperseded = pendingAnchors == 0
        val detail = buildString {
            append("功能：").append(feature)
            append(if (active) "（运行中）" else "（未启用或未装上）")
            if (unavailable != null) append("｜当前微信版本已不支持")
            append("\n锚点 ").append(list.size).append(" 个，已解析 ").append(ok).append(" 个。\n\n")
            list.forEach { a ->
                append(
                    when {
                        a.resolved -> "✅ "
                        a.supersededNote != null -> "⏭ "
                        else -> "❌ "
                    }
                ).append(a.key)
                val desc = a.descriptor
                if (!desc.isNullOrBlank()) append(" → ").append(desc)
                a.supersededNote?.let { append("（已由新路径替代：").append(it).append("）") }
                append('\n')
            }
            when {
                // 微信把实现删了：这不是「匹配条件写错」，补 matcher 也没用
                unavailable != null ->
                    append("\n原因：").append(unavailable)
                        .append("\n这是微信自身改动，不是匹配条件写错——要恢复只能按新实现重写。")
                allSuperseded ->
                    append("\n说明：上面标 ⏭ 的锚点只留给旧版微信，当前版本由新路径实现，功能不受影响。")
                missing.isNotEmpty() && active ->
                    append("\n该功能已开启但仍有锚点未解析：对应子能力会失效，需要在特征包里补匹配条件。")
            }
        }
        out += HealthItem(
            "锚点·" + feature, section, ok.toString() + "/" + list.size, ok == list.size, detail,
            // 版本已移除 / 已由新路径替代的功能不算故障（不标红），只作为信息展示
            inactive = unavailable != null || allSuperseded || (!active && ok < list.size),
        )
    }
    return out
}

/** 功能活体：不看开关状态，看这项能力在当前微信版本上是否真的能跑。 */
private fun liveSection(): List<HealthItem> =
    ModuleDiagnostics.featureLive().map { live ->
        HealthItem(
            live.label, "功能活体",
            when {
                live.ok -> "正常"
                live.inactive -> "未采集"
                else -> "失效"
            },
            live.ok, live.detail,
            inactive = live.inactive,
            hint = live.inactive,
        )
    }

/**
 * 功能回执：不看开关，看「开了之后到底有没有真的生效」。
 *
 * - 功能自己回报的运行时问题（注入型功能找不到容器、采集为空等，见 [BaseFeature.runtimeIssue]）
 * - 开关开着但 Hook 根本没装上（enable() 安装失败会回滚 isActive）
 *
 * 每一项都可「重启此功能」：禁用再启用会重新跑一遍 onEnable，多数安装/注入类问题能自愈。
 */
private fun featureReceiptSection(): List<HealthItem> {
    val section = "功能回执"
    val broken = FeaturesProvider.ALL_HOOK_ITEMS.filter { feature ->
        (feature as? SwitchFeature)?.isEnabled == true &&
            (runCatching { feature.runtimeIssue() }.getOrNull() != null || !feature.isActive)
    }
    if (broken.isEmpty()) {
        return listOf(
            HealthItem(
                "已开启的功能", section, "未发现异常", true,
                "已开启的功能里，没有「安装失败」或「自报运行时问题」的项。\n" +
                    "判据是功能是否装上、是否自己回报问题，不含「点下去有没有反应」。",
            )
        )
    }
    return broken.map { feature ->
        val issue = runCatching { feature.runtimeIssue() }.getOrNull()
        HealthItem(
            label = feature.name,
            section = section,
            value = if (issue != null) "开着但没生效" else "Hook 未安装",
            ok = false,
            detail = issue ?: ("开关是开的，但 Hook 没有安装成功（启动阶段安装失败），点了不会有任何反应。\n\n" +
                "运行日志里搜「${feature.name}」可以看到失败堆栈；也可以先点下面的「重启此功能」试试。"),
            actionLabel = "重启此功能",
            action = {
                runCatching {
                    feature.disable()
                    feature.enable()
                    "已重启「${feature.name}」。如果是注入型功能（如聊天工具栏），请回到对应界面确认效果；" +
                        "仍然无效的话把日志里该功能的 ERROR 发给作者。"
                }.getOrElse { "重启失败：${it.message}" }
            },
        )
    }
}


/**
 * 网络与 CDN：域名连通性、是否挂代理、图片带不带 Referer 的实际返回。
 *
 * 测试地址取自「批量删除朋友圈」最近一次枚举到的真实图片，因此不会用假数据糊弄。
 */
private fun networkSection(context: Context): List<HealthItem> {
    val url = runCatching { BatchDeleteMoments.lastProbeUrl }.getOrNull()
    return ModuleDiagnostics.network(context, url).map { r ->
        HealthItem(
            r.label, "网络与 CDN",
            if (r.ok) "通过" else if (r.expectFail) "提示" else "异常",
            r.ok, r.detail,
            inactive = r.expectFail,
            hint = r.expectFail,
        )
    }
}

/**
 * 运行环境：系统版本 / CPU 架构 / 当前进程。
 *
 * 这三项决定了「模块能不能装得上」——低版本系统缺 API、x86 设备没有对应 native 库、
 * 子进程里大部分 Hook 本来就不安装。区分清楚可以避免把环境限制误判成功能失效。
 */
private fun environmentSection(context: Context): List<HealthItem> {
    val section = "运行环境"
    val out = mutableListOf<HealthItem>()

    val sdk = Build.VERSION.SDK_INT
    out += HealthItem(
        "Android 版本", section, "API " + sdk, sdk >= 26,
        "当前系统 API " + sdk + "。\n" +
            if (sdk >= 26) "满足模块最低要求（API 26 / Android 8.0）。"
            else "低于模块最低要求（API 26 / Android 8.0），部分能力不可用。",
    )

    val abis = runCatching { Build.SUPPORTED_ABIS.joinToString(", ") }.getOrDefault("未知")
    val abiOk = abis.contains("arm64") || abis.contains("armeabi")
    out += HealthItem(
        "CPU 架构", section, abis, abiOk,
        "设备支持的 ABI：" + abis + "\n" +
            if (abiOk) "模块的 native 组件（arm64-v8a / armeabi-v7a）可正常加载。"
            else "没有 arm ABI（x86 模拟器或纯 x86 设备），native 组件无法加载，依赖它的功能会失败。",
    )

    val main = runCatching { TargetProcesses.isInMain }.getOrDefault(false)
    out += HealthItem(
        "当前进程", section, if (main) "微信主进程" else "子进程", main,
        "进程名：" + runCatching { TargetProcesses.currentName }.getOrDefault("未知") + "\n" +
            if (main) "主进程里模块会安装全部 Hook。"
            else "子进程里只做最小初始化，看到「未解析 / 未安装」属正常现象，不代表功能坏了。",
        inactive = !main,
        hint = !main,
    )

    // 注入方式：运行时识别（比扫管理器包名准），覆盖 LSPatch 内置 / 免 Root 虚拟容器 / Root 框架
    val frameworks = ModuleDiagnostics.installedFrameworks(context)
    val injection = ModuleDiagnostics.detectInjection(context)
    out += HealthItem(
        "注入方式", section, injection.method, injection.kind != "未知",
        injection.detail + "\n\n已装的框架管理器：" +
            (if (frameworks.isEmpty()) "无"
            else frameworks.joinToString("、") { it.label + " v" + it.version }) +
            "\n\n识别列表之外的方案（比如修改版安装包自带的注入器）功能却正常时，" +
            "把本条详情复制反馈给开发者补全识别。",
        inactive = injection.kind == "未知",
        hint = injection.kind == "未知",
    )

    // 微信通知权限：依赖系统提醒的功能需要它
    val notifyEnabled = runCatching {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(true)
    out += HealthItem(
        "微信通知权限", section, if (notifyEnabled) "已开启" else "已关闭", notifyEnabled,
        if (notifyEnabled) "微信可以正常发系统通知。"
        else "微信通知被系统关闭：依赖系统提醒的功能不会弹通知。" +
            "到 系统设置 → 应用 → 微信 → 通知 里打开即可。",
        actionLabel = if (!notifyEnabled) "去设置" else null,
        action = if (!notifyEnabled) {
            {
                runCatching {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "已打开微信通知设置"
                }.getOrElse { "打开失败：" + (it.message ?: it.javaClass.simpleName) }
            }
        } else null,
    )
    return out
}

/** 存储与缓存：数据目录可写性 + 缓存体积。写不进去会导致配置保存失败、日志缺失。 */
private fun storageSection(context: Context): List<HealthItem> {
    val section = "存储与缓存"
    val out = mutableListOf<HealthItem>()

    val files = runCatching { context.filesDir }.getOrNull()
    val writable = files?.let { dir -> runCatching { dir.canWrite() }.getOrDefault(false) } ?: false
    out += HealthItem(
        "模块数据目录", section, if (writable) "可写" else "不可写", writable,
        "路径：" + (files?.absolutePath ?: "未知") + "\n" +
            if (writable) "配置、日志可正常落盘。" else "无法写入：配置保存失败、日志缺失都会由此引起。",
    )

    val cacheMb = runCatching {
        val dir = context.cacheDir
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024L / 1024L
    }.getOrDefault(0L)
    out += HealthItem(
        "缓存占用", section, cacheMb.toString() + "MB", cacheMb < 200,
        "模块缓存目录当前 " + cacheMb + "MB。\n" +
            if (cacheMb < 200) "在正常范围内（阈值 200MB，超过才提示）。"
            else "超过 200MB 阈值。这些都是临时文件，清掉不影响任何配置与功能：" +
                "点下方「去清理」打开系统应用信息页，选「存储」→「清除缓存」即可。",
        // 占用大只是提示，不是功能故障，也不代表任何功能没启用
        inactive = cacheMb >= 200,
        hint = cacheMb >= 200,
        actionLabel = if (cacheMb >= 200) "去清理" else null,
        action = if (cacheMb >= 200) {
            {
                runCatching {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "已打开系统应用信息页：选「存储」→「清除缓存」"
                }.getOrElse { "打开失败：" + (it.message ?: it.javaClass.simpleName) }
            }
        } else null,
    )
    return out
}

/**
 * 冲突检测分两层：
 * 1. 「Xposed 框架」——装的是哪个框架管理器，只是环境信息，不算冲突；
 * 2. 「其它模块」——会 Hook 微信的模块，才可能和知友抢同一批方法。
 * 框架管理器不是模块：混在一起会把 LSPosed 本身标成「冲突模块」。
 */
private fun conflictSection(context: Context): List<HealthItem> {
    val out = mutableListOf<HealthItem>()

    val frameworks = ModuleDiagnostics.installedFrameworks(context)
    out += HealthItem(
        "Xposed 框架", "模块冲突",
        if (frameworks.isEmpty()) "未识别"
        else frameworks.joinToString("、") { it.label + " v" + it.version },
        true,
        "当前设备上装过的 Xposed 框架管理器。这只说明模块跑在哪个框架上，不是冲突；" +
            "如果同时显示多个框架（如 LSPosed 和 EdXposed 都在），才建议只保留一个。",
        inactive = frameworks.isEmpty(),
    )

    val others = ModuleDiagnostics.installedModules(context)
    if (others.isEmpty()) {
        out += HealthItem(
            "其它微信模块", "模块冲突", "未发现", true,
            "已扫描微信 Hook / 增强类模块的常见包名（微X、WAuxv、WeChatX、浮云、WCXLC），均未安装。",
        )
    } else {
        others.forEach { m ->
            out += HealthItem(
                "其它模块·" + m.label, "模块冲突", "已安装 v" + m.version, false,
                "包名：" + m.pkg + "\n" +
                    "同时 Hook 微信的模块会覆盖同一方法、争抢同一 View，表现为功能时好时坏或直接失效。" +
                    "如与 ${BuildConfig.BRAND} 功能重叠，建议在 LSPosed 里只给一个模块勾选微信作用域。",
                inactive = true,
                hint = true,
            )
        }
    }
    return out
}

private fun hiddenContactsSection(): List<HealthItem> {
    val section = "隐藏联系人"
    val out = mutableListOf<HealthItem>()
    val master = runCatching { HideContacts.isEnabled }.getOrDefault(false)
    val turnOn: () -> String = {
        runCatching { HideContacts.enable() }
        "隐藏联系人已启用；若名单为空请到「联系人与群组 → 隐藏联系人 → 配置隐藏列表」添加"
    }
    out += HealthItem(
        "隐藏联系人开关", section, if (master) "开启" else "关闭", master,
        "隐藏联系人在「联系人与群组」分类下。关闭后所有隐藏与拦截都不生效，名单保留。",
        actionLabel = if (master) null else "启用",
        action = if (master) null else turnOn,
        inactive = !master,
    )
    val count = runCatching { HideContacts.hiddenContacts.size }.getOrDefault(0)
    out += HealthItem(
        "名单人数", section, "$count", true,
        "当前名单共 $count 人。为 0 时不会有任何隐藏效果，请到「配置隐藏列表」添加。",
    )
    val temp = runCatching { HideContacts.isTemporarilyShown }.getOrDefault(false)
    out += HealthItem(
        "临时显示", section, if (temp) "生效中" else "未激活", !temp,
        "临时显示期间，名单内所有人对你可见（#show 或三击标题触发、#hide 恢复）。",
        inactive = temp,
    )
    val blockChat = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_BLOCK_CHAT) }.getOrDefault(false)
    out += HealthItem(
        "禁止进入聊天", section, if (blockChat) "开启" else "关闭", blockChat,
        "开启后点开名单内联系人的聊天页会立即退出；临时显示期间放行。",
        inactive = !blockChat,
    )
    val blockProfile = runCatching { HideContacts.surfaceOn(HideContacts.SURFACE_BLOCK_PROFILE) }.getOrDefault(false)
    out += HealthItem(
        "禁止查看资料", section, if (blockProfile) "开启" else "关闭", blockProfile,
        "开启后点开名单内联系人的资料页会立即退出；临时显示期间放行。",
        inactive = !blockProfile,
    )
    return out
}

// ---------------------------------------------------------------------------
//  修复步骤（全量）
// ---------------------------------------------------------------------------

/**
 * 一键修复（模块自检）：修复前体检 → 对症修复 → 复检 → 前后对比。
 *
 * 与脚本页保持一致：不输出一份「永远全绿」的修复记录，而是给出真实结论——
 * 修复前几项异常、修复后几项异常、哪些已解决、哪些仍然需要处理。
 */
private fun moduleRepairSteps(context: Context): List<HealthStep> {
    val before = mutableListOf<HealthItem>()
    val afterFix = mutableListOf<HealthItem>()

    val checklist = listOf(
    // ① 真正的「重建引导链路」：对解析不到锚点的功能重新跑一遍 DexKit，
    //    把结果写回缓存，并重装 hook（delete+enable，因为旧 descriptor 已经挂在方法上了）。
    //    之前这里只做 disable/enable，重启不会让 DexKit 重新解析，对锚点漂移完全无效。
    HealthStep("重建 DexKit 锚点") {
        val candidates = FeaturesProvider.ALL_HOOK_ITEMS
            .filterIsInstance<IResolveDex>()
            .filter { item ->
                val base = item as? BaseFeature
                // 微信已删除实现的（unavailableReason 稳定判定）不必重扫，扫也扫不到
                if (base?.unavailableReason != null) return@filter false
                val superseded = base?.supersededAnchors.orEmpty()
                item.dexDelegates.any { it.isPlaceholder && it.key !in superseded }
            }

        if (candidates.isEmpty()) {
            listOf(
                HealthItem(
                    "重建 DexKit 锚点", "引导链路",
                    "无需重建", true,
                    "所有已注册锚点都能解析到目标，不需要重新扫描。",
                )
            )
        } else {
            val rebuilt = mutableListOf<String>()
            val stillMissing = mutableListOf<String>()
            val failed = mutableListOf<String>()

            candidates.forEach { item ->
                val base = item as? BaseFeature
                val name = base?.displayName ?: item.javaClass.simpleName
                val superseded = base?.supersededAnchors.orEmpty()

                val scanned = runCatching {
                    item.resolveAllDex(DexKit)
                    DexCacheManager.saveItemCache(item)
                }
                if (scanned.isFailure) {
                    failed += name
                    return@forEach
                }

                val remaining = item.dexDelegates.count { it.isPlaceholder && it.key !in superseded }
                if (remaining > 0) {
                    stillMissing += "$name($remaining)"
                    return@forEach
                }

                rebuilt += name
                // 功能已经装好 hook 时，descriptor 是旧的：必须卸载再装一次才生效。
                if (base != null && base.isActive) {
                    runCatching { base.disable(); base.enable() }
                        .onFailure { WeLogger.w("ModuleRepair", "重新挂载 $name 失败", it) }
                }
            }

            val sum = buildString {
                append("重新解析 ${candidates.size} 个功能：")
                append("修复 ").append(rebuilt.size).append("（").append(rebuilt.joinToString("、")).append("）")
                if (stillMissing.isNotEmpty()) {
                    append("；仍缺锚点 ").append(stillMissing.size).append("（")
                        .append(stillMissing.joinToString("、")).append("）")
                }
                if (failed.isNotEmpty()) {
                    append("；扫描异常 ").append(failed.size).append("（")
                        .append(failed.joinToString("、")).append("）")
                }
                if (stillMissing.isNotEmpty() || failed.isNotEmpty()) {
                    append("。仍缺锚点的功能说明目标方法在当前微信版本里确实不存在，需要重新适配匹配条件。")
                }
            }
            listOf(
                HealthItem(
                    "重建 DexKit 锚点", "引导链路",
                    "修复 ${rebuilt.size}/${candidates.size}",
                    stillMissing.isEmpty() && failed.isEmpty(),
                    sum,
                )
            )
        }
    },
    HealthStep("重启异常功能") {
        val broken = FeaturesProvider.ALL_HOOK_ITEMS.filter { feature ->
            (feature as? SwitchFeature)?.isEnabled == true &&
                (runCatching { feature.runtimeIssue() }.getOrNull() != null || !feature.isActive)
        }
        broken.forEach { feature ->
            runCatching {
                feature.disable()
                feature.enable()
            }
        }
        listOf(
            HealthItem(
                "重启异常功能", "重启异常功能",
                if (broken.isEmpty()) "无需重启" else "${broken.size} 个", true,
                if (broken.isEmpty()) "没有「开关开着但没生效」的功能。"
                else "已重启：${broken.joinToString("、") { it.name }}。若仍无效，请把日志里对应功能的 ERROR 发给作者。",
            )
        )
    },
    HealthStep("隐藏联系人刷新") {
        val ok = runCatching { WeConversationApi.reloadConversations() }.isSuccess
        listOf(
            HealthItem(
                "隐藏联系人", "刷新会话列表",
                if (ok) "成功" else "失败", ok,
                "按当前名单重新过滤会话列表（名单本身不改动）。",
            )
        )
    },
)

    return listOf(
        HealthStep("修复前体检") {
            before.clear()
            before += moduleHealthSteps(context)
                .flatMap { step -> runCatching { step.run() }.getOrDefault(emptyList()) }
            val bad = before.count { !it.ok && !it.inactive }
            listOf(
                HealthItem(
                    label = "修复前异常",
                    section = "修复前体检",
                    value = "$bad 项",
                    ok = true,
                    detail = "共 ${before.size} 项检测，异常 $bad 项",
                )
            )
        },
    ) + checklist + listOf(
        HealthStep("修复后复检") {
            afterFix.clear()
            afterFix += moduleHealthSteps(context)
                .flatMap { step -> runCatching { step.run() }.getOrDefault(emptyList()) }
            val bad = afterFix.count { !it.ok && !it.inactive }
            listOf(
                HealthItem(
                    label = "修复后异常",
                    section = "修复后复检",
                    value = "$bad 项",
                    ok = bad == 0,
                    detail = "共 ${afterFix.size} 项检测，异常 $bad 项",
                )
            )
        },
        HealthStep("前后对比") {
            val beforeBad = before.filter { !it.ok && !it.inactive }.map { it.label }.toSet()
            val afterBad = afterFix.filter { !it.ok && !it.inactive }.map { it.label }.toSet()
            val fixed = beforeBad - afterBad
            val remain = afterBad
            listOf(
                HealthItem(
                    label = "修复结果",
                    section = "前后对比",
                    value = if (remain.isEmpty()) "全部通过" else "仍有 ${remain.size} 项异常",
                    ok = remain.isEmpty(),
                    detail = buildString {
                        append("修复前异常 ").append(beforeBad.size).append(" 项，修复后 ")
                            .append(remain.size).append(" 项")
                        if (fixed.isNotEmpty()) append("\n已解决：").append(fixed.joinToString("、"))
                        if (remain.isNotEmpty()) append("\n仍存在：").append(remain.joinToString("、"))
                    },
                )
            )
        },
    )
}

// ---------------------------------------------------------------------------
//  自检页 v2 组件：环形总览 / 操作条 / 彩色段网格 / 可折叠明细
// ---------------------------------------------------------------------------

/** 一个检测段的汇总（对应「检测范围」网格里的一格）。 */
private data class HealthSegment(
    val name: String,
    val ok: Int,
    val fail: Int,
    val off: Int,
) {
    val total: Int get() = ok + fail + off
    /** 0 = 全通过，1 = 有异常，2 = 全部无需处理 */
    val state: Int get() = when {
        fail > 0 -> 1
        total > 0 && ok == total -> 0
        else -> 2
    }
}

private fun buildSegments(items: List<HealthItem>): List<HealthSegment> =
    items.groupBy { it.section }.map { (name, list) ->
        HealthSegment(
            name = name,
            ok = list.count { it.ok },
            fail = list.count { !it.ok && !it.inactive },
            off = list.count { !it.ok && it.inactive },
        )
    }

private fun segmentColor(seg: HealthSegment): Color = when (seg.state) {
    1 -> WxPalette.Danger
    0 -> WxPalette.Ok
    else -> WxPalette.Warning
}

@Composable
private fun MiniPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** 环形进度 + 大百分比的模块总览卡。 */
@Composable
private fun HealthHeroCard(
    pass: Int,
    fail: Int,
    off: Int,
    total: Int,
    scanning: Boolean,
    lastScan: String,
    wechatVersion: String,
    moduleVersion: String,
) {
    // 口径：异常统计只看「通过 + 异常」，未启用/提示项不算故障也不占分母。
    val counted = pass + fail
    val ratio = if (counted > 0) pass.toFloat() / counted else 0f
    val animated by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "heroRatio",
    )
    val statusText = when {
        counted == 0 -> "尚未检测"
        fail > 0 -> "存在 " + fail + " 项异常"
        else -> "全部通过"
    }
    val statusColor = when {
        total == 0 -> WxPalette.textSecondary()
        fail > 0 -> WxPalette.Danger
        else -> WxPalette.Ok
    }
    SoftCard(contentPadding = 18, glow = counted > 0 && fail == 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Color(0x1A808080),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (animated > 0f) {
                        drawArc(
                            color = WxPalette.Accent,
                            startAngle = -90f,
                            sweepAngle = 360f * animated,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = (animated * 100).toInt().toString() + "%",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = WxPalette.textPrimary(),
                    )
                    Text(
                        text = pass.toString() + "/" + counted,
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("模块自检", fontSize = 12.sp, color = WxPalette.textSecondary())
                Text(statusText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = statusColor)
                Text(
                    text = if (scanning) "正在检测…" else "最后检测 " + lastScan,
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (fail > 0) MiniPill("异常 " + fail, WxPalette.Danger)
                    if (fail == 0 && counted > 0) MiniPill("无异常", WxPalette.Ok)
                }
                if (off > 0) {
                    Text(
                        text = "另有 " + off + " 项无需处理（不计入异常）",
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
            }
        }
        Text(
            text = "微信 " + wechatVersion + " · 模块 " + moduleVersion,
            fontSize = 11.sp,
            color = WxPalette.textSecondary(),
        )
    }
}

/** 一行三个操作：主按钮（重新检测）、次按钮（一键修复）、复制。 */
@Composable
private fun HealthActionPanel(onRescan: () -> Unit, onRepair: () -> Unit, onCopy: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(shape)
                .background(WxPalette.Accent)
                .clickable(onClick = onRescan),
            contentAlignment = Alignment.Center,
        ) {
            Text("重新检测", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(shape)
                .background(WxPalette.cardBackground())
                .border(1.dp, WxPalette.cardBorder(), shape)
                .clickable(onClick = onRepair),
            contentAlignment = Alignment.Center,
        ) {
            Text("一键修复", color = WxPalette.textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(shape)
                .background(WxPalette.cardBackground())
                .border(1.dp, WxPalette.cardBorder(), shape)
                .clickable(onClick = onCopy),
            contentAlignment = Alignment.Center,
        ) {
            Text("复制", fontSize = 11.sp, color = WxPalette.textSecondary())
        }
    }
}

/** 4 列彩色段网格：一眼看出哪个检测段是红的。 */
@Composable
private fun HealthSegmentGrid(segments: List<HealthSegment>, onOpen: (HealthSegment) -> Unit) {
    SoftCard(contentPadding = 14) {
        Text("检测范围", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = WxPalette.textSecondary())
        segments.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { seg ->
                    val color = segmentColor(seg)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpen(seg) }
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (seg.fail > 0) seg.fail.toString() else seg.ok.toString(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = color,
                            )
                        }
                        Text(
                            text = seg.name,
                            fontSize = 10.sp,
                            color = WxPalette.textSecondary(),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 可折叠明细分组：默认只露标题 + 数量，点开才铺明细。 */
@Composable
private fun HealthFoldSection(
    title: String,
    list: List<HealthItem>,
    defaultCollapsed: Boolean,
    accent: Color,
    onOpenAll: () -> Unit,
    onOpenItem: (HealthItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(!defaultCollapsed) }
    SoftCard(contentPadding = 0, glow = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
                modifier = Modifier.weight(1f),
            )
            Text(text = list.size.toString(), fontSize = 13.sp, color = WxPalette.textSecondary())
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = 12.sp,
                color = WxPalette.Accent,
            )
        }
        if (expanded) {
            if (!defaultCollapsed && title == "异常项") {
                Text(
                    text = "标记说明：绿标「可修复」= 点开详情能直接执行修复动作；" +
                        "橙标「需适配」= 多为微信版本变化导致，端上无法自动修复，" +
                        "点开详情复制内容发给开发者即可。",
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                SoftDivider(start = 16.dp, end = 0.dp, verticalPadding = 0.dp)
            }
            if (list.isEmpty()) {
                SoftDivider(start = 16.dp, end = 0.dp, verticalPadding = 0.dp)
                Text(
                    text = "（无）",
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                val shown = if (list.size > 12) list.take(12) else list
                shown.forEach { item ->
                    SoftDivider(start = 16.dp, end = 0.dp, verticalPadding = 0.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenItem(item) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (item.ok) "✓" else if (item.inactive) "○" else "✕",
                            fontSize = 12.sp,
                            color = when {
                                item.ok -> WxPalette.Ok
                                item.inactive -> WxPalette.Warning
                                else -> WxPalette.Danger
                            },
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.label, fontSize = 13.sp, color = WxPalette.textPrimary())
                                if (!item.ok && !item.inactive) {
                                    Spacer(Modifier.width(6.dp))
                                    val fixable = item.action != null
                                    val tagColor = if (fixable) WxPalette.Ok else WxPalette.Warning
                                    Text(
                                        text = if (fixable) "可修复" else "需适配",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tagColor,
                                        modifier = Modifier
                                            .background(tagColor.copy(alpha = 0.14f), RoundedCornerShape(50))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = item.value,
                                fontSize = 11.sp,
                                color = WxPalette.textSecondary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (list.size > 12) {
                    SoftDivider(start = 16.dp, end = 0.dp, verticalPadding = 0.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAll)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("查看全部 " + list.size + " 项", fontSize = 12.sp, color = WxPalette.Accent)
                    }
                }
            }
        }
    }
}

/** 检测段清单浮层：与页面同风格的底部卡片，点条目看详情，支持一键复制全部。 */
@Composable
private fun HealthListOverlay(
    title: String,
    items: List<HealthItem>,
    onDismiss: () -> Unit,
    onOpenItem: (HealthItem) -> Unit,
    onCopyAll: () -> Unit,
) {
    val sheetInteraction = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxSize()) {
        Scrim(onDismiss = onDismiss, dense = true)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(WxPalette.cardBackground())
                .clickable(interactionSource = sheetInteraction, indication = null) {}
                .padding(top = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = WxPalette.textPrimary(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = items.size.toString() + " 项",
                    fontSize = 12.sp,
                    color = WxPalette.textSecondary(),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onCopyAll)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("复制全部", fontSize = 13.sp, color = WxPalette.Accent)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("关闭", fontSize = 13.sp, color = WxPalette.Accent)
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items.size, key = { items[it].label + it }) { i ->
                    val item = items[i]
                    val rowShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(WxPalette.cardBackground())
                            .border(1.dp, WxPalette.cardBorder(), rowShape)
                            .clickable { onOpenItem(item) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (item.ok) "✓" else if (item.inactive) "○" else "✕",
                            fontSize = 12.sp,
                            color = when {
                                item.ok -> WxPalette.Ok
                                item.inactive -> WxPalette.Warning
                                else -> WxPalette.Danger
                            },
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.label,
                                    fontSize = 13.sp,
                                    color = WxPalette.textPrimary(),
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (!item.ok && !item.inactive) {
                                    Spacer(Modifier.width(6.dp))
                                    val fixable = item.action != null
                                    val tagColor = if (fixable) WxPalette.Ok else WxPalette.Warning
                                    Text(
                                        text = if (fixable) "可修复" else "需适配",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = tagColor,
                                        modifier = Modifier
                                            .background(tagColor.copy(alpha = 0.14f), RoundedCornerShape(50))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = item.value,
                                fontSize = 11.sp,
                                color = WxPalette.textSecondary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "\u203A",
                            fontSize = 18.sp,
                            color = WxPalette.textTertiary(),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
