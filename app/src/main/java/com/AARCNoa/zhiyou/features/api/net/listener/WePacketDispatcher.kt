package com.AARCNoa.zhiyou.features.api.net.listener

import android.os.Handler
import android.os.Looper
import androidx.core.os.postDelayed
import com.tencent.kinda.framework.module.impl.WXPCommReqResp
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.features.api.net.WePacketHelper
import com.AARCNoa.zhiyou.features.api.net.WePacketManager
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 响应数据包拦截。
 *
 * 只做一件事：对**拦截器声明关心的**请求，把回调换成一个代理，在 `onGYNetEnd` 里改写响应字节。
 * 其余请求（消息收发、同步、拉取……）一律原样放行——不读请求体、不换回调。
 *
 * 历史包袱：这里曾经还会解析出站请求体喂给 `onRequest`，并且"取不到请求体也要把代理装上"。
 * 但三个拦截器（公众号信息流广告、文章广告、收银台余额）都只实现了 `onResponse`，
 * 请求篡改没有任何消费者；而那段反射在每条消息发送时都会跑，取不到字节还会走到装代理的分支，
 * 等于把全模块的风险压到了发送主路径上。
 */
@Feature(name = "数据包拦截与篡改服务", categories = ["API"], description = "响应数据包拦截与篡改")
object WePacketDispatcher : ApiFeature(), IResolveDex {

    private const val TAG = "WePacketDispatcher"

    private val classOnGYNetEnd by dexClass {
        searchPackages("com.tencent.mm.network")
        matcher {
            methodCount(1)
            methods {
                add {
                    name = "onGYNetEnd"
                    paramCount = 6
                }
            }
        }
    }

