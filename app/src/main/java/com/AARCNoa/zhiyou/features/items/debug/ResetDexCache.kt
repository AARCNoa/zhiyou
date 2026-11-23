package com.AARCNoa.zhiyou.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import com.AARCNoa.zhiyou.dexkit.cache.DexCacheManager
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(name = "重置适配信息", categories = ["调试"], description = "清除全部 DEX 适配信息, 等待下次启动时重新适配")
object ResetDexCache : ClickableFeature() {

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清除适配信息") },
                text = {
                    Text(
                        "这将删除所有的 DEX 适配信息，宿主重启后需要重新适配。\n" +
                                "确定清除吗？"
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            showToastSuspend("正在清除...")
                            DexCacheManager.clearAllCache()
                            showToastSuspend("清除成功!")
                            withContext(Dispatchers.Main) {
                                onDismiss()
                            }
                        }
                    }) { Text("确定") }
                })
        }
    }

    override val noSwitchWidget = true
}
