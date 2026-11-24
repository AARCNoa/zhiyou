package com.AARCNoa.zhiyou.update

import kotlinx.serialization.Serializable

/**
 * 更新清单，对应仓库根目录的 `update.json`。
 *
 * 只描述「最新版本是什么、从哪下」：要不要更新只看 [versionCode]，
 * 说明文字是给人看的，写错也不影响判断。
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    /** ISO 时间，弹窗里显示前 10 位（日期）；格式不对就当没有。 */
    val publishedAt: String = "",
    val changelog: String = "",
    val assets: List<UpdateAsset> = emptyList(),
)

/**
 * 一个可下载的包。
 *
 * [urls] 是同一个文件的几条线路（直连 + 加速），**顺序不代表优先级**：
 * 谁快用谁，由 [UpdateChecker.pickFastestUrl] 实测决定。
 */
@Serializable
data class UpdateAsset(
    val flavor: String = "",
    val abi: String = "",
    val urls: List<String> = emptyList(),
)

/**
 * 选与当前安装包匹配的资产。
 *
 * 先按入口（standard / legacy）筛，再按设备 ABI 顺序找第一个对得上的；
 * 同入口下一个 ABI 都没有时用第一条 —— arm64 设备装 v7a 能跑，反过来装不上。
 *
 * 入口对不上直接返回 null：换错入口等于换了模块的加载方式，宁可不更新。
 */
fun UpdateManifest.pickAsset(flavor: String, abis: List<String>): UpdateAsset? {
    val sameFlavor = assets.filter { it.urls.isNotEmpty() && it.flavor.equals(flavor, true) }
    if (sameFlavor.isEmpty()) return null
    abis.forEach { abi ->
        sameFlavor.firstOrNull { it.abi.equals(abi, true) }?.let { return it }
    }
    return sameFlavor.first()
}
