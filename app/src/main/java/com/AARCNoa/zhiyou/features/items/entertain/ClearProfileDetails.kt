package com.AARCNoa.zhiyou.features.items.entertain

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.AARCNoa.zhiyou.features.api.core.WeDatabaseApi
import com.AARCNoa.zhiyou.features.api.core.models.SelfProfileField
import com.AARCNoa.zhiyou.features.api.net.WePacketHelper
import com.AARCNoa.zhiyou.features.api.net.models.protobuf.OpLog
import com.AARCNoa.zhiyou.features.api.net.models.protobuf.OpLogRespProto
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(name = "清空资料信息", categories = ["个人资料"], description = "清空当前用户的地区信息（性别需在微信资料页手动修改）")
object ClearProfileDetails : ClickableFeature() {

    private const val TAG = "ClearProfileDetails"

    /** 8.0.79 的「我的资料快照 + ModProfile 构造器」，`kb.e(kb)` 返回完整的 `ke5.fy4`。 */
    private const val PROFILE_SNAPSHOT_CLASS = "l51.kb"

    /**
     * 用微信自己的构造器生成清空地区的请求。
     *
     * 之前手写 `ModProfileProto` 的问题：
     * - 地区字段不是普通字符串，而是带 `hasValue` 布尔标记的 `UserName` 结构；
     * - 省/市还有独立的 code 字段（12293/12292 与 12325/12326）；
     * - `flag=128` 是原生固定的资料更新标记。
     * 少一个标记，服务端就会忽略整个字段（上次只改了性别、地区没动）。
     *
     * `kb.a()` 先读当前资料快照，把地区相关字段全部置空，再交给 `kb.e()` 生成与原生
     * 资料编辑完全一致的 proto。`e()` 同时会把本地 config 的对应字段写成空值。
     */
    private fun buildClearRegionBytes(): ByteArray? = runCatching {
        val kbClass = Class.forName(PROFILE_SNAPSHOT_CLASS, false, ClassLoaders.HOST)

        val snapshot = kbClass.getMethod("a").invoke(null)
        // c=省 12293, d=市 12292, f=国家 12307, g=国家码 12324,
        // h=省码 12325, i=市码 12326
        for (field in listOf("c", "d", "f", "g", "h", "i")) {
            kbClass.getField(field).set(snapshot, "")
        }

        val profile = kbClass.getMethod("e", kbClass).invoke(null, snapshot)
        profile.javaClass.getMethod("toByteArray").invoke(profile) as ByteArray
    }.onFailure {
        WeLogger.e(TAG, "构建资料更新请求失败（宿主类 $PROFILE_SNAPSHOT_CLASS 可能已改版）", it)
    }.getOrNull()

    /** 读当前地区字段，用于发送后确认是否真的生效。 */
    private fun regionSummary(): String {
        fun read(field: SelfProfileField): String =
            runCatching { WeDatabaseApi.getSelfProfileField(field)?.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "未设置"

        return "省=${read(SelfProfileField.PROVINCE)}，市=${read(SelfProfileField.CITY)}，" +
            "省码=${read(SelfProfileField.PROVINCE_CODE)}，市码=${read(SelfProfileField.CITY_CODE)}"
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清空地区信息") },
                text = {
                    Text(
                        "只清空地区（省/市/国家及对应编码），不会修改性别。\n\n" +
                            "注意：微信原生资料编辑只支持「男 / 女」，没有「未设置性别」选项；" +
                            "性别一旦改过，无法恢复为未设置"
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        val payload = buildClearRegionBytes()
                        if (payload == null) {
                            showComposeDialog(context) {
                                AlertDialogContent(
                                    title = { Text("当前版本不支持") },
                                    text = { Text("找不到微信的资料构造器（l51.kb），请把日志发给开发者适配。") },
                                    confirmButton = {
                                        TextButton(onClick = onDismiss) { Text("关闭") }
                                    }
                                )
                            }
                            return@Button
                        }

                        val reqBytes = OpLog.encodeRequest(
                            listOf(OpLog.operationRaw(OpLog.CMD_CLEAR_PROFILE, payload))
                        )
                        WePacketHelper.sendCgiRaw(
                            "/cgi-bin/micromsg-bin/oplog",
                            681, 0, 0,
                            reqBytes = reqBytes
                        ) {
                            onSuccess { bytes ->
                                val ret = bytes?.let { OpLogRespProto.decode(it).ret }
                                WeLogger.i(TAG, "clear-region submitted, ret=$ret")

                                CoroutineScope(Dispatchers.IO).launch {
                                    delay(1_000)
                                    val summary = regionSummary()
                                    WeLogger.i(TAG, "after clear-region: $summary")
                                    withContext(Dispatchers.Main) {
                                        if (context.isFinishing || context.isDestroyed) return@withContext
                                        showComposeDialog(context) {
                                            AlertDialogContent(
                                                title = { Text(if (ret == 0) "已提交" else "服务器返回异常") },
                                                text = {
                                                    Text(
                                                        "服务器返回码：${ret ?: "未知"}\n" +
                                                            "当前地区：$summary\n\n" +
                                                            "若仍有值，说明该版本未接受这次资料更新。"
                                                    )
                                                },
                                                confirmButton = {
                                                    TextButton(onClick = onDismiss) { Text("关闭") }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            onFailure { type, code, msg ->
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("发送失败") },
                                        text = { Text("type: $type, code: $code, msg: $msg") },
                                        confirmButton = {
                                            TextButton(onClick = onDismiss) { Text("关闭") }
                                        }
                                    )
                                }
                            }
                        }
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    override val noSwitchWidget: Boolean
        get() = true
}
