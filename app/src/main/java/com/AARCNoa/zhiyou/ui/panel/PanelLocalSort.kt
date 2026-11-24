package com.AARCNoa.zhiyou.ui.panel

import com.AARCNoa.zhiyou.features.items.chat.panel.LocalSortMode
import com.AARCNoa.zhiyou.ui.icons.OutlinedIcons

internal fun panelLocalSortAction(
    mode: LocalSortMode,
    enabled: Boolean = true,
    onModeChange: (LocalSortMode) -> Unit,
    onStartCustomOrder: () -> Unit,
) = PanelAction(
    icon = OutlinedIcons.Sort,
    label = mode.label,
    enabled = enabled,
    showLabel = true,
    onLongClick = onStartCustomOrder.takeIf { mode == LocalSortMode.CUSTOM && enabled },
    onClick = { onModeChange(mode.next()) },
)

internal fun panelReorderActions(
    onCancel: () -> Unit,
    onSave: () -> Unit,
) = listOf(
    PanelAction(OutlinedIcons.Close, "取消", onClick = onCancel),
    PanelAction(OutlinedIcons.Save, "保存", onClick = onSave),
)

internal fun <T> List<T>.moveItem(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
