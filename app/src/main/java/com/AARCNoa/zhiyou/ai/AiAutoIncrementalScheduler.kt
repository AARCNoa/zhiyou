package com.AARCNoa.zhiyou.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.AARCNoa.zhiyou.features.ai.AiConfig
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 好友档案「自动增量整理」的触发层：每天固定时刻补一次新增记录。
 *
 * 与 [com.AARCNoa.zhiyou.ai.group.GroupDigestScheduler] 同构：闹钟 + 进程内定时器 +
 * 启动补跑，**真正的计时依据是落盘的 `autoIncrementalDay`**，不是这里的定时器。
 * 微信被杀 / force-stop 时闹钟投递不到，只会让「到点那一刻没跑」；下次 `onEnable`
 * 按 `isDueToday` 补一次，当天只补一次。
 *
 * 串行化靠 [AiRunState.hasLongTask]：同时开了多个好友时，第一个跑起来后其余的重试
 * 10 分钟后再看，不会并发抢同一个服务商配额。
 */
object AiAutoIncrementalScheduler {

    private const val TAG = "AiAutoIncremental"
    private const val ACTION = "com.AARCNoa.zhiyou.AI_AUTO_INCREMENTAL_ALARM"
    private const val EXTRA_WX_ID = "wxid"

    /** 到点后的启动延迟：给设置页保存、索引落盘留一点余量。 */
    private const val DUE_TRIGGER_DELAY_MS = 2000L

    /** 档案忙 / 其他好友在整理时的重试间隔。 */
    private const val BUSY_RETRY_MS = 10 * 60 * 1000L

    /** 补跑时逐个错开，避免多位好友同一秒进队列。 */
    private const val CATCH_UP_STAGGER_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timers = ConcurrentHashMap<String, Job>()

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun onEnable() {
        registerReceiver()
        scope.launch { catchUp() }
    }

    fun onDisable() {
        unregisterReceiver()
        timers.values.forEach { it.cancel() }
        timers.clear()
        loadEnabled().forEach { cancelAlarm(it.wxId) }
    }

    // ── 排程 ────────────────────────────────────────────────────────────────

    /** 读索引里开着自动整理的好友。索引是唯一来源：不解析完整档案。 */
    private fun loadEnabled(): List<AiProfileSummary> =
        AiProfileStore.loadIndex().profiles.filter { it.autoIncremental }

    /**
     * 启动补跑：今天到点但还没跑过的排到最近，其余按各自时间点排。
     *
     * 只读一次索引 —— 好友多时逐个 `schedule(wxId)` 会重复读同一份文件几十次。
     */
    private suspend fun catchUp() {
        val now = System.currentTimeMillis()
        val enabled = loadEnabled()
        if (enabled.isEmpty()) return
        val due = enabled.filter { isDueToday(it, now) }
        if (due.isNotEmpty()) WeLogger.i(TAG, "启动补跑 ${due.size} 位好友的自动增量整理")
        enabled.forEachIndexed { index, profile ->
            val extra = if (isDueToday(profile, now)) DUE_TRIGGER_DELAY_MS + index * CATCH_UP_STAGGER_MS else 0L
            scheduleOne(profile, extra)
        }
    }

    /** 按最新配置重排一位好友：改开关、改时间之后都要调一次。 */
    fun schedule(wxId: String) {
        val profile = AiProfileStore.loadIndex().profiles.firstOrNull { it.wxId == wxId }
        if (profile == null || !profile.autoIncremental) {
            cancel(wxId)
            return
        }
        scheduleOne(profile, 0L)
    }

    fun cancel(wxId: String) {
        timers.remove(wxId)?.cancel()
        cancelAlarm(wxId)
    }

    private fun scheduleOne(profile: AiProfileSummary, extraDelayMs: Long) {
        cancel(profile.wxId)
        val now = System.currentTimeMillis()
        val at = if (isDueToday(profile, now)) {
            now + DUE_TRIGGER_DELAY_MS + extraDelayMs
        } else {
            // 今天该时间点还没到就是今天，已过就是明天；已经跑过的时间点由 isDueToday 挡住。
            nextRunAt(profile.autoIncrementalMinute, now)
        }
        setAlarm(profile.wxId, at)
        scheduleTimer(profile.wxId, at)
    }

