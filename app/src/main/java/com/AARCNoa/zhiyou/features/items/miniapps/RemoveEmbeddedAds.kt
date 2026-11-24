package com.AARCNoa.zhiyou.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexConstructor
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import org.json.JSONObject
import java.lang.reflect.Field

@Feature(name = "移除嵌入广告", categories = ["小程序"], description = "移除小程序嵌入广告")
object RemoveEmbeddedAds : SwitchFeature(), IResolveDex {

    private val ctorNetSceneJSOperateWxData by dexConstructor {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
            }
        }
    }
    private val methodBaseTransferRequestOnLoad by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.BaseTransferRequest")
            paramTypes("com.tencent.mm.plugin.brandservice.api.TransferResultInfo")
        }
    }

    private lateinit var protoField: Field

    /** 广告请求里表示广告位 / 插槽的字段，清空后接口拿不到可投广告的位置。 */
    private val AD_SLOT_KEYS = arrayOf(
        "ad_unit_id", "adunitid", "adUnitId", "pos_id", "ad_slot", "ad_slot_data",
    )

    /**
     * 广告接口判定。
     *
     * 旧实现要求 `api_name` 精确等于 `webapi_getadvert`。8.0.80 实测这个串已不在任何 dex 里，
     * 微信换了广告接口命名，精确匹配恒为 false —— 功能完全失效。这里按语义匹配：
     * 名字里带 advert / adunit / ad_unit 的都当广告接口处理。旧版本的 `webapi_getadvert`
     * 同样命中，行为不变。
     */
    private fun isAdApiName(name: String): Boolean {
        if (name.isBlank()) return false
        val lower = name.lowercase()
        return lower.contains("advert") || lower.contains("adunit") || lower.contains("ad_unit")
    }

    override fun onEnable() {
        ctorNetSceneJSOperateWxData.hookBefore {
            // 从全部实参中定位承载 JSON 的那个（构造器参数顺序可能随版本变化）。
            // 原实现把 api_name 当字符串字面量比较（恒为 false），导致本功能从未生效。
            val idx = args.indexOfFirst { arg ->
                arg is String && runCatching {
                    isAdApiName(JSONObject(arg).optString("api_name"))
                }.getOrDefault(false)
            }
            if (idx < 0) return@hookBefore
            val json = runCatching { JSONObject(args[idx] as String) }.getOrNull() ?: return@hookBefore
            // data 缺失时补一个空对象：有的广告接口把广告位放在顶层之外的 data 里。
            val data = json.optJSONObject("data") ?: JSONObject().also { json.put("data", it) }
            // 两类处理都要：旧接口认「存在但为空」的 ad_unit_id，新接口按字段存在与否决定要不要拉广告。
            AD_SLOT_KEYS.forEach { key ->
                if (key == "ad_unit_id") data.put(key, "") else data.remove(key)
            }
            args[idx] = json.toString()
        }

        methodBaseTransferRequestOnLoad.hookBefore {
            val transferResultInfo = args[0]
            if (!::protoField.isInitialized) {
                protoField = transferResultInfo.reflekt()
                    .firstField {
                        type { !it.isBuiltin }
                    }.self
            }

            val proto = protoField.get(transferResultInfo)
            proto.reflekt()
                .fields {
                    type = String::class
                }.forEach {
                    val jsonStr = it.get() as? String? ?: return@forEach
                    if (jsonStr.isBlank()) return@forEach
                    val json = runCatching { JSONObject(jsonStr) }.getOrElse { return@forEach }
                    if (!json.has("ad_slot_data")) return@forEach
                    it.set("{}")
                }
        }
    }
}
