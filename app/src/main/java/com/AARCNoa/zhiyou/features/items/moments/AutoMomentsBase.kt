package com.AARCNoa.zhiyou.features.items.moments

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi.classImproveInteractionLayout
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi.classImproveSnsInfo
import com.AARCNoa.zhiyou.features.api.ui.WeMomentsApi.fieldInteractionSnsInfo
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.ui.utils.findViewWhich
import com.AARCNoa.zhiyou.ui.utils.rootView
import com.AARCNoa.zhiyou.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Shared base for [AutoLikeMoments] and [AutoRepostMoments].
 *
 * Owns the view-tree wiring (timeline hooks, attach/scroll listeners) and the
 * SnsInfo location logic so neither subclass has to duplicate it.
 */
abstract class AutoMomentsBase : ClickableFeature() {

    @Suppress("PropertyName")
    protected abstract val TAG: String

    private data class TimelineBinding(
        val list: WeakReference<ViewGroup>,
        val layoutChangeListener: View.OnLayoutChangeListener,
        val globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener,
    )

    private val timelineLock = Any()
    private val timelineBindings = WeakHashMap<ViewGroup, TimelineBinding>()
    private val pendingAttachTasks = mutableListOf<Pair<ViewGroup, Runnable>>()

    @Volatile
    private var timelineHooksInstalled = false

    // ==================== Timeline hooks ====================

    protected fun installTimelineHooks() {
        if (timelineHooksInstalled) return
        timelineHooksInstalled = true
        listOf(
            ImproveSnsTimelineUI::class.java,
            SnsUserUI::class.java
        ).forEach { clazz ->
            clazz.reflekt()
                .firstMethod { name = "onCreate" }
                .hookAfter { scheduleAttach(thisObject as Activity) }
            clazz.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfter { scheduleAttach(thisObject as Activity) }
        }
    }

    private fun scheduleAttach(activity: Activity) {
        if (!isActive) return
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
            lateinit var task: Runnable
            task = Runnable {
                synchronized(timelineLock) {
                    pendingAttachTasks.removeAll { it.second === task }
                }
                if (!isActive) return@Runnable
                runCatching { attachToTimelineList(root) }
                    .onFailure { WeLogger.w(TAG, "failed to attach Moments list observer", it) }
            }
            synchronized(timelineLock) {
                if (!isActive) return@forEach
                pendingAttachTasks += root to task
            }
            root.postDelayed(task, delayMs.toLong())
        }
    }

    private fun attachToTimelineList(root: ViewGroup) {
        val list = root.findViewWhich<ViewGroup> { it is WxRecyclerView } ?: return
        val listRef = WeakReference(list)
        val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isActive) listRef.get()?.let { processVisibleItems(it) }
        }
        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (isActive) listRef.get()?.let { processVisibleItems(it) }
        }
        synchronized(timelineLock) {
            if (!isActive || timelineBindings.containsKey(root)) return
            list.addOnLayoutChangeListener(layoutChangeListener)
            list.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
            timelineBindings[root] = TimelineBinding(listRef, layoutChangeListener, globalLayoutListener)
        }
        processVisibleItems(list)
    }

    protected fun clearTimelineObservers() {
        synchronized(timelineLock) {
            pendingAttachTasks.forEach { (root, task) -> root.removeCallbacks(task) }
            pendingAttachTasks.clear()
            timelineBindings.values.forEach { binding ->
                val list = binding.list.get() ?: return@forEach
                list.removeOnLayoutChangeListener(binding.layoutChangeListener)
                if (list.viewTreeObserver.isAlive) {
                    list.viewTreeObserver.removeOnGlobalLayoutListener(binding.globalLayoutListener)
                }
            }
            timelineBindings.clear()
            timelineHooksInstalled = false
        }
    }

    /** Called whenever visible list items may have changed. */
    protected abstract fun processVisibleItems(list: ViewGroup)

    // ==================== SnsInfo location ====================

    protected fun locateSnsInfo(itemView: View): Any? {
        extractImproveSnsInfo(itemView)?.let { return it }

        val interactionView = itemView.findViewWhich<View> {
            classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null

        return extractImproveSnsInfo(interactionView)
            ?: fieldInteractionSnsInfo.field.get(interactionView)
    }

    private fun extractImproveSnsInfo(receiver: Any): Any? {
        if (classImproveSnsInfo.clazz.isInstance(receiver)) return receiver

        receiver.reflekt()
            .firstMethodOrNull { parameters(); superclass(); returnType { it isSubclassOf classImproveSnsInfo.clazz } }
            ?.invoke()?.let { return it }

        receiver.reflekt().firstMethodOrNull {
            name = "getImproveListItem"
            parameters()
            superclass()
        }?.invoke()?.let { listItem ->
            listItem.reflekt()
                .firstMethodOrNull { parameters(); superclass(); returnType { it isSubclassOf classImproveSnsInfo.clazz } }
                ?.invoke()?.let { return it }
            listItem.reflekt()
                .firstFieldOrNull { superclass(); type { it isSubclassOf classImproveSnsInfo.clazz } }
                ?.get()?.let { return it }
        }

        return receiver.reflekt()
            .firstFieldOrNull { superclass(); type { it isSubclassOf classImproveSnsInfo.clazz } }
            ?.get()
    }

    // ==================== AntiMomentsDelete interception check ====================

    protected fun isIntercepted(snsInfo: Any): Boolean {
        val content = WeMomentsApi.getContentText(snsInfo) ?: return false
        return content.contains(AntiMomentsDelete.INTERCEPT_MARKER)
    }
}
