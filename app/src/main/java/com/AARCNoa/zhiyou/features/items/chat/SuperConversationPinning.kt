package com.AARCNoa.zhiyou.features.items.chat

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.data
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.features.api.core.WeConversationApi
import com.AARCNoa.zhiyou.features.api.ui.WeConversationContextMenuApi
import com.AARCNoa.zhiyou.features.core.Feature

import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.preferences.WePrefs.Companion.prefOption
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.EditIcon
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.serialization.DefaultJson
import java.util.concurrent.atomic.AtomicBoolean

@Feature(
    name = "超级对话置顶",
    categories = ["聊天"],
    description = "在首页对话列表长按菜单设置优先级, 置顶和非置顶对话分别按优先级排序"
)
object SuperConversationPinning : SwitchFeature(),
    WeConversationContextMenuApi.IMenuItemsProvider,
    IResolveDex {

    private const val TAG = "SuperConversationPinning"
    private const val PRIORITIES_KEY = "super_conversation_pinning_priorities"
    private const val MENU_ITEM_ID = 777021
    private const val PLACED_TOP_BIT = 4611686018427387904L

    private var storedPriorities by prefOption(PRIORITIES_KEY, "{}")

    @Volatile
    private var priorities: Map<String, Int> = emptyMap()

    private val mvvmOrderingApplied = AtomicBoolean()

    override fun onEnable() {
        priorities = loadPriorities()
        WeConversationContextMenuApi.addProvider(this)
        hookMvvmConversationComparator()
        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
        WeConversationApi.reloadConversations()
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
        WeConversationContextMenuApi.MenuItem(
            id = MENU_ITEM_ID,
            text = ("设置优先级"),
            drawable = EditIcon,
            shouldShow = { context, _ -> context.talker.isNotEmpty() },
            onClick = { context -> showPriorityDialog(context.activity, context.talker) }
        )
    )

    private fun showPriorityDialog(context: Context, talker: String) {
        showComposeDialog(context) {
            var priority by remember(talker) { mutableIntStateOf(priorityOf(talker)) }

            AlertDialogContent(
                title = { Text("设置优先级") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("置顶优先级：".format(priority))
                        Slider(
                            value = priority.toFloat(),
                            onValueChange = { priority = it.toInt() },
                            valueRange = 0f..10f,
                            steps = 9
                        )
                        Text("优先级越高，在同一置顶状态内越靠前。0 为微信默认排序。")
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button({
                        setPriority(talker, priority)
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private fun priorityOf(talker: String): Int = priorities[talker] ?: 0

    private fun setPriority(talker: String, priority: Int) {
        val updated = priorities.toMutableMap()
        if (priority == 0) {
            updated.remove(talker)
        } else {
            updated[talker] = priority.coerceIn(1, 10)
        }
        priorities = updated
        storedPriorities = DefaultJson.encodeToString(updated)
        mvvmOrderingApplied.set(false)
        WeLogger.i(TAG, "saved priority $priority for $talker")
        WeConversationApi.reloadConversations()
    }

    private val classConversation by dexClass {
        matcher {
            usingEqStrings("MicroMsg.Conversation", "[%s}]get mmkv editmsg is [%s],get conv editmsg is [%s]")
        }
    }

    private val classMvvmConversationAdapter by dexClass {
        matcher {
            superClass {
                usingEqStrings("null cannot be cast to non-null type T of com.tencent.mm.plugin.mvvmlist.BaseMvvmListItem")
            }

            addField {
                type(classConversation.data.name)
            }
        }
    }

    private fun hookMvvmConversationComparator() {
        if (classMvvmConversationAdapter.isPlaceholder) {
            WeLogger.w(TAG, "mvvm conversation comparator unavailable")
            return
        }

        classMvvmConversationAdapter.reflekt().firstMethod("compareTo").hookBefore {
            val currentPriorities = priorities
            if (currentPriorities.isEmpty()) return@hookBefore

            val currentModel = thisObject ?: return@hookBefore
            val otherModel = args.firstOrNull() ?: return@hookBefore

            fun Any.getConversation(): Any {
                return reflekt().firstField {
                    type = classConversation.clazz
                }.get()!!
            }

            fun Any.getUsername(): String? {
                return reflekt().firstField {
                    name = "field_username"
                    superclass()
                }.get() as? String?
            }

            fun Any.getFlag(): Long {
                return (reflekt().firstField {
                    name = "field_flag"
                    superclass()
                }.get() as Number).toLong()
            }

            val currentConversation = currentModel.getConversation()
            val otherConversation = otherModel.getConversation()
            val currentUsername = currentConversation.getUsername()
            val otherUsername = otherConversation.getUsername()
            val currentFlag = currentConversation.getFlag()
            val otherFlag = otherConversation.getFlag()
            val currentPinned = currentFlag and PLACED_TOP_BIT != 0L
            val otherPinned = otherFlag and PLACED_TOP_BIT != 0L

            if (currentPinned != otherPinned) {
                result = if (currentPinned) -1 else 1
                return@hookBefore
            }

            val currentPriority = currentPriorities[currentUsername] ?: 0
            val otherPriority = currentPriorities[otherUsername] ?: 0
            if (currentPriority == otherPriority) return@hookBefore

            result = otherPriority.compareTo(currentPriority)

            if (mvvmOrderingApplied.compareAndSet(false, true)) {
                WeLogger.i(
                    TAG,
                    "applied mvvm homepage ordering: $currentUsername=$currentPriority, " +
                            "$otherUsername=$otherPriority, pinned=$currentPinned"
                )
            }
        }
    }

    private fun loadPriorities(): Map<String, Int> = runCatching {
        DefaultJson.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), storedPriorities)
            .filter { (talker, priority) -> talker.isNotBlank() && priority in 1..10 }
    }.getOrDefault(emptyMap())
}
