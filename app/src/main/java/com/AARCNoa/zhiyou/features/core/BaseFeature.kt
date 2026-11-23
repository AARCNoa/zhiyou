@file:Suppress("NOTHING_TO_INLINE")

package com.AARCNoa.zhiyou.features.core

import androidx.compose.runtime.Composable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.dexkit.dsl.BaseDexDelegate
import com.AARCNoa.zhiyou.dexkit.dsl.DexConstructorDelegate
import com.AARCNoa.zhiyou.dexkit.dsl.DexMethodDelegate
import com.AARCNoa.zhiyou.utils.HookAction
import com.AARCNoa.zhiyou.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable
import kotlin.reflect.KClass

abstract class BaseFeature {

    var name: String = ""
    var categories: List<String> = emptyList()

    /**
     * 本功能是否需要在非主进程（push / appbrand）启动。
     *
     * [SwitchFeature] 用 [SwitchFeature.targetProcessMask] 静态声明，白名单校验能直接读到；
     * 而 [ApiFeature] 子类是在 [startup] 里自己判断进程的，静态看不出来——历史上
     * `HideContactsNotifications` 就因此漏登记白名单，在 push 里「启动成功但一条 hook 都没挂」。
     * 这类功能必须把这里置 true，白名单校验才知道要检查它。
     */
    open val runsInNonMainProcess: Boolean
        get() = false

    val displayName: String
        get() = "${categories.joinToString(",")}/$name"

    var description: String = ""

    /**
     * 当前微信版本是否支持本功能；返回非空字符串表示不支持（开关会拒绝开启并提示该文案）。
     *
     * 用于「微信把某个模块整体删掉」这类情况（例如 8.0.77 起多人通话 MultiTalk 被移除），
     * 避免用户开了开关却毫无反应、误以为模块坏了。
     */
    open val unavailableReason: String?
        get() = null

    /**
     * 「解析不到、但功能本身有别的实现路径」的锚点说明：key → 为什么可以忽略。
     *
     * 微信改版经常只是换了一条路径（例如群通话从 `MultiTalkMainUI` 搬到 VoIPMP v2 / Flutter），
     * 旧锚点留在代码里给老版本用，在新版本上必然解析失败。这类锚点如果也算「需适配」，
     * 自检页会长期挂着十来个红叉，真正要修的问题反而被淹没。
     *
     * 登记在这里的锚点在自检里显示「已由新路径替代」，不计入异常。
     * 只有确认「新路径确实覆盖了同样的能力」才登记，不要用它掩盖真实缺失。
     */
    open val supersededAnchors: Map<String, String>
        get() = emptyMap()

    /**
     * 当前微信版本是否 ≥ major.minor.patch。
     *
     * 「微信从某版起整体移除了本功能依赖的实现」这类判断必须按版本号来：这类功能在 UI 上
     * 直接把条目隐藏（不显示「失效/需适配」），因此不能依赖 DexKit 委托是否解析完成——
     * 未解析时 isPlaceholder 同样为 true，会让功能列表项在解析结束前闪一下消失。
     */
    protected fun hostVersionAtLeast(major: Int, minor: Int, patch: Int): Boolean {
        val nums = runCatching {
            com.AARCNoa.zhiyou.utils.HostInfo.versionName
                .split('.')
                .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        }.getOrDefault(emptyList())
        if (nums.isEmpty()) return false
        val actual = intArrayOf(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
        val expected = intArrayOf(major, minor, patch)
        for (i in 0..2) {
            if (actual[i] != expected[i]) return actual[i] > expected[i]
        }
        return true
    }

    /**
     * 运行时回执：功能「开着但当前状态不健康」时返回具体原因；null = 没有已知问题。
     *
     * 自检页会把「开关已开 && runtimeIssue() != null」直接列为「开着但没生效」，并可一键重启。
     * 只有功能自己确认「该生效却没生效」时才返回（例如注入型功能找不到注入容器、
     * 采集不到内容）；没进入过相关界面、无法判断时请返回 null，避免误报。
     */
    open fun runtimeIssue(): String? = null

    open fun startup() {
        error("You shouldn't inherit BaseFeature")
    }

    /** Whether this feature's hooks are currently installed (runtime truth). */
    var isActive: Boolean = false
        private set

    fun enable() {
        if (isActive) return

        runCatching {
            isActive = true
            onEnable()
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to enable feature $displayName", e)
            // ensure transaction is fully discarded
            unhookAll()
            isActive = false
        }
    }

    fun disable() {
        if (!isActive) return

        runCatching {
            isActive = false
            unhookAll()
            onDisable()
        }.onFailure { e ->
            // unhookAll() 已经执行，运行时 hook 确实摘掉了；这里不能把 isActive 写回 true，
            // 否则自检页会显示「已启用」而实际一个 hook 都没挂。onDisable 自身的异常只记录。
            WeLogger.e(TAG, "onDisable failed for $displayName（unhook 已完成）", e)
        }
    }

    open fun onEnable() {}

    open fun onDisable() {}

    @Composable
    open fun Ui() {
    }

    private val _dexDelegates = mutableListOf<BaseDexDelegate>()

    /** 只读视图：调用方拿不到内部注册表，强转也无法改动。 */
    val dexDelegates: List<BaseDexDelegate> by lazy {
        java.util.Collections.unmodifiableList(_dexDelegates)
    }
    internal fun registerDexDelegate(d: BaseDexDelegate) {
        _dexDelegates += d
    }

    internal fun resolveInlineDex(dexKit: DexKitBridge) {
        // 每个委托独立容错：某个锚点命中多个候选、或解析时抛异常，只让这一个委托降级，
        // 绝不能把整个 API 拖下水。典型事故：8.0.80 的 img feature service 有一个方法
        // 命中 2 个候选，IllegalStateException 直接把 WeMessageApi 的解析整体打断，
        // 连带语音/图片/复读等所有发送能力一起不可用。
        dexDelegates.forEach { delegate ->
            runCatching { delegate.findInline(dexKit) }
                .onFailure { WeLogger.e(TAG, "dex delegate resolve failed (${delegate.key})", it) }
        }
    }

    private val unhooks = mutableListOf<XC_MethodHook.Unhook>()
    internal fun registerUnhook(u: XC_MethodHook.Unhook) {
        unhooks += u
    }

    internal fun unhookAll() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
    }

