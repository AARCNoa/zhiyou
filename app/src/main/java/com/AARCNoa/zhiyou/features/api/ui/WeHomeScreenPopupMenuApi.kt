package com.AARCNoa.zhiyou.features.api.ui

import android.graphics.drawable.Drawable
import android.os.Looper
import android.util.SparseArray
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import androidx.collection.mutableIntObjectMapOf
import androidx.core.util.size
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.runOnUiThread
import com.AARCNoa.zhiyou.utils.hookBeforeDirectly
import com.AARCNoa.zhiyou.utils.reflection.BBool
import com.AARCNoa.zhiyou.utils.reflection.BInt
import com.AARCNoa.zhiyou.utils.reflection.BString
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "首页菜单服务", categories = ["API"], description = "提供向首页右上角菜单添加菜单项的能力")
object WeHomeScreenPopupMenuApi : ApiFeature(), IResolveDex {

    interface IMenuItemsProvider {
        fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: () -> Unit
    ) {
        val fakeResId get() = id + text.hashCode()
    }

    private val providers = ProviderRegistry<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers.register(provider)
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers.unregister(provider)
    }

    private const val TAG = "WeHomeScreenPopupMenuApi"

    private val fakeResIdToResMap = mutableIntObjectMapOf<Drawable>()

    /** 已装过 getView hook 的适配器类：按类去重，避免每次 addItem 都再叠一层且从不卸载。 */
    private val hookedAdapterClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    /** 我们注入过的菜单项包装（弱引用）：同一份 items 重复 addItem 时先摘掉旧的那份。 */
    private val injectedWrappers: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap())

    /** 每个适配器最后一次 notifyDataSetChanged 时的条目数，布局前拿它对账。 */
    private val expectedCounts: MutableMap<BaseAdapter, Int> = WeakHashMap()

    private val methodAddItem by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings(
                "MicroMsg.PlusSubMenuHelper",
                "dyna plus config is null, we use default one"
            )
        }
    }
    private val methodHandleItemClick by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
        }
    }
    private val classMenuItemData by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BString)
            addFieldForType(BInt)
            addFieldForType(BInt)
            addFieldForType(BInt)
            addFieldForType(BString)
            fieldCount(5)
            methods {
                add {
                    usingEqStrings("")
                }
            }
        }
    }
    private val classMenuItemWrapper by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BBool)
            addFieldForType(classMenuItemData.clazz)
        }
    }

    override fun onEnable() {
        // WeChat 8.0.70 moved this to com.tencent.mm.ui.HomeUI
        methodAddItem.hookAfter {
            var thisObj = thisObject

            if (thisObj.javaClass.simpleName == "HomeUI") {
                thisObj = thisObj.reflekt()
                    .firstField { type = methodHandleItemClick.method.declaringClass }
                    .get()!!
            }

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val baseAdapter = thisObj.reflekt()
                .firstField {
                    type { it isSubclassOf BaseAdapter::class }
                }
                .get()!! as BaseAdapter

            if (hookedAdapterClasses.add(baseAdapter.javaClass)) {
                baseAdapter.reflekt().firstMethod {
                    name = "getView"
                }.apply {
                    var unhook: XC_MethodHook.Unhook? = null

                    hookBefore {
                        unhook = ImageView::class.reflekt().firstMethod {
                            name = "setImageResource"
                        }.hookBeforeDirectly {
                            try {
                                val fakeResId = args[0] as? Int ?: return@hookBeforeDirectly
                                val imageView = thisObject as? ImageView ?: return@hookBeforeDirectly
                                imageView.setImageDrawable(fakeResIdToResMap[fakeResId] ?: return@hookBeforeDirectly)
                                // setImageResource 返回 void，设置 result = null 阻断原方法
                                if (method is java.lang.reflect.Method) {
                                    val returnType = (method as java.lang.reflect.Method).returnType
                                    if (returnType == Void.TYPE) {
                                        result = null
                                    }
                                }
                            } catch (e: Throwable) {
                                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                            }
                        }
                    }

                    hookAfter {
                        unhook!!.unhook()
                    }
                }
            }

            // 注入与 notifyDataSetChanged 必须在主线程上作为整体同步完成。
            // 原先是「同步 put、再 post 一个 notify」：两者之间 ListView 看到数量已变却没
            // 收到通知，此时一旦布局（手指轻微滑动后抬起的 onTouchUp → layoutChildren）就会
            // 抛 IllegalStateException；微信若在后台线程调 addItem，SparseArray 还会被并发修改。
            val hookParam = this
            val inject = {
                for (i in items.size - 1 downTo 0) {
                    if (injectedWrappers.contains(items.valueAt(i))) items.removeAt(i)
                }
                var injected = 0
                for (provider in providers.snapshot()) {
                    try {
                        for (item in provider.getMenuItems(hookParam)) {
                            fakeResIdToResMap[item.fakeResId] = item.drawable

                            val itemData = classMenuItemData.clazz.createInstance(
                                item.id,
                                item.text,
                                "",
                                item.fakeResId,
                                0
                            )
                            val itemWrapper =
                                classMenuItemWrapper.clazz.createInstance(itemData)
                            items.put(items.size, itemWrapper)
                            injectedWrappers.add(itemWrapper)
                            injected++
                        }
                    } catch (ex: Exception) {
                        WeLogger.e(
                            TAG,
                            "provider ${provider.javaClass.name} threw while providing menu items",
                            ex
                        )
                    }
                }
                baseAdapter.notifyDataSetChanged()
                expectedCounts[baseAdapter] = baseAdapter.count
                WeLogger.d(
                    TAG,
                    "注入 $injected 项：items=${items.size()} adapter=${baseAdapter.count} " +
                        "thread=${Thread.currentThread().name}"
                )
            }
            if (Looper.myLooper() == Looper.getMainLooper()) inject()
            else runOnUiThread { inject() }
        }

        // 兜底：我们通知完之后条目数又变了（微信异步补菜单项、通知没送到 ListView 等），
        // ListView 并不知道，手指一抬直接 layoutChildren 就会抛
        // 「The content of the adapter has changed but ListView did not receive a notification」。
        // 布局前对一次账，不一致就再通知一次，让 ListView 的 mItemCount 跟上。
        ListView::class.reflekt().firstMethod { name = "layoutChildren" }.hookBefore {
            val adapter = (thisObject as? ListView)?.adapter as? BaseAdapter ?: return@hookBefore
            val expected = expectedCounts[adapter] ?: return@hookBefore
            val actual = adapter.count
            if (actual == expected) return@hookBefore
            expectedCounts[adapter] = actual
            WeLogger.e(TAG, "菜单条目数在通知后又变了：$expected -> $actual，布局前重新同步")
            adapter.notifyDataSetChanged()
        }

        methodHandleItemClick.hookBefore {
            val thisObj = thisObject

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val position = args[2] as Int
            val itemWrapper = items.get(position)
            val itemData = itemWrapper.reflekt()
                .firstField { type = classMenuItemData.clazz }.get()!!
            val id = itemData.reflekt()
                .fields { type = Int::class }[1].get()!! as Int

            for (provider in providers.snapshot()) {
                for (item in provider.getMenuItems(this)) {
                    if (item.id == id) {
                        try {
                            item.onClick()
                            return@hookBefore
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }
}
