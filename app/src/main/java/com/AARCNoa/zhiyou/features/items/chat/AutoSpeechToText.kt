package com.AARCNoa.zhiyou.features.items.chat

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.features.api.core.WeMessageApi
import com.AARCNoa.zhiyou.features.api.core.WeServiceApi
import com.AARCNoa.zhiyou.features.api.core.models.MessageType
import com.AARCNoa.zhiyou.features.api.ui.WeChatMessageViewApi
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.collections.LruCache
import java.lang.reflect.InvocationTargetException

@Feature(name = "自动语音转文字", categories = ["聊天"], description = "自动将语音消息转为文字")
object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    private val processedMessages = LruCache<Long, Boolean>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val TAG = "AutoSpeechToText"

    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        // 这里全是微信内部结构（apiManager 字段、transform 方法签名、MsgInfo 实体），
        // 任一漂移都会抛异常。原来没有兜底：一条消息失败就等于整个功能「没反应」，
        // 日志里也看不到。现在失败要留痕，自检页「最近错误」也能直接看到。
        runCatching { handleCreateView(param, view) }
            .onFailure { com.AARCNoa.zhiyou.utils.WeLogger.e(TAG, "自动语音转文字失败（本条消息跳过）：${it.message}", it) }
    }

    /**
     * 定位「触发转文字」的方法。
     *
     * 8.0.79 的实际签名是 `(MsgInfo, boolean, int, int, int)`——比旧版本多了一个 int，
     * 原来写死的 4 参签名在 8.0.79 上**永远匹配不到**，导致每条语音都抛
     * `No method matching conditions`，功能等于完全没生效。
     *
     * 这里按「首参是 MsgInfo、次参必须是 boolean、其余全是 int」匹配，不锁死参数个数，
     * 以后再增删 int 也不用改代码。同类里还有 `E0(MsgInfo, int)`（设置下载控制类型），
     * 靠「次参必须是 boolean」把它排除掉。
     */
    private fun findTriggerTransform(api: Any) = api.reflekt().firstMethod {
        parameters { types ->
            types.size >= 2 &&
                types[0].isAssignableFrom(WeMessageApi.classMsgInfo.clazz) &&
                types[1] == Boolean::class.javaPrimitiveType &&
                types.drop(2).all { it == Int::class.javaPrimitiveType }
        }
        returnType = Void::class.javaPrimitiveType
    }

    private fun handleCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        val apiMan = chattingContext.reflekt()
            .firstField {
                type = WeServiceApi.apiManagerClass
            }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        val chatViewItem = api.reflekt()
            .firstMethod {
                parameters(Long::class)
                returnType { clazz ->
                    clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
                }
            }
            .invoke(id)

        if (chatViewItem.toString() != "NoTransform") return

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }

        if (WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean) return
        try {
            val trigger = findTriggerTransform(api)
            // 参数个数随版本变化（8.0.79 是 5 个），按实际个数补默认值：
            // 第 2 个参数固定 false（与历史行为一致），其余 int 依次 -1 / 0，与原调用保持同义。
            val args = ArrayList<Any?>(trigger.self.parameterCount)
            args.add(msgInfo.instance)
            args.add(false)
            repeat(trigger.self.parameterCount - 2) { i -> args.add(if (i == 0) -1 else 0) }
            trigger.invoke(*args.toTypedArray())
        } catch (_: InvocationTargetException) {
            // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
            // but that's not what we should care about and doesn't affect functionality
        }
    }
}