    /**
     * 给界面看的「下次执行」：`今天 12:20 · 4 分钟后` / `明天 09:00` / `11-27 09:00`。
     *
     * 规则与 [scheduleOne] 共用同一套判断，避免界面显示一个时间、实际在另一个时间跑。
     */
    fun describeNext(profile: AiProfile, nowMs: Long = System.currentTimeMillis()): String {
        val at = if (
            isDueToday(
                profile.autoIncrementalMinute,
                profile.autoIncrementalDay,
                profile.autoIncrementalAt,
                nowMs,
            )
        ) {
            nowMs + DUE_TRIGGER_DELAY_MS
        } else {
            nextRunAt(profile.autoIncrementalMinute, nowMs)
        }
        val day = AiDates.dayKey(at)
        val clock = AiDates.minuteText(profile.autoIncrementalMinute)
        return when (day) {
            // 今天补一句相对时间：改完就能看出「还剩几分钟」，不用自己做减法。
            AiDates.dayKey(nowMs) -> "今天 $clock · " + leftText(at - nowMs)
            AiDates.dayKey(nowMs + DAY_MS) -> "明天 $clock"
            else -> day.substring(5) + " $clock"
        }
    }

    /** `4 分钟后` / `1 小时 20 分钟后` / `马上`。 */
    private fun leftText(leftMs: Long): String {
        val minutes = (leftMs / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "马上"
            minutes < 60 -> "$minutes 分钟后"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分钟后"
        }
    }

    private fun scheduleNextDay(profile: AiProfileSummary) {
        cancel(profile.wxId)
        val at = nextRunAt(profile.autoIncrementalMinute, System.currentTimeMillis() + DAY_MS)
        setAlarm(profile.wxId, at)
        scheduleTimer(profile.wxId, at)
    }

    private fun retryLater(wxId: String) {
        cancel(wxId)
        val at = System.currentTimeMillis() + BUSY_RETRY_MS
        setAlarm(wxId, at)
        scheduleTimer(wxId, at)
    }

    // ── 触发 ────────────────────────────────────────────────────────────────

    /**
     * 闹钟与定时器都会进来，可能同时触发两次。
     *
     * 去重分两层：`autoIncrementalDay`（当天跑过就不跑）挡跨次触发，
     * `AiProfileInitializer` 的单飞锁挡同一秒的并发启动。
     */
    private fun handleTrigger(wxId: String) {
        scope.launch {
            val profile = AiProfileStore.loadIndex().profiles
                .firstOrNull { it.wxId == wxId } ?: return@launch
            if (!profile.autoIncremental) return@launch

            val now = System.currentTimeMillis()
            if (!isDueToday(profile, now)) {
                // 今天已经跑过（或还没到点）：按正常时间点排下一次。
                scheduleOne(profile, 0L)
                return@launch
            }
            if (!AiConfig.isConfigured) {
                WeLogger.w(TAG, "跳过 ${mask(wxId)}：AI 未配置")
                // 没配 AI 时重试没有意义，当天不再打扰，第二天再说。
                markDayDone(wxId, now)
                scheduleNextDay(profile)
                return@launch
            }
            if (AiRunState.hasLongTask()) {
                WeLogger.i(TAG, "延迟 ${mask(wxId)}：有其他档案任务在跑")
                retryLater(wxId)
                return@launch
            }
            if (AiProfileInitializer.isRunning(wxId)) {
                retryLater(wxId)
                return@launch
            }

            val started = AiProfileInitializer.startIncremental(wxId, automatic = true)
            if (!started) {
                // 档案正忙（初始化 / 审查）：稍后再试，不写当天日期。
                WeLogger.i(TAG, "延迟 ${mask(wxId)}：档案正忙")
                retryLater(wxId)
                return@launch
            }
            WeLogger.i(TAG, "开始自动增量整理 ${mask(wxId)}")
            // 整理成功与否都排到明天：失败不自动重试，避免反复消耗 token。
            scheduleNextDay(profile)
        }
    }

