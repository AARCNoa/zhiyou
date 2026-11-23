package com.AARCNoa.zhiyou.features.api.net.abc

interface IWePacketInterceptor {
    /**
     * 这个请求的响应是否可能被 [onResponse] 改写。
     *
     * 返回 false 的请求不会安装回调代理——消息收发、同步、拉取等绝大多数流量都应该落到 false。
     * 默认 true 是为了"新写的拦截器不会因为忘了声明而失效"，已有拦截器请按自己的条件覆盖。
     */
    fun interests(uri: String, cgiId: Int): Boolean = true

    fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? = null
}
