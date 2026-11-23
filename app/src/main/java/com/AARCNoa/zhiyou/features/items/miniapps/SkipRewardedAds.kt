package com.AARCNoa.zhiyou.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexConstructor
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature

import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.TargetProcesses
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.fs.createDirsSafe
import com.AARCNoa.zhiyou.BuildConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * 跳过小程序激励广告。
 *
 * 这类广告是用户主动点击的: 短暂加载后触发一次长震, 然后一个内嵌的精简版小程序/网页
 * (微信广告 SDK 里的 MB 奖励页) 从底部滑入覆盖原小程序, 看完倒计时后小程序才发奖励。
 * 8.0.76 群收集实际遇到的是「试玩 (playable)」奖励广告: 试玩包下载/注入/首帧由 MB
 * 运行时管理, WAService 侧的广告 SDK (WAAppAd.js / WAGameAd.js, 远程 commlib SDK 子包)
 * 能收到的唯一「广告已展示」信号是 onPlayableStatusChangeNew action=1
 * (PLAYABLE_FIRST_FRAME_READY)。
 *
 * 注入链路 (反编译确认, Java 侧稳定):
 *   loadLibFiles -> f.j() -> iCommLibReader.a()/H0() -> e3.h(script) -> V8 执行
 * 本实现:
 * 1. 拦截 H0() (FD 直读, 无法改文本), 强制 loadLibFiles 走字符串路径 e3.h;
 * 2. 在 e3.h 拿到即将执行的 SDK 文本, 打四处补丁:
 *    a) 事件出口(主): 包装发射器 `emit(e,t)`, 凡是 `close` 事件且载荷是对象,
 *       一律把 isEnded 置真。这是所有关闭分支(看完/跳过/胶囊/MB 关闭)的最终出口,
 *       不依赖任何混淆函数名或 show 分支;
 *    b) MB 展示成功后(`a.mbAdHasShown=!0`, 全文件唯一)补 `a.isRewarded=!0`,
 *       让 SDK 自己的 reward 状态(`lastAdIsEnded`)与之一致;
 *    c) MB 回报的 MbRewardAd_OnClose 被 SDK 原样转发(`emitClose(e.data)`),
 *       把 payload 的 isEnded 也强制为 true;
 *    d) 试玩广告: 首帧就绪信号后 1 秒执行 SDK 自己的完整关闭链路
 *       (置 isRewarded -> 移除试玩视图 + 容器收起 + emit close({isEnded:true}) -> q.destroy());
 * 3. 完整链路日志: e3.h 注入点 + webapi_getadvert 请求 + bridge 事件。
 *
 * 符号提取策略 (重要): SDK 是混淆 webpack bundle, 内部函数/变量名 (v$/JX/GY/mZ 等)
 * 在不同微信版本/基础库版本会变, 硬编码锚点会静默失效。因此补丁不引用任何写死的
 * 混淆名, 而是在拿到 SDK 文本后, 用「不混淆的字符串字面量 + 结构正则」运行时提取:
 *   - "PLAYABLE_FIRST_FRAME_READY" -> 首帧事件变量名;
 *   - 函数签名 function(e,t=!1) + 体内 isRewarded=!0/countDown -> 置奖励函数;
 *   - async function(e=0) + 体内 lastAdIsEnded -> 关闭链路函数;
 *   - VerifyAdRewardEligibility 请求上下文 -> 发请求助手 (marker 用, 可缺失);
 *   - .mb=new / .mbId -> MB 通道实例字段存在性。
 * 任一核心结构缺失时明确打 WeLogger.w 报告, 不注入半成品补丁。
 */
@Feature(
    name = "跳过激励广告",
    categories = ["小程序"],
    description = "小程序激励广告关闭时即视为看完并发放奖励（点关闭即可，无需等倒计时）"
)
object SkipRewardedAds : SwitchFeature(), IResolveDex {

    private const val TAG = "SkipRewardedAds"
    private const val SESSION_WINDOW_MS = 30_000L
    private const val SKIP_DELAY_MS = 200

    private val AD_EVENT_MARKERS = listOf("ad", "video", "reward", "close", "ended", "endpage")

    /** 广告 SDK 文件 (commlib SDK 子包), 命中即尝试打补丁。 */
    private val SDK_CANDIDATES = listOf(
        "WAAppAd.js",
        "WAGameAd.js",
        "WASplashAdFloatWindow.js",
        "WASplashadWorker.js",
        "app-ad.js",
        "app-ad",
        "WAAd.js",
        "WASdkAd.js",
    )