    private fun markDayDone(wxId: String, nowMs: Long) {
        runCatching {
            AiProfileStore.patchProfile(wxId) {
                it.copy(
                    autoIncrementalDay = AiDates.dayKey(nowMs),
                    autoIncrementalAt = nowMs,
                )
            }
        }
    }

    // ── 时间计算 ────────────────────────────────────────────────────────────

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private fun isDueToday(profile: AiProfileSummary, nowMs: Long): Boolean =
        isDueToday(profile.autoIncrementalMinute, profile.autoIncrementalDay, profile.autoIncrementalAt, nowMs)

    /**
     * 今天该时间点已过，且**这个时间点**今天还没跑过。
     *
     * - 从没跑过（`autoIncrementalDay` 为空 = 刚开启）：当天不补跑，从下一个时间点开始。
     *   否则默认 09:00 一开开关就被判成「今天错过」，立刻消耗一次整理。
     * - 今天跑过，但跑的时刻早于当前设定时间点（12:16 把时间改到 12:20）：说明 12:20
     *   这个点还没跑，今天允许再跑一次。
     * - 今天跑过且跑的时刻不早于当前设定时间点：这个点已经跑过，等明天。
     */
    private fun isDueToday(minuteOfDay: Int, dayDone: String, lastAt: Long, nowMs: Long): Boolean {
        if (dayDone.isBlank()) return false
        val at = atMinute(nowMs, minuteOfDay)
        if (dayDone == AiDates.dayKey(nowMs) && lastAt >= at) return false
        return nowMs >= at
    }

    /** 从 [nowMs] 起算的下一个时间点：今天还没到就是今天，否则明天。 */
    private fun nextRunAt(minuteOfDay: Int, nowMs: Long): Long {
        val calendar = atMinute(nowMs, minuteOfDay)
        return if (calendar > nowMs) calendar else atMinute(nowMs + DAY_MS, minuteOfDay)
    }

    private fun atMinute(dayMs: Long, minuteOfDay: Int): Long {
        val minute = minuteOfDay.coerceIn(0, 23 * 60 + 59)
        return Calendar.getInstance().apply {
            timeInMillis = dayMs
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** 日志不留完整 wxid。 */
    private fun mask(wxId: String): String =
        if (wxId.length <= 8) wxId else wxId.take(4) + "…" + wxId.takeLast(2)

    // ── 定时器 ──────────────────────────────────────────────────────────────

    private fun scheduleTimer(wxId: String, atMs: Long) {
        val delayMs = atMs - System.currentTimeMillis()
        if (delayMs <= 0L) return
        timers[wxId] = scope.launch {
            try {
                delay(delayMs)
                handleTrigger(wxId)
            } catch (_: CancellationException) {
                // 关闭开关 / 改了时间 / 删除档案
            } catch (e: Throwable) {
                WeLogger.e(TAG, "进程内定时器失败：$wxId", e)
            }
        }
    }

    // ── 闹钟 ────────────────────────────────────────────────────────────────

    private fun setAlarm(wxId: String, atMs: Long) {
        val manager = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val pending = alarmIntent(wxId)
        val canExact = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
        }.getOrDefault(false)
        runCatching {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        }.onFailure {
            WeLogger.w(TAG, "精确闹钟失败，降级：${it.javaClass.simpleName}")
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending) }
        }
    }

    private fun cancelAlarm(wxId: String) {
        val manager = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        runCatching { manager.cancel(alarmIntent(wxId)) }
    }

    private fun alarmIntent(wxId: String): PendingIntent {
        val intent = Intent(ACTION)
            .setPackage(HostInfo.application.packageName)
            .putExtra(EXTRA_WX_ID, wxId)
        return PendingIntent.getBroadcast(
            HostInfo.application,
            wxId.hashCode() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ── 接收器 ──────────────────────────────────────────────────────────────

    private fun registerReceiver() {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val wxId = intent?.getStringExtra(EXTRA_WX_ID) ?: return
                handleTrigger(wxId)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                HostInfo.application,
                created,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = created
        }.onFailure {
            WeLogger.e(TAG, "注册闹钟接收器失败", it)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { HostInfo.application.unregisterReceiver(it) } }
        receiver = null
    }
}
