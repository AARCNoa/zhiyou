package com.AARCNoa.zhiyou.features.items.payment

import android.content.Intent
import androidx.activity.ComponentActivity
import com.AARCNoa.zhiyou.activity.RedPacketStatsActivity
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.WeLogger

/**
 * 抢红包金额统计：本地记录抢到的红包（仅本机保存，不上传）。
 *
 * 主开关以 @Feature name 为 SP 键（工程惯例，同“自动抢红包”等）。
 * 关闭时不执行任何统计读写（由 [com.AARCNoa.zhiyou.features.items.payment.stats.RedPacketStatsManager.isEnabled]
 * 在抢红包成功回调处短路）。
 *
 * 本功能无全局 hook：写入动作挂在 AutoOpenRedPackets 的抢红包成功回调内，
 * 点击开关行右侧详情图标可进入统计详情页。
 */
@Feature(
    name = "抢红包金额统计",
    categories = ["红包与支付"],
    description = "本地统计抢到的红包金额与数量，数据仅保存在本机",
)
object RedPacketStatsFeature : ClickableFeature() {

    private const val TAG = "RedPacketStatsFeature"

    override fun onClick(context: ComponentActivity) {
        runCatching {
            context.startActivity(
                Intent(context, RedPacketStatsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to open red packet stats page", e)
        }
    }
}
