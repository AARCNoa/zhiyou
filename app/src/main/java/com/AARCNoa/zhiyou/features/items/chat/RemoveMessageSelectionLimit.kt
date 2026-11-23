package com.AARCNoa.zhiyou.features.items.chat

import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.core.SwitchFeature
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.hookDirectly
import de.robv.android.xposed.XC_MethodHook
import com.AARCNoa.zhiyou.utils.reflection.bool
import com.AARCNoa.zhiyou.utils.reflection.int
import com.AARCNoa.zhiyou.utils.reflection.void
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet

@Feature(
    name = "解除消息多选数量限制",
    categories = ["聊天"],
    description = "解除聊天界面消息多选至多只能选择 100 条的限制"
)
object RemoveMessageSelectionLimit : SwitchFeature(), IResolveDex {

    private const val SELECTION_LIMIT = 100

    private val methodToggleMessageSelection by dexMethod(allowFailure = true) {
        matcher {
            // 8.0.79 实测：目标就是 adapter.k#i1(MsgIdTalker)Z —— 方法体里
            // `const/16 v4, 0x64` + `CopyOnWriteArraySet.contains/size/remove`。
            //
            // 这里刻意**不写 declaredClass**：原来用的是
            // WeMessageApi.classChattingDataAdapter.clazz，那属于另一个 feature 的委托，
            // 解析顺序不保证；只要本 feature 先解析，拿到的是占位符 Class，
            // declaredClass 就锁死成 LauncherUI，整条匹配必然失败。
            //
            // i1(MsgIdTalker) 在 8.0.79 全包里唯一，加上 100 这个常量足够定位。
            usingNumbers(SELECTION_LIMIT)
            paramTypes("${PackageNames.WECHAT}.plugin.msg.MsgIdTalker")
            returnType(bool)
        }
    }

    private val methodGetSelectedMessageCount by dexMethod(allowFailure = true) {
        matcher {
            // 对应 8.0.79 的 adapter.k#X0()I —— 方法体只有 `return selected.size()`。
            // 同一类里 Y0()/getCount() 也是零参 int，但只有 X0 读写 CopyOnWriteArraySet 字段，
            // 用字段类型就能唯一收窄。
            addUsingField {
                type(CopyOnWriteArraySet::class.java)
            }
            paramCount(0)
            returnType(int)
        }
    }

    private val classChatItemQuickSelect by dexClass {
        searchPackages("${PackageNames.WECHAT}.ui.chatting.component")
        matcher {
            usingEqStrings(
                // 8.0.80：TAG 串与 initViews 串被拆到同一类的不同方法，方法级 AND 失效；
                // 该串在 component 包内唯一（仅 ChatItemQuickSelectComponent 持有），单串精确锁定
                "initViews: chattingQuickSelectRootUp="
            )
        }
    }

    private val methodSetQuickSelectViewEnabled1 by dexMethod(
        allowMultiple = true,
        allowFailure = true,
        resultIndex = 0
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val methodSetQuickSelectViewEnabled2 by dexMethod(
        allowMultiple = true,
        allowFailure = true,
        resultIndex = 1
    ) {
        matcher {
            declaredClass(classChatItemQuickSelect.clazz)
            usingNumbers(SELECTION_LIMIT)
            paramTypes(bool)
            returnType(void)
        }
    }

    private val selectedMessagesField: Field by lazy {
        methodToggleMessageSelection.method.declaringClass.declaredFields.single {
            it.type == CopyOnWriteArraySet::class.java
        }.makeAccessible()
    }

    private data class TemporarilyRemovedSelections(
        val selectedMessages: CopyOnWriteArraySet<Any>,
        val removed: List<Any>
    )

    private val selectedMessageCountOverride = ThreadLocal<Int>()

    // 本地 Xposed 桥的 MethodHookParam.extra 为 val+Bundle（非 fork 的 Any），
    // 临时状态改用 ThreadLocal 传递（与 selectedMessageCountOverride 同风格）
    private val tempRemovedSelections = ThreadLocal<TemporarilyRemovedSelections?>()

    override fun onEnable() {
        // 判定依据改成「两个 hook 目标是否都拿不到」，而不是适配器类在不在：
        // 8.0.79 把 ChattingDataAdapter 混淆成 adapter.k，类名匹配本来就是运气，
        // 真正决定功能能不能跑的是 i1(MsgIdTalker) 和 X0() 这两个方法。
        if (methodToggleMessageSelection.isPlaceholder &&
            methodGetSelectedMessageCount.isPlaceholder
        ) {
            WeLogger.w("RemoveMessageSelectionLimit", "selection methods not found, feature disabled")
            return
        }
        listOf(
            methodSetQuickSelectViewEnabled1,
            methodSetQuickSelectViewEnabled2
        ).forEach {
            it.hookBefore {
                args[0] = true
            }
        }

        if (!methodGetSelectedMessageCount.isPlaceholder) {
            methodGetSelectedMessageCount.hookBefore {
                selectedMessageCountOverride.get()?.let {
                    result = it
                }
            }
        }

        // 这两个方法是「临时移出旧选中项」那套写法的前提。8.0.79 上已解析不到，
        // 直接访问 .method 会抛 IllegalStateException 把整个 onEnable 打断，后面的 hook
        // 全都不装。这里显式判断，解析不到就只保留上面那部分能力。
        if (methodToggleMessageSelection.isPlaceholder) {
            WeLogger.w(
                "RemoveMessageSelectionLimit",
                "toggleMessageSelection not found, overflow selection stays capped at $SELECTION_LIMIT"
            )
            return
        }

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val adapter = param.thisObject ?: return
                val message = param.args[0] ?: return
                @Suppress("UNCHECKED_CAST")
                val selectedMessages = selectedMessagesField.get(adapter) as CopyOnWriteArraySet<Any>
                if (message in selectedMessages || selectedMessages.size < SELECTION_LIMIT) return

                // Let WeChat run its original add and UI refresh path with 99 existing selections.
                val removed = selectedMessages.take(selectedMessages.size - SELECTION_LIMIT + 1)
                selectedMessages.removeAll(removed.toSet())
                tempRemovedSelections.set(TemporarilyRemovedSelections(selectedMessages, removed))
                selectedMessageCountOverride.set(selectedMessages.size + removed.size + 1)
            }

            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val state = tempRemovedSelections.get() ?: return
                val remainingAndNew = state.selectedMessages.toList()
                state.selectedMessages.clear()
                state.selectedMessages.addAll(state.removed)
                state.selectedMessages.addAll(remainingAndNew)
                selectedMessageCountOverride.remove()
            }
        }

        registerUnhook(methodToggleMessageSelection.method.hookDirectly(hook))
    }
}