    /** 只在首次遇到某个回调类型时打一条，避免每条消息都刷日志。 */
    private val loggedSkipClasses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 把 `parseFrom(bytes)` 的结果写回 wrapper 的 `a` 字段。
     *
     * protobuf 的 `parseFrom` 是静态工厂（`parseFrom(byte[]) -> XxxResp`），返回的是**新对象**，
     * 不会修改传入的那个。旧实现拿到篡改后的字节后调用它却把返回值丢掉了，容器里的 `a` 字段
     * 一个字节都没变 —— 配了响应篡改规则也毫无效果。
     *
     * 失败返回 null（调用方只记日志，不影响原响应）。
     */
    private fun applyParsedPb(wrapper: Any, current: Any, tampered: ByteArray): Any? = runCatching {
        val method = (current.javaClass.declaredMethods.asSequence() + current.javaClass.methods.asSequence())
            .firstOrNull {
                it.name == "parseFrom" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == ByteArray::class.java &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            } ?: return@runCatching null
        method.isAccessible = true
        val parsed = method.invoke(null, tampered) ?: return@runCatching null
        wrapper.reflekt().setField("a", parsed)
        parsed
    }.onFailure {
        // 每个 cgi 都可能失败一次（同类 pb 类没有对应 parseFrom），W 级会把日志刷满 —— 降为 D。
        WeLogger.d(TAG, "apply parsed pb failed: ${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    override fun onEnable() {
        Handler(Looper.getMainLooper()).postDelayed(3000) {
            try {
                val netSceneBaseClass = WePacketHelper.classNetSceneBase.clazz
                val callbackInterface = classOnGYNetEnd.clazz
                val callbackInterfaceName = callbackInterface.name

                netSceneBaseClass.reflekt().firstMethod { name = "dispatch" }.hookBefore {
                    // 这是全模块唯一挂在网络主干上的 hook。它跑在微信自己的发送线程里，
                    // 任何异常冒出去都会让调用方当成本地失败——表现就是"一发消息直接红色感叹号"。
                    // 所以整段必须兜住：宁可这次不改写，也不能打断 dispatch。
                    try {
                        // 没有拦截器 = 响应不会被改写，直接放行。
                        if (!WePacketManager.hasInterceptors) return@hookBefore

                        val v0Var = args[1] ?: return@hookBefore
                        val originalCallback = args[2] ?: return@hookBefore

                        // 有时 getUri 返回 null
                        val v0Ref = v0Var.reflekt()
                        val uri = (v0Ref.invokeMethod("getUri", superclass = true) as? String) ?: "null"
                        val cgiId = v0Ref.invokeMethod("getType", superclass = true) as? Int ?: -1

                        // 消息、同步、拉取等绝大多数请求都不在任何拦截器的范围内。
                        // 这里直接放行：不装代理，也不做任何字节读写。
                        if (!WePacketManager.needsResponse(uri, cgiId)) return@hookBefore

                        if (Proxy.isProxyClass(originalCallback.javaClass)) return@hookBefore

                        // 只替换「确实实现了目标接口」的回调：微信不同 NetScene 的回调接口不止一个，
                        // 装错接口的代理会在调用点抛 ClassCastException，同样会打断发送。
                        if (!callbackInterface.isAssignableFrom(originalCallback.javaClass)) {
                            if (loggedSkipClasses.add(originalCallback.javaClass.name)) {
                                WeLogger.d(
                                    TAG,
                                    "skip proxy: ${originalCallback.javaClass.name} 未实现 $callbackInterfaceName"
                                )
                            }
                            return@hookBefore
                        }

                        args[2] = Proxy.newProxyInstance(
                            ClassLoaders.HOST,
                            arrayOf(callbackInterface)
                        ) { _, method, args ->
                            when (method.name) {
                                "hashCode" -> return@newProxyInstance originalCallback.hashCode()
                                "toString" -> return@newProxyInstance originalCallback.toString()
                                "equals" -> return@newProxyInstance originalCallback == args?.get(0)
                                "onGYNetEnd" -> {
                                    runCatching {
                                        val respV0 = args!![4] ?: v0Var
                                        val respV0Ref = respV0.reflekt()

                                        // 处理 Kinda 框架的 WXPCommReqResp
                                        if (respV0 is WXPCommReqResp) {
                                            val originalRespBytes = respV0.wxpRespData
                                            if (originalRespBytes != null) {
                                                WePacketManager.handleResponseTamper(
                                                    uri,
                                                    cgiId,
                                                    originalRespBytes
                                                )?.let { tampered ->
                                                    respV0Ref.invokeMethod("setWXPRespData", tampered, superclass = true)
                                                    WeLogger.i(
                                                        TAG,
                                                        "tampered response (WXP): $uri"
                                                    )
                                                }
                                            }
                                        }
                                        // 处理标准混淆的 ICommReqResp 实现
                                        else {
                                            val respWrapper = runCatching {
                                                respV0Ref.getField("b", superclass = true)
                                            }.getOrElse { respV0Ref.invokeMethod("getRespObj", superclass = true) }

                                            if (respWrapper != null) {
                                                val respPbObj = runCatching {
                                                    respWrapper.reflekt().getField("a", superclass = true)
                                                }.getOrNull()

                                                if (respPbObj != null) {
                                                    val respPbObjRef = respPbObj.reflekt()

                                                    runCatching {
                                                        val originalRespBytes = respPbObjRef
                                                            .invokeMethod("toByteArray", superclass = true)!! as ByteArray
                                                        WePacketManager.handleResponseTamper(
                                                            uri,
                                                            cgiId,
                                                            originalRespBytes
                                                        )?.let { tampered ->
                                                            // parseFrom 返回新对象，必须写回 wrapper 才生效。
                                                            if (applyParsedPb(respWrapper, respPbObj, tampered) != null) {
                                                                WeLogger.i(
                                                                    TAG,
                                                                    "tampered response (PB): $uri"
                                                                )
                                                            } else {
                                                                WeLogger.w(
                                                                    TAG,
                                                                    "篡改后的响应没能写回（parseFrom 未找到），本次按原样返回: $uri"
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }.onFailure { WeLogger.e(TAG, "failed to tamper inner logic", it) }
                                }
                            }

                            // 把原回调里抛的异常原样抛回去：反射调用会包一层
                            // InvocationTargetException，不拆包会变成 UndeclaredThrowableException，
                            // 微信那侧的类型判断就走样了。
                            try {
                                return@newProxyInstance method.invoke(
                                    originalCallback,
                                    *(args ?: emptyArray())
                                )
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException ?: e
                            }
                        }
                    } catch (t: Throwable) {
                        // 兜底：任何模块侧异常都不能传播给微信的 dispatch
                        WeLogger.e(TAG, "dispatch hook failed, passthrough", t)
                    }
                }
            } catch (ex: IllegalStateException) {
                WeLogger.w(TAG, "exception occurred during entry", ex)
            }
        }
    }
}