    // --- hookBefore ---

    internal fun Executable.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(priority) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        ))

    @JvmName("hookBefore2")
    internal fun BaseReflectedMethod.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = self.hookBefore(priority, action)

    @JvmName("hookBefore3")
    internal fun ReflectedConstructor<*>.hookBefore(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookBefore(priority, action)

    internal fun Class<*>.hookBeforeOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookBefore(50, action)

    internal fun Class<*>.hookAfterOnCreate(
        action: HookAction
    ) = this.reflekt().firstMethod { name = "onCreate" }.hookAfter(50, action)

    internal fun KClass<*>.hookBeforeOnCreate(action: HookAction) =
        this.java.hookBeforeOnCreate(action)

    internal fun KClass<*>.hookAfterOnCreate(action: HookAction) =
        this.java.hookAfterOnCreate(action)

    // --- end hookBefore ---

    // --- hookAfter ---

    internal fun Executable.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = registerUnhook(
        XposedBridge.hookMethod(
            this,
            object :
                XC_MethodHook(priority) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    executeHookAction(param, action)
                }
            }
        ))

    @JvmName("hookAfter2")
    internal fun BaseReflectedMethod.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = self.hookAfter(priority, action)

    @JvmName("hookAfter3")
    internal fun ReflectedConstructor<*>.hookAfter(
        priority: Int = 50,
        action: HookAction
    ) = this.self.hookAfter(priority, action)

    // --- end hookAfter ---

    // --- dex delegate ---

    /**
     * 未解析的委托一律不挂载。
     *
     * 占位符指向 `LauncherUI.getInstance()` 这类高频零参方法，挂上去既不生效、又可能按 ms
     * 级频率抛参数越界刷屏。统一在这里拦住，调用方不必每个都写 `isPlaceholder` 判断。
     */
    private inline fun hookIfResolved(
        isPlaceholder: Boolean,
        key: String,
        what: String,
        hook: () -> Unit,
    ) {
        if (isPlaceholder) {
            WeLogger.d("HookSkip", "跳过未解析$what: $key")
            return
        }
        hook()
    }

    internal fun DexMethodDelegate.hookBefore(priority: Int = 50, action: HookAction) =
        hookIfResolved(isPlaceholder, key, "方法 hookBefore") { method.hookBefore(priority, action) }

    internal fun DexMethodDelegate.hookAfter(priority: Int = 50, action: HookAction) =
        hookIfResolved(isPlaceholder, key, "方法 hookAfter") { method.hookAfter(priority, action) }

    internal fun DexConstructorDelegate.hookBefore(priority: Int = 50, action: HookAction) =
        hookIfResolved(isPlaceholder, key, "构造器 hookBefore") { constructor.hookBefore(priority, action) }

    internal fun DexConstructorDelegate.hookAfter(priority: Int = 50, action: HookAction) =
        hookIfResolved(isPlaceholder, key, "构造器 hookAfter") { constructor.hookAfter(priority, action) }

    // --- end dex delegate ---

    internal fun executeHookAction(param: XC_MethodHook.MethodHookParam, action: HookAction) {
        runCatching {
            action(param)
        }.onFailure { e -> WeLogger.e("executeHookAction", "failed to execute hook of $name", e) }
    }

    companion object {
        private const val TAG = "BaseFeature"
    }
}
