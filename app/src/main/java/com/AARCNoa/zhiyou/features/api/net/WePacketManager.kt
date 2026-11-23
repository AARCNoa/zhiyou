package com.AARCNoa.zhiyou.features.api.net

import com.AARCNoa.zhiyou.constants.Preferences
import com.AARCNoa.zhiyou.features.api.net.abc.IWePacketInterceptor
import com.AARCNoa.zhiyou.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

object WePacketManager {

    private const val MAX_PACKET_LOG_CHARS = 48_000
    private val listeners = CopyOnWriteArrayList<IWePacketInterceptor>()

    private fun boundedPacketJson(data: WeProtoData): String {
        val json = data.toJsonObject().toString()
        return if (json.length <= MAX_PACKET_LOG_CHARS) json else
            json.take(MAX_PACKET_LOG_CHARS) + "\n...[packet log truncated; total ${json.length} chars]"
    }

    fun addInterceptor(interceptor: IWePacketInterceptor) = listeners.addIfAbsent(interceptor)

    fun removeInterceptor(interceptor: IWePacketInterceptor) = listeners.remove(interceptor)

    /**
     * 没有任何拦截器时，请求/响应都不可能被改写。
     *
     * [com.AARCNoa.zhiyou.features.api.net.listener.WePacketDispatcher] 据此直接放行：
     * 不取 uri/cgiId、不反射请求体、不装回调代理。绝大多数用户没开广告/余额这类功能，
     * 也就不需要在网络主干上付任何代价。
     */
    val hasInterceptors: Boolean get() = listeners.isNotEmpty()

    /**
     * 这个请求是否落在任一拦截器的处理范围内。
     *
     * [com.AARCNoa.zhiyou.features.api.net.listener.WePacketDispatcher] 只在返回 true 时才安装回调代理：
     * 装代理意味着要替换微信自己的回调对象，能不做就不做。
     */
    fun needsResponse(uri: String, cgiId: Int): Boolean = listeners.any {
        runCatching { it.interests(uri, cgiId) }.getOrDefault(true)
    }

    internal fun handleResponseTamper(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (Preferences.verboseLog) {
            val data = WeProtoData.fromBytes(respBytes)
            WeLogger.logChunkedD(
                "WePacketInterceptor.Response",
                "Response: $uri, CGI=$cgiId, LEN=${respBytes.size}, Data=${boundedPacketJson(data)}"
            )
        }
        for (listener in listeners) {
            val tampered = listener.onResponse(uri, cgiId, respBytes)
            if (tampered != null) return tampered
        }
        return null
    }
}