    /** 混淆标识符 (webpack 局部变量/函数名, 可能含 $)。 */
    private const val ID = "[A-Za-z_$][A-Za-z0-9_$]*"

    // 广告数据请求日志: 打印 webapi_getadvert 的请求内容 (主进程)。
    // 能看到奖励广告数据放行、发奖关键请求 VerifyAdRewardEligibility。
    private val ctorNetSceneJSOperateWxData by dexConstructor {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
            }
        }
    }

    // 真正的 SDK 注入点: loadLibFiles 读到的脚本文本在这里被编译/求值。
    // e3.h(f9, jsruntime.t, path, name, version, ctxId, script, i3, b3)
    private val methodInjectLibScript by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.utils")
        matcher {
            usingEqStrings("MicroMsg.JsValidationInjector", "hy: injecting file %s")
        }
    }

    // 远程 commlib reader 的 H0: 返回 AssetFileDescriptor 给 V8 直读, 文本补丁无效。
    // 置 null 后 loadLibFiles 回退到 iCommLibReader.a() -> e3.h 字符串路径。
    private val methodPkgReaderH0 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand.appcache")
        matcher {
            paramTypes("java.lang.String")
            returnType("android.content.res.AssetFileDescriptor")
            declaredClass {
                usingEqStrings("PkgReader[%d] [%s]")
            }
        }
    }

    // 本地 assets reader 同样处理 (老版本基础库)。
    private val methodAssetReaderH0 by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand.appcache")
        matcher {
            paramTypes("java.lang.String")
            returnType("android.content.res.AssetFileDescriptor")
            declaredClass {
                usingEqStrings("AssetReader[%d][%s]")
            }
        }
    }

    /** 已 dump 过的 SDK 文件名 (每个进程一次)。 */
    private val dumpedNames = ConcurrentHashMap.newKeySet<String>()

    /** 命中广告相关事件后, 接下来 30 秒内的所有 bridge 事件都会被打日志。 */
    private val adEventSessionUntil = AtomicLong(0)

    override val targetProcessMask
        get() = TargetProcesses.PROC_MAIN or TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        // 1) webapi_getadvert 请求日志 (主进程): 奖励请求放行 + 发奖校验。
        ctorNetSceneJSOperateWxData.hookBefore {
            val dataIndex = args.indexOfFirst { arg ->
                arg is String && runCatching {
                    JSONObject(arg).optString("api_name") == "webapi_getadvert"
                }.getOrDefault(false)
            }
            if (dataIndex < 0) return@hookBefore
            val data = runCatching {
                JSONObject(args[dataIndex] as String).optJSONObject("data")?.toString()
            }.getOrNull()
            WeLogger.d(TAG, "webapi_getadvert data=${data?.take(800)}")
        }

        // 2) 强制字符串注入路径: 广告 SDK 文件不允许 FD 直读。
        listOf(methodPkgReaderH0, methodAssetReaderH0)
            .filter { !it.isPlaceholder }
            .forEach { method ->
                method.hookBefore {
                    val path = args.getOrNull(0) as? String ?: return@hookBefore
                    if (isSdkCandidate(path)) {
                        WeLogger.d(TAG, "H0 blocked path=$path (force text inject)")
                        result = null
                    }
                }
            }

        // 3) 真正的补丁点: e3.h 里的 script 就是即将被编译执行的 SDK 文本。
        methodInjectLibScript.hookBefore {
            val path = args.getOrNull(2) as? String ?: return@hookBefore
            if (!isSdkCandidate(path)) return@hookBefore
            val script = args.getOrNull(6) as? String ?: return@hookBefore
            dumpSdkFile(path, script)
            val report = PatchReport()
            val patched = patchMbSdk(path, script, report)
            if (patched != script) {
                args[6] = patched
                // 每个 SDK 文件只留一行：命中项 + 体积变化。以前 close-emitter /
                // shown-reward / close-reward / playable / symbols 各打一条，
                // 一次小程序启动就是 7 行，日志里 80% 是这里刷出来的。
                WeLogger.i(
                    TAG,
                    "SDK ${path.substringAfterLast('/')} 已注入：${report.summary()} " +
                        "delta=${patched.length - script.length}",
                )
            } else {
                WeLogger.d(TAG, "SDK ${path.substringAfterLast('/')} 无需改动（${report.summary()}）")
            }
        }

        // 4) bridge 事件日志: mbAd_* 全家 + 广告会话窗口, 观察完整 MB 流程。
        val bindingClass = "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass()
        bindingClass.reflekt().firstMethod { name = "subscribeHandler" }.hookBefore {
            val type = args.getOrNull(0) as? String ?: return@hookBefore
            val data = (args.getOrNull(1) as? String).orEmpty()
            val now = System.currentTimeMillis()
            val looksAd = type.startsWith("mbAd_") ||
                AD_EVENT_MARKERS.any { type.contains(it, ignoreCase = true) }
            if (looksAd) {
                adEventSessionUntil.set(now + SESSION_WINDOW_MS)
                // 只记录广告相关事件，且只进 logcat。
                // 旧实现会在「广告会话窗口（30s）」内把所有 bridge 事件都写盘，其中加速度传感器
                // onAccelerometerChange 每秒数十条，日志被瞬间灌满且持续占用 IO（卡顿来源）。
                WeLogger.d(TAG, "bridge event type=$type data=${data.take(200)}")
            }
        }
    }

    private fun dumpSdkFile(path: String, content: String) {
        // 仅调试包：把即将执行的 SDK 原文落到模块数据目录（/sdcard/Android/data/…/WCX/sdk_dump），
        // 用于离线分析广告流程。正式包不写，避免每次冷启动多写 3MB。
        if (!BuildConfig.DEBUG) return
        if (!dumpedNames.add(path)) return
        runCatching {
            val dir = (KnownPaths.moduleData / "sdk_dump").createDirsSafe()
            val safeName = path.substringAfterLast('/').ifBlank { path.hashCode().toString() }
            val target = dir / safeName
            target.writeText(content)
            WeLogger.d(TAG, "dumped SDK path=$path size=${content.length} -> $target")
        }.onFailure { WeLogger.e(TAG, "dump SDK path=$path failed", it) }
    }

    private fun isSdkCandidate(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in SDK_CANDIDATES || name.contains("ad", ignoreCase = true)
    }

    /**
     * 动态补丁: 只用稳定字符串/结构定位, 不写死混淆符号名。
     *
     * 注入点在 SDK 的「试玩首帧就绪」处理分支
     * (t.emitter.emit(<FIRST_FRAME_READY 变量>);break;), 1 秒后执行:
     *   <置奖励函数>.call(this,0,!0)          -> isRewarded=true, KX/uZ 发 close 才带 isEnded;
     *   Promise.resolve(<关闭链路函数>.call(this)) -> 移除试玩视图 + 容器收起 +
     *                                          emit close({isEnded:true}) -> 小程序发奖;
     *   q.destroy()                            -> 通知 MB 运行时关闭广告页。
     * 任一核心结构提取失败 -> WeLogger.w 明确报告, 返回原文不注入。
     */
    private fun patchMbSdk(path: String, content: String, report: PatchReport): String {
        // 策略 1（主）：包住广告的事件发射器，任何 close 事件在交给小程序前都带上 isEnded=true。
        //
        // 实测走过的两条路（MB 主动 OnClose、胶囊关闭）都没有稳定命中，且无法确认 SDK 内部
        // 到底走了哪个 show 分支。发射器是最终出口：`emit(e,t){this.emitter.emit(e,t)}`，
        // 只此一处（每个文件 2 个包装类共用同一实现），在这里改字段不会漏任何分支。
        val withEmitter = patchCloseEmitter(path, content, report)

        // 策略 2：MB 展示成功后置 isRewarded，让 SDK 自己的状态保持一致（兜底，不排定时器）。
        val withShown = patchShownReward(path, withEmitter, report)

        // 策略 3：MB 主动发关闭事件时，强制 payload 的 isEnded 为 true。
        val withClose = patchMbClosePayload(path, withShown, report)

        // 策略 4（试玩广告）：首帧就绪后自动走完整关闭链路。
        return patchPlayableSite(
            path,
            withClose,
            closePatched = withShown != withEmitter || withClose != withShown,
            report = report,
        )
    }

    /**
     * `emit(e,t){this.emitter.emit(e,t)}` → 在转发前把 close 载荷的 isEnded 置真。
     *
     * 这是所有广告事件（load/error/close）的唯一出口，命中 2 处（两个包装类共用实现），
     * 两处都改；只对事件名 "close" 且载荷是对象时才动，其它事件零影响。
     */
    private fun patchCloseEmitter(path: String, content: String, report: PatchReport): String {
        val needle = "emit(e,t){this.emitter.emit(e,t)}"
        val sites = Regex(Regex.escape(needle)).findAll(content).count()
        if (sites == 0) {
            report.fail()
            WeLogger.w(TAG, "close-emitter patch NOT applied path=$path missing=$needle")
            return content
        }
        report.hit("close", sites)
        return content.replace(
            needle,
            "emit(e,t){if(\"close\"===e&&t&&\"object\"==typeof t)t.isEnded=!0;this.emitter.emit(e,t)}",
        )
    }

    /**
     * `a.mbAdHasShown=!0` → `a.mbAdHasShown=!0,a.isRewarded=!0`。
     *
     * 该赋值全文件只有一处，紧跟 `await a.mb?.show(e,h)` 成功返回之后，
     * 让 SDK 自己的 reward 状态与强制 isEnded 保持一致（`lastAdIsEnded` 等读它）。
     * 不依赖这个点去关广告：真正保证小程序收到 isEnded 的是 [patchCloseEmitter]。
     */
    private fun patchShownReward(path: String, content: String, report: PatchReport): String {
        val re = Regex("""(\w+)\.mbAdHasShown=!0""")
        val sites = re.findAll(content).count()
        if (sites == 0) {
            report.fail()
            WeLogger.w(TAG, "shown-reward patch NOT applied path=$path missing=<x>.mbAdHasShown=!0")
            return content
        }
        if (sites > 1) {
            report.fail()
            WeLogger.w(TAG, "shown-reward patch skipped path=$path matched=$sites (expected 1)")
            return content
        }
        report.hit("shown", sites)
        return re.replace(content) { m ->
            val it = m.groupValues[1]
            // 必须捕获接收者：a 是广告状态对象，写成裸 isRewarded=!0 只会创建全局变量。
            "$it.mbAdHasShown=!0,$it.isRewarded=!0"
        }
    }

    /**
     * 把 `emitClose(<x>.data)` 改成 `emitClose(Object.assign({},<x>.data,{isEnded:!0}))`。
     *
     * 用 `emitClose\((\w+)\.data\)` 而不是写死参数名：这是 webpack 压缩后的形参（本次是 `e`），
     * 不同基础库版本会变。全文件只有 MB OnClose 这一处调用，命中即为目标点。
     */
    private fun patchMbClosePayload(path: String, content: String, report: PatchReport): String {
        val re = Regex("""emitClose\((\w+)\.data\)""")
        val sites = re.findAll(content).count()
        if (sites == 0) {
            report.fail()
            WeLogger.w(TAG, "close-reward patch NOT applied path=$path missing=emitClose(<x>.data)")
            return content
        }
        report.hit("closePayload", sites)
        return re.replace(content) { m ->
            "emitClose(Object.assign({},${m.groupValues[1]}.data,{isEnded:!0}))"
        }
    }

    private fun patchPlayableSite(
        path: String,
        content: String,
        closePatched: Boolean,
        report: PatchReport,
    ): String {
        val ox = Regex("""($ID)="PLAYABLE_FIRST_FRAME_READY"""")
            .find(content)?.groupValues?.get(1)
        if (ox == null) {
            return unsupported(path, content, "PLAYABLE_FIRST_FRAME_READY 常量", closePatched, report)
        }

        val reward = findRewardSetter(content) ?: return unsupported(
            path, content, "置奖励函数 (function(e,t=!1)+isRewarded=!0+countDown)", closePatched, report,
        )

        val close = findCloseFlow(content) ?: return unsupported(
            path, content, "关闭链路函数 (async function(e=0)+lastAdIsEnded)", closePatched, report,
        )

        if (!hasMbChannel(content)) {
            return unsupported(path, content, "MB 通道实例字段 (.mb=new / .mbId)", closePatched, report)
        }

        val req = findRequestHelper(content)
        val skip = buildSkip(reward, close, req)
        val siteRegex = Regex("""emitter\.emit\(""" + Regex.escape(ox) + """\);break;""")
        val sites = siteRegex.findAll(content).count()
        if (sites == 0) {
            return unsupported(path, content, "首帧注入点 emitter.emit($ox);break;", closePatched, report)
        }

        report.hit("playable", sites)
        // 混淆符号名只在做锚点适配时有用，正常使用不需要落盘。
        WeLogger.d(
            TAG,
            "sdk patch symbols path=$path ox=$ox reward=$reward close=$close " +
                "req=${req ?: "N/A"} sites=$sites",
        )
        return siteRegex.replace(content) { it.value.replace(";break;", ",$skip;break;") }
    }

    private fun unsupported(
        path: String,
        content: String,
        missing: String,
        closePatched: Boolean = false,
        report: PatchReport? = null,
    ): String {
        if (closePatched) {
            // 视频激励分支已经生效，试玩分支缺失只是「这类广告不适用」，不该报成告警。
            WeLogger.d(TAG, "playable patch skipped path=$path missing=$missing（视频关闭分支已生效）")
        } else {
            report?.fail()
            WeLogger.w(TAG, "skip patch NOT applied path=$path missing=$missing (SDK 结构可能已改版)")
        }
        return content
    }

    /**
     * SDK 补丁命中情况收集器。
     *
     * 原来每个补丁点各打一条 I 日志（close-emitter / shown-reward / close-reward /
     * playable / symbols），一个 SDK 文件 5~7 行，而每次小程序启动都要走两遍。
     * 现在只在结束时汇总成一行；只有「应该命中却没命中」才单独升 W。
     */
    private class PatchReport {
        private val hits = mutableListOf<String>()
        private var failed = false

        fun hit(name: String, sites: Int) {
            hits += "$name=$sites"
        }

        fun fail() {
            failed = true
        }

        fun summary(): String {
            if (hits.isEmpty()) return "无命中"
            val text = hits.joinToString(" ")
            return if (failed) "$text（有未命中项）" else text
        }
    }

    /** 置奖励函数: 倒计时结束显示函数, 特征签名 + 体内置 isRewarded + 操作 countDown UI。 */
    private fun findRewardSetter(content: String): String? {
        val re = Regex("""($ID)=function\(e,t=!1\)\{var a=$ID\(this\)""")
        return re.findAll(content).firstOrNull { m ->
            val frag = content.substring(m.range.last, minOf(m.range.last + 400, content.length))
            frag.contains("isRewarded=!0") && frag.contains("countDown")
        }?.groupValues?.get(1)
    }

    /** 关闭链路函数: RewardedVideoAd 全量关闭, 特征 async 签名 + 体内 lastAdIsEnded。 */
    private fun findCloseFlow(content: String): String? {
        val re = Regex("""($ID)=async function\(e=0\)\{if\($ID\.call\(this\)\)throw""")
        return re.findAll(content).firstOrNull { m ->
            val frag = content.substring(m.range.last, minOf(m.range.last + 6000, content.length))
            frag.contains("lastAdIsEnded")
        }?.groupValues?.get(1)
    }

    /** 发请求助手 (marker 用): VerifyAdRewardEligibility 请求上下文里的调用名。 */
    private fun findRequestHelper(content: String): String? {
        val re = Regex(
            """await ($ID)\(\{apiName:"webapi_getadvert",reqData:\{action:"weapp_comm",""" +
                """request_data:JSON\.stringify\(\{rpc_method:"VerifyAdRewardEligibility""""
        )
        return re.find(content)?.groupValues?.get(1)
    }

    /** MB 通道实例字段存在性校验 (t.mb / q.mbId), 属性名若改版会在这里暴露。 */
    private fun hasMbChannel(content: String): Boolean {
        return Regex("""\.mb=new $ID\(""").containsMatchIn(content) &&
            Regex("""\.mbId""").containsMatchIn(content)
    }

    private fun buildSkip(reward: String, close: String, req: String?): String {
        val marker = if (req != null) {
            "$req({apiName:\"webapi_getadvert\",reqData:{action:\"weapp_comm\"," +
                "request_data:JSON.stringify({rpc_method:\"WEKIT_PLAYABLE_CLOSE\",traceid:id})}});"
        } else {
            ""
        }
        return "setTimeout((()=>{try{var w=globalThis;w.__wekitPlayable=w.__wekitPlayable||{};" +
            "var id=t.adProxy?.data?.traceid;" +
            "if(!w.__wekitPlayable[id]){w.__wekitPlayable[id]=1;$marker" +
            "var q=t.mb;if(q&&q.mbId){t.isEnded=!0," +
            "$reward.call(this,0,!0),Promise.resolve($close.call(this)).catch(function(){})," +
            "q.destroy().catch(function(){})}}}catch(_){}}),$SKIP_DELAY_MS)"
    }
}
